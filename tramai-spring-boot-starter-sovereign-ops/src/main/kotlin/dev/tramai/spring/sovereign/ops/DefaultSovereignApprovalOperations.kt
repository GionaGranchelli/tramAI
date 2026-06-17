package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import kotlinx.coroutines.CancellationException

/**
 * Default implementation of [SovereignApprovalOperations].
 *
 * Delegates to an [ApprovalStore]. All mutations are guarded by
 * [SovereignOpsProperties.mutationsEnabled] and emit a safe audit event
 * via [SovereignOpsAuditEmitter] on success. Only safe summaries are
 * returned — tokens and sensitive payloads are never exposed.
 *
 * **Atomicity note**: The store transition and audit emission are separate
 * operations. If audit emission fails after a successful transition, the
 * approval state change is NOT rolled back. The caller receives a
 * [IllegalStateException] with code `tramai-sovereign-ops-audit-emission-failed`.
 */
class DefaultSovereignApprovalOperations(
    private val store: ApprovalStore,
    private val properties: SovereignOpsProperties,
    private val auditEmitter: SovereignOpsAuditEmitter,
) : SovereignApprovalOperations {

    private companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")
        private val SAFE_ACTOR = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,255}")
        private const val MAX_REASON_LENGTH = 4096
    }

    override suspend fun getApproval(approvalId: String): SovereignApprovalSummary? {
        validateApprovalId(approvalId)
        return store.get(approvalId)?.toSummary()
    }

    override suspend fun denyApproval(
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

        // Emit audit event after successful transition.
        // Makes audit failure visible without cascading approval state rollback.
        try {
            auditEmitter.approvalDenied(
                approvalId = approvalId,
                actor = actor,
                reason = reason,
                approvalStatus = updated.status.name,
                approvalVersion = updated.version,
                workflowRunId = updated.binding.workflowRunId,
                correlationId = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw IllegalStateException("tramai-sovereign-ops-audit-emission-failed")
        }

        return updated.toSummary()
    }

    // ── Validation ──

    private fun validateApprovalId(id: String) {
        require(id.isNotBlank()) { "tramai-sovereign-ops-invalid-approval-id" }
        require(id.length <= 128) { "tramai-sovereign-ops-invalid-approval-id" }
        require(SAFE_ID.matches(id)) { "tramai-sovereign-ops-invalid-approval-id" }
    }

    private fun validateActor(actor: String) {
        require(actor.isNotBlank()) { "tramai-sovereign-ops-invalid-actor" }
        require(actor.length <= 256) { "tramai-sovereign-ops-invalid-actor" }
        require(SAFE_ACTOR.matches(actor)) { "tramai-sovereign-ops-invalid-actor" }
    }

    private fun validateReason(reason: String) {
        require(reason.isNotBlank()) { "tramai-sovereign-ops-invalid-reason" }
        require(reason.length <= MAX_REASON_LENGTH) { "tramai-sovereign-ops-invalid-reason" }
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
