package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.IllegalApprovalTransitionException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryApprovalStore(
    private val clock: Clock = Clock.systemUTC(),
    private val maxIdLength: Int = 256,
    private val maxCommentLength: Int = 4096,
    private val maxDigestLength: Int = 1024,
    private val maxCasRetries: Int = 10,
) : ApprovalStore {

    private val store = ConcurrentHashMap<String, ApprovalRequest>()

    override suspend fun create(request: ApprovalRequest): ApprovalRequest {
        require(request.approvalId.isNotBlank()) { "approvalId must not be blank" }
        require(request.binding.workflowRunId.isNotBlank()) { "workflowRunId must not be blank" }
        require(request.binding.toolName.isNotBlank()) { "toolName must not be blank" }
        require(request.binding.argumentsDigest.isNotBlank()) { "argumentsDigest must not be blank" }
        require(request.binding.argumentsDigest.length <= maxDigestLength) {
            "argumentsDigest exceeds maximum length of $maxDigestLength"
        }
        require(request.approvalId.length <= maxIdLength) {
            "approvalId exceeds maximum length of $maxIdLength"
        }
        require(request.binding.workflowRunId.length <= maxIdLength) {
            "workflowRunId exceeds maximum length of $maxIdLength"
        }
        require(request.binding.toolName.length <= maxIdLength) {
            "toolName exceeds maximum length of $maxIdLength"
        }
        request.binding.policyVersion?.let {
            require(it.length <= maxIdLength) { "policyVersion exceeds maximum length of $maxIdLength" }
        }
        request.binding.workflowDigest?.let {
            require(it.length <= maxDigestLength) { "workflowDigest exceeds maximum length of $maxDigestLength" }
        }
        require(request.status == ApprovalStatus.PENDING) {
            "Initial approval status must be PENDING, got ${request.status}"
        }

        val existing = store.putIfAbsent(request.approvalId, request)
        require(existing == null) {
            "Approval '${request.approvalId}' already exists"
        }

        return request
    }

    override suspend fun get(approvalId: String): ApprovalRequest? = store[approvalId]

    override suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: ApprovalTransition,
    ): ApprovalRequest {
        // Validate comment length before entering the retry loop
        when (transition) {
            is ApprovalTransition.Approve -> transition.comment?.let {
                require(it.length <= maxCommentLength) {
                    "Comment exceeds maximum length of $maxCommentLength in Approve transition for '$approvalId'"
                }
            }
            is ApprovalTransition.Deny -> transition.comment?.let {
                require(it.length <= maxCommentLength) {
                    "Comment exceeds maximum length of $maxCommentLength in Deny transition for '$approvalId'"
                }
            }
            is ApprovalTransition.Timeout -> { /* no comment to validate */ }
        }

        var retries = 0
        while (true) {
            val current = store[approvalId]
                ?: throw IllegalArgumentException("Approval '$approvalId' not found")

            require(current.version == expectedVersion) {
                "Approval '$approvalId' version mismatch: expected $expectedVersion, actual ${current.version}"
            }

            check(current.version < Long.MAX_VALUE) {
                "Approval '$approvalId' version overflow"
            }

            val now = clock.instant()
            val nextStatus = resolveNextStatus(current, transition, now)
            val updated = current.copy(
                status = nextStatus,
                version = current.version + 1,
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

            if (store.replace(approvalId, current, updated)) {
                return updated
            }
            // CAS failed — concurrent modification, retry
            retries++
            if (retries > maxCasRetries) {
                throw IllegalStateException(
                    "Approval '$approvalId' CAS retry budget exhausted after $maxCasRetries attempts"
                )
            }
        }
    }

    private fun resolveNextStatus(
        current: ApprovalRequest,
        transition: ApprovalTransition,
        now: Instant,
    ): ApprovalStatus {
        // Expiry check: if PENDING and has expiry and is expired, reject non-timeout transitions
        if (current.status == ApprovalStatus.PENDING
            && current.expiresAt != null
            && now >= current.expiresAt
            && transition !is ApprovalTransition.Timeout
        ) {
            throw IllegalApprovalTransitionException(
                current.approvalId,
                current.status,
                when (transition) {
                    is ApprovalTransition.Approve -> ApprovalStatus.APPROVED
                    is ApprovalTransition.Deny -> ApprovalStatus.DENIED
                    is ApprovalTransition.Timeout -> ApprovalStatus.TIMED_OUT
                },
                "approval has expired at ${current.expiresAt}",
            )
        }

        return when (current.status) {
            ApprovalStatus.PENDING -> when (transition) {
                is ApprovalTransition.Approve -> ApprovalStatus.APPROVED
                is ApprovalTransition.Deny -> ApprovalStatus.DENIED
                is ApprovalTransition.Timeout -> ApprovalStatus.TIMED_OUT
            }
            ApprovalStatus.APPROVED -> throw IllegalApprovalTransitionException(
                current.approvalId, current.status, ApprovalStatus.APPROVED,
                "approval already granted",
            )
            ApprovalStatus.DENIED -> throw IllegalApprovalTransitionException(
                current.approvalId, current.status,
                when (transition) {
                    is ApprovalTransition.Approve -> ApprovalStatus.APPROVED
                    is ApprovalTransition.Deny -> ApprovalStatus.DENIED
                    is ApprovalTransition.Timeout -> ApprovalStatus.TIMED_OUT
                },
                "approval already denied",
            )
            ApprovalStatus.TIMED_OUT -> throw IllegalApprovalTransitionException(
                current.approvalId, current.status,
                when (transition) {
                    is ApprovalTransition.Approve -> ApprovalStatus.APPROVED
                    is ApprovalTransition.Deny -> ApprovalStatus.DENIED
                    is ApprovalTransition.Timeout -> ApprovalStatus.TIMED_OUT
                },
                "approval already timed out",
            )
        }
    }
}
