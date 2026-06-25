package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest

/**
 * Atomic approval request creation store that persists all approval-request
 * records inside one mutation boundary.
 *
 * No approval request is created unless every required record is persisted.
 * If an optional audit intent is requested and cannot be persisted, the
 * approval request is not created.
 */
interface SovereignOpsApprovalRequestMutationStore {
    suspend fun createApprovalRequest(
        request: ApprovalGatewayPersistenceRequest,
        auditIntent: SovereignOpsAuditOutboxRecord? = null,
    ): SovereignOpsApprovalRequestMutationResult
}
