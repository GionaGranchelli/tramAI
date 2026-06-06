package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ClaimedApprovalContinuation
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.exception.ApprovalContinuationConflictException
import dev.tramai.core.exception.ApprovalContinuationNotClaimableException
import dev.tramai.core.exception.ApprovalContinuationNotCompletableException
import dev.tramai.core.exception.ApprovalContinuationNotFoundException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class InMemoryApprovalContinuationStore(
    private val clock: Clock = Clock.systemUTC(),
    private val maxContinuationTtl: Duration = Duration.ofMinutes(15),
    private val toolArgumentsDigester: ToolArgumentsDigester = Sha256ToolArgumentsDigester(),
) : ApprovalContinuationStore {

    init {
        require(maxContinuationTtl > Duration.ZERO) {
            "maxContinuationTtl must be positive"
        }
    }

    private val store = ConcurrentHashMap<String, StoredApprovalContinuation>()

    override suspend fun create(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments,
    ): ApprovalContinuation {
        validateIdentifier(continuation.approvalId, "approvalId")
        validateIdentifier(continuation.workflowRunId, "workflowRunId")
        validateIdentifier(continuation.correlationId, "correlationId")
        validateIdentifier(continuation.toolCallId, "toolCallId")
        validateIdentifier(continuation.toolName, "toolName")
        validateIdentifier(continuation.policyVersion, "policyVersion")
        require(continuation.version == 0L) { "Initial continuation version must be 0, got ${continuation.version}" }
        require(continuation.status == ApprovalContinuationStatus.PENDING) {
            "Initial continuation status must be PENDING, got ${continuation.status}"
        }
        require(continuation.claimedBy == null) { "Initial continuation must not have claimedBy set" }
        require(continuation.claimedAt == null) { "Initial continuation must not have claimedAt set" }
        require(continuation.completedAt == null) { "Initial continuation must not have completedAt set" }

        val now = clock.instant()
        require(continuation.createdAt <= now) { "createdAt must not be in the future" }
        require(continuation.approvalExpiresAt > now) { "approvalExpiresAt must be in the future" }
        require(continuation.approvalExpiresAt > continuation.createdAt) {
            "approvalExpiresAt must be after createdAt"
        }

        val ttl = Duration.between(continuation.createdAt, continuation.approvalExpiresAt)
        require(ttl <= maxContinuationTtl) {
            "approvalExpiresAt exceeds maximum continuation TTL of $maxContinuationTtl"
        }

        val actualDigest = toolArgumentsDigester.digest(arguments)
        require(actualDigest == continuation.argumentsDigest) {
            "argumentsDigest does not match arguments"
        }

        val existing = store.putIfAbsent(
            continuation.approvalId,
            StoredApprovalContinuation(continuation = continuation, arguments = arguments),
        )
        if (existing != null) throw ApprovalContinuationConflictException(continuation.approvalId)

        return continuation
    }

    override suspend fun get(approvalId: String): ApprovalContinuation? {
        validateIdentifier(approvalId, "approvalId")
        val now = clock.instant()
        return store.computeIfPresent(approvalId) { _, stored ->
            expireIfElapsed(approvalId, stored, now)
        }?.continuation
    }

    override suspend fun claimForExecution(
        approvalId: String,
        expectedVersion: Long,
        claimedBy: String,
    ): ClaimedApprovalContinuation {
        validateIdentifier(approvalId, "approvalId")
        validateIdentifier(claimedBy, "claimedBy")

        var claimed: ClaimedApprovalContinuation? = null
        var expired = false
        store.compute(approvalId) { _, current ->
            val stored = current ?: throw ApprovalContinuationNotFoundException(approvalId)
            val now = clock.instant()
            val normalized = expireIfElapsed(approvalId, stored, now)
            val continuation = normalized.continuation

            if (continuation.status == ApprovalContinuationStatus.EXPIRED) {
                expired = true
                return@compute normalized
            }

            if (continuation.version != expectedVersion) throw ApprovalContinuationConflictException(approvalId)
            if (continuation.status != ApprovalContinuationStatus.PENDING) {
                throw ApprovalContinuationNotClaimableException(approvalId)
            }

            val capturedArguments = normalized.arguments ?: throw ApprovalContinuationConflictException(approvalId)
            val claimedContinuation = continuation.copy(
                status = ApprovalContinuationStatus.CLAIMED,
                claimedBy = claimedBy,
                claimedAt = now,
                version = incrementVersion(approvalId, continuation.version),
            )
            claimed = ClaimedApprovalContinuation(claimedContinuation, capturedArguments)
            StoredApprovalContinuation(
                continuation = claimedContinuation,
                arguments = null,
            )
        } ?: throw ApprovalContinuationConflictException(approvalId)

        if (expired) throw ApprovalContinuationNotClaimableException(approvalId)
        return claimed ?: throw ApprovalContinuationConflictException(approvalId)
    }

    override suspend fun complete(approvalId: String, expectedVersion: Long): ApprovalContinuation {
        validateIdentifier(approvalId, "approvalId")
        return store.compute(approvalId) { _, current ->
            val stored = current ?: throw ApprovalContinuationNotFoundException(approvalId)
            val continuation = stored.continuation

            if (continuation.version != expectedVersion) throw ApprovalContinuationConflictException(approvalId)
            if (continuation.status != ApprovalContinuationStatus.CLAIMED) {
                throw ApprovalContinuationNotCompletableException(approvalId)
            }
            if (continuation.claimedAt == null || continuation.claimedBy == null || continuation.completedAt != null) {
                throw ApprovalContinuationNotCompletableException(approvalId)
            }
            if (stored.arguments != null) {
                throw ApprovalContinuationNotCompletableException(approvalId)
            }

            StoredApprovalContinuation(
                continuation = continuation.copy(
                    status = ApprovalContinuationStatus.COMPLETED,
                    completedAt = clock.instant(),
                    version = incrementVersion(approvalId, continuation.version),
                ),
                arguments = null,
            )
        }?.continuation ?: throw ApprovalContinuationConflictException(approvalId)
    }

    override suspend fun expire(approvalId: String, expectedVersion: Long): ApprovalContinuation {
        validateIdentifier(approvalId, "approvalId")
        return store.compute(approvalId) { _, current ->
            val stored = current ?: throw ApprovalContinuationNotFoundException(approvalId)
            val continuation = stored.continuation

            if (continuation.version != expectedVersion) throw ApprovalContinuationConflictException(approvalId)
            if (continuation.status != ApprovalContinuationStatus.PENDING) {
                throw ApprovalContinuationConflictException(approvalId)
            }

            val now = clock.instant()
            if (now < continuation.approvalExpiresAt) throw ApprovalContinuationConflictException(approvalId)

            expireIfElapsed(approvalId, stored, now)
        }?.continuation ?: throw ApprovalContinuationConflictException(approvalId)
    }

    override suspend fun cancel(approvalId: String, expectedVersion: Long): ApprovalContinuation {
        validateIdentifier(approvalId, "approvalId")
        var expired = false
        return store.compute(approvalId) { _, current ->
            val stored = current ?: throw ApprovalContinuationNotFoundException(approvalId)
            val now = clock.instant()
            val normalized = expireIfElapsed(approvalId, stored, now)
            val continuation = normalized.continuation

            if (continuation.status == ApprovalContinuationStatus.EXPIRED) {
                expired = true
                return@compute normalized
            }

            if (continuation.version != expectedVersion) throw ApprovalContinuationConflictException(approvalId)
            if (continuation.status != ApprovalContinuationStatus.PENDING) {
                throw ApprovalContinuationConflictException(approvalId)
            }

            StoredApprovalContinuation(
                continuation = continuation.copy(
                    status = ApprovalContinuationStatus.CANCELLED,
                    version = incrementVersion(approvalId, continuation.version),
                ),
                arguments = null,
            )
        }?.continuation?.also {
            if (expired) throw ApprovalContinuationConflictException(approvalId)
        } ?: throw ApprovalContinuationConflictException(approvalId)
    }

    override suspend fun sweepExpired(): Int {
        val now = clock.instant()
        val expiredCount = AtomicInteger()

        store.keys.forEach { approvalId ->
            store.computeIfPresent(approvalId) { _, stored ->
                val normalized = expireIfElapsed(approvalId, stored, now)
                if (normalized !== stored) {
                    expiredCount.incrementAndGet()
                }
                normalized
            }
        }

        return expiredCount.get()
    }

    private fun expireIfElapsed(
        approvalId: String,
        stored: StoredApprovalContinuation,
        now: java.time.Instant,
    ): StoredApprovalContinuation {
        val continuation = stored.continuation

        if (continuation.status != ApprovalContinuationStatus.PENDING ||
            now < continuation.approvalExpiresAt
        ) {
            return stored
        }

        return StoredApprovalContinuation(
            continuation = continuation.copy(
                status = ApprovalContinuationStatus.EXPIRED,
                version = incrementVersion(approvalId, continuation.version),
            ),
            arguments = null,
        )
    }

    private fun incrementVersion(approvalId: String, version: Long): Long =
        try {
            Math.addExact(version, 1L)
        } catch (_: ArithmeticException) {
            throw ApprovalContinuationConflictException(approvalId)
        }

    private fun validateIdentifier(value: String, fieldName: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "$fieldName must not be blank" }
        require(trimmed == value) { "$fieldName must not contain surrounding whitespace" }
        require(trimmed.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
        require(trimmed.length <= MAX_ID_LENGTH) { "$fieldName exceeds maximum length of $MAX_ID_LENGTH" }
        return trimmed
    }

    private companion object {
        private const val MAX_ID_LENGTH = 256
    }
}
