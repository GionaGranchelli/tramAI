package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.exception.ApprovalContinuationConflictException
import dev.tramai.core.exception.ApprovalContinuationNotClaimableException
import dev.tramai.core.exception.ApprovalContinuationNotCompletableException
import dev.tramai.core.exception.ApprovalContinuationNotFoundException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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

    private val store = ConcurrentHashMap<String, ApprovalContinuation>()

    override suspend fun create(continuation: ApprovalContinuation): ApprovalContinuation {
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
        require(continuation.expiresAt > now) { "expiresAt must be in the future" }
        require(continuation.expiresAt > continuation.createdAt) { "expiresAt must be after createdAt" }

        val ttl = Duration.between(continuation.createdAt, continuation.expiresAt)
        require(ttl <= maxContinuationTtl) {
            "expiresAt exceeds maximum continuation TTL of $maxContinuationTtl"
        }

        val actualDigest = toolArgumentsDigester.digest(continuation.arguments)
        require(actualDigest == continuation.argumentsDigest) {
            "argumentsDigest does not match arguments"
        }

        val existing = store.putIfAbsent(continuation.approvalId, continuation)
        if (existing != null) throw ApprovalContinuationConflictException(continuation.approvalId)

        return continuation
    }

    override suspend fun get(approvalId: String): ApprovalContinuation? {
        validateIdentifier(approvalId, "approvalId")
        return store[approvalId]
    }

    override suspend fun claimForExecution(
        approvalId: String,
        expectedVersion: Long,
        claimedBy: String,
    ): ApprovalContinuation {
        validateIdentifier(approvalId, "approvalId")
        validateIdentifier(claimedBy, "claimedBy")

        return store.compute(approvalId) { _, current ->
            val continuation = current ?: throw ApprovalContinuationNotFoundException(approvalId)

            if (continuation.version != expectedVersion) throw ApprovalContinuationConflictException(approvalId)
            if (continuation.status != ApprovalContinuationStatus.PENDING) {
                throw ApprovalContinuationNotClaimableException(approvalId)
            }

            val now = clock.instant()
            if (now >= continuation.expiresAt) throw ApprovalContinuationNotClaimableException(approvalId)

            continuation.copy(
                status = ApprovalContinuationStatus.CLAIMED,
                claimedBy = claimedBy,
                claimedAt = now,
                version = incrementVersion(approvalId, continuation.version),
            )
        } ?: throw ApprovalContinuationConflictException(approvalId)
    }

    override suspend fun complete(approvalId: String, expectedVersion: Long): ApprovalContinuation {
        validateIdentifier(approvalId, "approvalId")
        return store.compute(approvalId) { _, current ->
            val continuation = current ?: throw ApprovalContinuationNotFoundException(approvalId)

            if (continuation.version != expectedVersion) throw ApprovalContinuationConflictException(approvalId)
            if (continuation.status != ApprovalContinuationStatus.CLAIMED) {
                throw ApprovalContinuationNotCompletableException(approvalId)
            }
            if (continuation.claimedAt == null || continuation.claimedBy == null || continuation.completedAt != null) {
                throw ApprovalContinuationNotCompletableException(approvalId)
            }

            continuation.copy(
                status = ApprovalContinuationStatus.COMPLETED,
                completedAt = clock.instant(),
                version = incrementVersion(approvalId, continuation.version),
            )
        } ?: throw ApprovalContinuationConflictException(approvalId)
    }

    override suspend fun expire(approvalId: String, expectedVersion: Long): ApprovalContinuation {
        validateIdentifier(approvalId, "approvalId")
        return store.compute(approvalId) { _, current ->
            val continuation = current ?: throw ApprovalContinuationNotFoundException(approvalId)

            if (continuation.version != expectedVersion) throw ApprovalContinuationConflictException(approvalId)
            if (continuation.status != ApprovalContinuationStatus.PENDING) {
                throw ApprovalContinuationConflictException(approvalId)
            }

            val now = clock.instant()
            if (now < continuation.expiresAt) throw ApprovalContinuationConflictException(approvalId)

            continuation.copy(
                status = ApprovalContinuationStatus.EXPIRED,
                version = incrementVersion(approvalId, continuation.version),
            )
        } ?: throw ApprovalContinuationConflictException(approvalId)
    }

    override suspend fun cancel(approvalId: String, expectedVersion: Long): ApprovalContinuation {
        validateIdentifier(approvalId, "approvalId")
        return store.compute(approvalId) { _, current ->
            val continuation = current ?: throw ApprovalContinuationNotFoundException(approvalId)

            if (continuation.version != expectedVersion) throw ApprovalContinuationConflictException(approvalId)
            if (continuation.status != ApprovalContinuationStatus.PENDING) {
                throw ApprovalContinuationConflictException(approvalId)
            }

            val now = clock.instant()
            if (now >= continuation.expiresAt) throw ApprovalContinuationConflictException(approvalId)

            continuation.copy(
                status = ApprovalContinuationStatus.CANCELLED,
                version = incrementVersion(approvalId, continuation.version),
            )
        } ?: throw ApprovalContinuationConflictException(approvalId)
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
