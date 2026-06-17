package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition

/**
 * Default implementation of [SovereignApprovalOperations].
 *
 * Delegates to an [ApprovalStore]. All mutations are guarded by
 * [SovereignOpsProperties.mutationsEnabled]. Only safe summaries
 * are returned — tokens and sensitive payloads are never exposed.
 */
class DefaultSovereignApprovalOperations(
    private val store: ApprovalStore,
    private val properties: SovereignOpsProperties,
) : SovereignApprovalOperations {

    private companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")
        private const val MAX_REASON_LENGTH = 4096
    }

    override suspend fun getApproval(approvalId: String): SovereignApprovalSummary? {
        validateApprovalId(approvalId)
        val request = store.get(approvalId) ?: return null
        return request.toSummary()
    }

    override suspend fun cancelApproval(
        approvalId: String,
        actor: String,
        reason: String,
    ): SovereignApprovalSummary {
        if (!properties.mutationsEnabled) {
            throw IllegalStateException("tramai-sovereign-ops-mutations-disabled")
        }
        validateApprovalId(approvalId)
        validateActor(actor)
        validateReason(reason)

        val request = store.get(approvalId)
            ?: throw IllegalStateException("tramai-sovereign-ops-invalid-approval-id")

        val updated = store.transition(
            approvalId = approvalId,
            expectedVersion = request.version,
            transition = ApprovalTransition.Deny(decidedBy = actor, comment = reason),
        )
        return updated.toSummary()
    }

    // ── Validation ──

    private fun validateApprovalId(id: String) {
        require(id.isNotBlank()) { "tramai-sovereign-ops-invalid-approval-id" }
        require(id.length <= 128) { "tramai-sovereign-ops-invalid-approval-id" }
        require(SAFE_ID.matches(id)) { "tramai-sovereign-ops-invalid-approval-id" }
    }

    private fun validateActor(actor: String) {
        require(actor.isNotBlank()) { "tramai-sovereign-ops-invalid-approval-id" }
        require(actor.length <= 256) { "tramai-sovereign-ops-invalid-approval-id" }
    }

    private fun validateReason(reason: String) {
        require(reason.isNotBlank()) { "tramai-sovereign-ops-invalid-approval-id" }
        require(reason.length <= MAX_REASON_LENGTH) { "tramai-sovereign-ops-invalid-approval-id" }
    }

    // ── Mapping ──

    private fun ApprovalRequest.toSummary(): SovereignApprovalSummary =
        SovereignApprovalSummary(
            approvalId = approvalId,
            status = status.name,
            workflowRunId = binding.workflowRunId,
            correlationId = null,
            createdAt = requestedAt,
            expiresAt = expiresAt,
            actor = decidedBy,
            reasonCode = decisionComment,
        )
}
