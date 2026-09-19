package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.gateway.ApprovalResumeCredentialRecord
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadata
import dev.tramai.spring.sovereign.ops.outbox.GovernedSovereignOpsApprovalRequestMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord

/**
 * Governed mutation capability for the JDBC approval-request store (0.7.1d1, P3).
 *
 * Composition, not a second durable authority: [createGovernedApprovalRequest] forwards into the
 * same single transaction the store already runs, so the approval attribution and the suspension
 * identity are written together or not at all.
 *
 * [JdbcSovereignOpsApprovalRequestMutationStore] deliberately does not declare this interface:
 * widening a released type changes the analyzer identity it is baselined under. Same decision the
 * governed JDBC suspension store makes.
 */
class GovernedJdbcSovereignOpsApprovalRequestMutationStore(
    private val delegate: JdbcSovereignOpsApprovalRequestMutationStore,
) : SovereignOpsApprovalRequestMutationStore by delegate,
    GovernedSovereignOpsApprovalRequestMutationStore {
    override suspend fun createGovernedApprovalRequest(
        request: ApprovalGatewayPersistenceRequest,
        identity: GovernedRunIdentity,
        auditIntent: SovereignOpsAuditOutboxRecord?,
        inboxMetadata: ApprovalInboxMetadata?,
        resumeCredential: ApprovalResumeCredentialRecord?,
    ): SovereignOpsApprovalRequestMutationResult =
        delegate.createGovernedApprovalRequest(
            request = request,
            identity = identity,
            auditIntent = auditIntent,
            inboxMetadata = inboxMetadata,
            resumeCredential = resumeCredential,
        )
}
