package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalConsumptionReceipt
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalStoreConflictException
import dev.tramai.core.exception.ApprovalStoreNotConsumableException
import dev.tramai.core.exception.ApprovalStoreNotFoundException
import dev.tramai.core.exception.ApprovalStoreTokenRejectedException
import dev.tramai.core.exception.IllegalApprovalTransitionException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class InMemoryApprovalStore(
    private val clock: Clock = Clock.systemUTC(),
    private val maxIdLength: Int = 256,
    private val maxCommentLength: Int = 4096,
    private val maxCreationTtl: Duration = Duration.ofMinutes(15),
) : ApprovalStore {

    init {
        require(maxCreationTtl > Duration.ZERO) {
            "maxCreationTtl must be positive"
        }
    }

    private val store = ConcurrentHashMap<String, ApprovalRequest>()

    override suspend fun create(request: ApprovalRequest): ApprovalRequest {
        // Version
        require(request.version == 0L) { "Initial approval version must be 0, got ${request.version}" }

        // Status
        require(request.status == ApprovalStatus.PENDING) { "Initial approval status must be PENDING, got ${request.status}" }

        // No decision fields set
        require(request.decidedBy == null) { "Initial approval must not have decidedBy set" }
        require(request.decidedAt == null) { "Initial approval must not have decidedAt set" }
        require(request.decisionComment == null) { "Initial approval must not have decisionComment set" }

        // Approval ID
        validateIdField(request.approvalId, "approvalId", maxIdLength)

        // Requested by
        validateIdField(request.requestedBy, "requestedBy", maxIdLength)

        // Binding fields
        val binding = request.binding
        validateIdField(binding.workflowRunId, "workflowRunId", maxIdLength)
        validateIdField(binding.toolName, "toolName", maxIdLength)
        validateIdField(binding.policyVersion, "policyVersion", maxIdLength)

        // Expiry: must be in the future
        val now = clock.instant()
        require(request.expiresAt > now) { "expiresAt must be in the future, got $now for expiry ${request.expiresAt}" }
        require(request.expiresAt > request.requestedAt) { "expiresAt must be after requestedAt" }

        // requestedAt must not be in the future
        require(request.requestedAt <= now) { "requestedAt must not be in the future, got ${request.requestedAt} for now $now" }
        require(request.consumedBy == null) { "Initial approval must not have consumedBy set" }
        require(request.consumedAt == null) { "Initial approval must not have consumedAt set" }

        // Bounded TTL: defense-in-depth layer against callers bypassing the coordinator
        val ttl = Duration.between(request.requestedAt, request.expiresAt)
        require(ttl <= maxCreationTtl) {
            "expiresAt exceeds maximum creation TTL of $maxCreationTtl"
        }

        // Atomically insert
        val existing = store.putIfAbsent(request.approvalId, request)
        if (existing != null) throw ApprovalStoreConflictException(request.approvalId)

        return request
    }

    override suspend fun get(approvalId: String): ApprovalRequest? {
        validateIdField(approvalId, "approvalId", maxIdLength)
        return store[approvalId]
    }

    override suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: ApprovalTransition,
    ): ApprovalRequest {
        // Validate approvalId
        validateIdField(approvalId, "approvalId", maxIdLength)

        // Validate comment length
        when (transition) {
            is ApprovalTransition.Approve -> transition.comment?.let {
                require(it.length <= maxCommentLength) { "Comment exceeds maximum length of $maxCommentLength" }
            }
            is ApprovalTransition.Deny -> transition.comment?.let {
                require(it.length <= maxCommentLength) { "Comment exceeds maximum length of $maxCommentLength" }
            }
            is ApprovalTransition.Timeout -> {}
        }

        // Validate decidedBy for non-timeout transitions
        when (transition) {
            is ApprovalTransition.Approve ->
                validateIdField(transition.decidedBy, "decidedBy", maxIdLength)
            is ApprovalTransition.Deny ->
                validateIdField(transition.decidedBy, "decidedBy", maxIdLength)
            is ApprovalTransition.Timeout -> {}
        }

        val result = store.compute(approvalId) { _, current ->
            val req = current ?: throw ApprovalStoreNotFoundException(approvalId)

            if (req.version != expectedVersion) throw ApprovalStoreConflictException(approvalId)

            val now = clock.instant()  // captured atomically with the mutation
            val nextStatus = resolveNextStatus(req, transition, now)

            req.copy(
                status = nextStatus,
                version = incrementVersion(approvalId, req.version),
                decidedAt = when (transition) {
                    is ApprovalTransition.Approve -> now
                    is ApprovalTransition.Deny -> now
                    is ApprovalTransition.Timeout -> now
                },
                decidedBy = when (transition) {
                    is ApprovalTransition.Approve -> transition.decidedBy
                    is ApprovalTransition.Deny -> transition.decidedBy
                    is ApprovalTransition.Timeout -> null
                },
                decisionComment = when (transition) {
                    is ApprovalTransition.Approve -> transition.comment
                    is ApprovalTransition.Deny -> transition.comment
                    is ApprovalTransition.Timeout -> null
                },
            )
        }

        return result ?: throw ApprovalStoreNotFoundException(approvalId)
    }

    override suspend fun consumeApprovedOrReplay(
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: Sha256Digest,
        consumedBy: String,
    ): ApprovalConsumptionReceipt {
        validateIdField(approvalId, "approvalId", maxIdLength)
        validateIdField(consumedBy, "consumedBy", maxIdLength)

        val replayed = AtomicBoolean(false)
        val result = store.compute(approvalId) { _, current ->
            val req = current ?: throw ApprovalStoreNotFoundException(approvalId)

            if (req.status != ApprovalStatus.APPROVED) throw ApprovalStoreNotConsumableException(approvalId)

            if (!tokenDigestsMatch(presentedTokenDigest, req.binding.approvalTokenDigest)) {
                throw ApprovalStoreTokenRejectedException(approvalId)
            }

            if (req.consumedAt == null && req.consumedBy == null) {
                if (req.version != expectedVersion) throw ApprovalStoreConflictException(approvalId)

                val now = clock.instant()
                if (now >= req.expiresAt) throw ApprovalStoreNotConsumableException(approvalId)

                replayed.set(false)
                req.copy(
                    consumedBy = consumedBy,
                    consumedAt = now,
                    version = incrementVersion(approvalId, req.version),
                )
            } else {
                if (req.consumedAt == null || req.consumedBy == null) {
                    throw ApprovalStoreNotConsumableException(approvalId)
                }
                if (req.consumedBy != consumedBy) throw ApprovalStoreNotConsumableException(approvalId)

                val replayVersion = try {
                    Math.addExact(expectedVersion, 1L)
                } catch (_: ArithmeticException) {
                    throw ApprovalStoreConflictException(approvalId)
                }
                if (req.version != replayVersion) throw ApprovalStoreConflictException(approvalId)

                replayed.set(true)
                req
            }
        }

        return ApprovalConsumptionReceipt(
            request = result ?: throw ApprovalStoreNotFoundException(approvalId),
            replayed = replayed.get(),
        )
    }

    private fun incrementVersion(
        approvalId: String,
        version: Long,
    ): Long =
        try {
            Math.addExact(
                version,
                1L,
            )
        } catch (_: ArithmeticException) {
            throw ApprovalStoreConflictException(
                approvalId,
            )
        }

    private fun tokenDigestsMatch(
        presentedTokenDigest: Sha256Digest,
        storedTokenDigest: Sha256Digest,
    ): Boolean =
        MessageDigest.isEqual(
            presentedTokenDigest.value.toByteArray(StandardCharsets.US_ASCII),
            storedTokenDigest.value.toByteArray(StandardCharsets.US_ASCII),
        )

    private fun resolveNextStatus(
        current: ApprovalRequest,
        transition: ApprovalTransition,
        now: Instant,
    ): ApprovalStatus {
        return when (current.status) {
            ApprovalStatus.PENDING -> {
                // Expiry check: if expired (now >= expiresAt), only timeout is allowed
                if (now >= current.expiresAt) {
                    if (transition is ApprovalTransition.Timeout) {
                        return ApprovalStatus.TIMED_OUT
                    }
                    throw IllegalApprovalTransitionException(
                        current.approvalId, current.status, transition.targetStatus(),
                        "approval has expired at ${current.expiresAt}",
                    )
                }
                // Not expired: timeout is NOT allowed, approve and deny are fine
                when (transition) {
                    is ApprovalTransition.Approve -> {
                        ApprovalStatus.APPROVED
                    }
                    is ApprovalTransition.Deny -> ApprovalStatus.DENIED
                    is ApprovalTransition.Timeout -> {
                        throw IllegalApprovalTransitionException(
                            current.approvalId, current.status, transition.targetStatus(),
                            "Cannot time out approval before expiry at ${current.expiresAt}",
                        )
                    }
                }
            }
            ApprovalStatus.APPROVED -> throw IllegalApprovalTransitionException(
                current.approvalId, current.status,
                transition.targetStatus(),
                "approval already granted",
            )
            ApprovalStatus.DENIED -> throw IllegalApprovalTransitionException(
                current.approvalId, current.status,
                transition.targetStatus(),
                "approval already denied",
            )
            ApprovalStatus.TIMED_OUT -> throw IllegalApprovalTransitionException(
                current.approvalId, current.status,
                transition.targetStatus(),
                "approval already timed out",
            )
        }
    }

    private fun validateIdField(value: String, fieldName: String, maxLength: Int): String {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "$fieldName must not be blank" }
        require(trimmed.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
        require(trimmed.length <= maxLength) { "$fieldName exceeds maximum length of $maxLength" }
        require(trimmed == value) { "$fieldName must not contain surrounding whitespace" }
        return trimmed
    }
}
