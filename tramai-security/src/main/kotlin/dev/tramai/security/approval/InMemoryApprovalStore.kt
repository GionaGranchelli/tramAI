package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.IllegalApprovalTransitionException
import dev.tramai.core.approval.Sha256Digest
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryApprovalStore(
    private val clock: Clock = Clock.systemUTC(),
    private val maxIdLength: Int = 256,
    private val maxCommentLength: Int = 4096,
) : ApprovalStore {

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
        val normalizedId = request.approvalId.trim()
        require(normalizedId.isNotBlank()) { "approvalId must not be blank" }
        require(normalizedId.length <= maxIdLength) { "approvalId exceeds maximum length of $maxIdLength" }
        require(normalizedId == request.approvalId) { "approvalId must not contain surrounding whitespace" }

        // Requested by
        val normalizedRequester = request.requestedBy.trim()
        require(normalizedRequester.isNotBlank()) { "requestedBy must not be blank" }
        require(normalizedRequester.length <= maxIdLength) { "requestedBy exceeds maximum length of $maxIdLength" }
        require(normalizedRequester == request.requestedBy) { "requestedBy must not contain surrounding whitespace" }

        // Binding fields
        val binding = request.binding
        val normalizedWorkflowRunId = binding.workflowRunId.trim()
        require(normalizedWorkflowRunId.isNotBlank()) { "workflowRunId must not be blank" }
        require(normalizedWorkflowRunId.length <= maxIdLength) { "workflowRunId exceeds maximum length of $maxIdLength" }
        require(normalizedWorkflowRunId == binding.workflowRunId) { "workflowRunId must not contain surrounding whitespace" }

        val normalizedToolName = binding.toolName.trim()
        require(normalizedToolName.isNotBlank()) { "toolName must not be blank" }
        require(normalizedToolName.length <= maxIdLength) { "toolName exceeds maximum length of $maxIdLength" }
        require(normalizedToolName == binding.toolName) { "toolName must not contain surrounding whitespace" }

        // Digest validation
        Sha256Digest.validate(binding.argumentsDigest)
        Sha256Digest.validate(binding.workflowDigest)
        Sha256Digest.validate(binding.approvalTokenDigest)

        // Policy version
        val normalizedPolicyVersion = binding.policyVersion.trim()
        require(normalizedPolicyVersion.isNotBlank()) { "policyVersion must not be blank" }
        require(normalizedPolicyVersion.length <= maxIdLength) { "policyVersion exceeds maximum length of $maxIdLength" }
        require(normalizedPolicyVersion == binding.policyVersion) { "policyVersion must not contain surrounding whitespace" }

        // Expiry: must be in the future
        val now = clock.instant()
        require(request.expiresAt > now) { "expiresAt must be in the future, got $now for expiry ${request.expiresAt}" }
        require(request.expiresAt > request.requestedAt) { "expiresAt must be after requestedAt" }

        // Atomically insert
        val existing = store.putIfAbsent(request.approvalId, request)
        require(existing == null) { "Approval '${request.approvalId}' already exists" }

        return request
    }

    override suspend fun get(approvalId: String): ApprovalRequest? = store[approvalId]

    override suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: ApprovalTransition,
    ): ApprovalRequest {
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
            is ApprovalTransition.Approve -> {
                val normalized = transition.decidedBy.trim()
                require(normalized.isNotBlank()) { "decidedBy must not be blank" }
                require(normalized.length <= maxIdLength) { "decidedBy exceeds maximum length of $maxIdLength" }
            }
            is ApprovalTransition.Deny -> {
                val normalized = transition.decidedBy.trim()
                require(normalized.isNotBlank()) { "decidedBy must not be blank" }
                require(normalized.length <= maxIdLength) { "decidedBy exceeds maximum length of $maxIdLength" }
            }
            is ApprovalTransition.Timeout -> {}
        }

        val now = clock.instant()

        val result = store.compute(approvalId) { _, current ->
            val req = current ?: throw IllegalArgumentException("Approval '$approvalId' not found")

            require(req.version == expectedVersion) {
                "Approval '$approvalId' version mismatch: expected $expectedVersion, actual ${req.version}"
            }

            check(req.version < Long.MAX_VALUE) { "Approval '$approvalId' version overflow" }

            val nextStatus = resolveNextStatus(req, transition, now)

            req.copy(
                status = nextStatus,
                version = req.version + 1,
                decidedAt = when (transition) {
                    is ApprovalTransition.Approve -> now
                    is ApprovalTransition.Deny -> now
                    is ApprovalTransition.Timeout -> now
                },
                decidedBy = when (transition) {
                    is ApprovalTransition.Approve -> transition.decidedBy.trim()
                    is ApprovalTransition.Deny -> transition.decidedBy.trim()
                    is ApprovalTransition.Timeout -> null
                },
                decisionComment = when (transition) {
                    is ApprovalTransition.Approve -> transition.comment
                    is ApprovalTransition.Deny -> transition.comment
                    is ApprovalTransition.Timeout -> null
                },
            )
        }

        return result ?: throw IllegalArgumentException("Approval '$approvalId' not found")
    }

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
                        require(now < current.expiresAt) { "Cannot approve approval before expiry" }
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
}
