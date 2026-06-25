package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord

/**
 * Factory for creating durable audit outbox intent records during approval request creation.
 *
 * The transactional gateway calls this factory when available to produce an audit intent
 * that is persisted atomically alongside the approval, suspended invocation, and
 * continuation records.
 *
 * Return `null` to skip audit intent for a particular request.
 *
 * ### Layering
 *
 * This factory lives outside [ApprovalGatewayRequestFactory] to keep the request factory
 * focused on approval persistence records (approval request, continuation, suspension
 * metadata) and separate from ops/outbox concerns.
 *
 * @see SovereignOpsTransactionalApprovalGateway
 * @see SovereignOpsApprovalRequestMutationStore
 */
fun interface ApprovalGatewayAuditIntentFactory {

    /**
     * Create a durable "approval-requested" audit outbox record for the given
     * gateway request.
     *
     * The returned record must have [SovereignOpsAuditOutboxRecord.status] set to
     * [SovereignOpsAuditOutboxStatus.PREPARED] so the mutation store can mark it
     * PENDING inside the same transaction.
     *
     * @param request the full persistence request that will be committed
     * @param subject the subject passed to the gateway
     * @param recommendation the recommendation passed to the gateway
     * @param requiredRole the role passed to the gateway
     * @return a prepared audit outbox record, or `null` to skip audit intent
     */
    fun approvalRequested(
        request: ApprovalGatewayPersistenceRequest,
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
    ): SovereignOpsAuditOutboxRecord?
}
