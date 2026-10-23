package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.core.approval.gateway.ApprovalResumeCredentialRecord
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadata

/**
 * Additive governed capability beside [SovereignOpsApprovalRequestMutationStore] (0.7.1d1, P3).
 *
 * A governed request must persist its approval attribution and its suspension identity from ONE
 * canonical [GovernedRunIdentity] inside the SAME transaction that already writes the approval, the
 * suspension, the continuation, the resume credential and the inbox/audit artifacts. Composing a
 * legacy mutation with a separate governed write would let a crash between them leave a
 * half-governed approval behind — the silent downgrade 0.7.1d exists to prevent.
 *
 * The method takes the identity itself, not a nullable value and not a five-component snapshot: the
 * implementation derives the approval-row attribution from it and embeds the full identity in the
 * suspension, so the two records cannot be built from independently interpreted inputs.
 *
 * Deliberately a separate interface rather than a new method on the released SPI: third parties
 * implement that interface, and a governed request therefore REQUIRES this capability and fails
 * closed when the configured store does not provide it.
 *
 * Implementations MUST provide the atomicity [SovereignOpsApprovalRequestMutationStore] already
 * promises — every record persisted, or none — and MUST verify any existing approval row's durable
 * attribution against [identity] before reporting it as existing.
 */
interface GovernedSovereignOpsApprovalRequestMutationStore : SovereignOpsApprovalRequestMutationStore {
    suspend fun createGovernedApprovalRequest(
        request: ApprovalGatewayPersistenceRequest,
        identity: GovernedRunIdentity,
        auditIntent: SovereignOpsAuditOutboxRecord? = null,
        inboxMetadata: ApprovalInboxMetadata? = null,
        resumeCredential: ApprovalResumeCredentialRecord? = null,
    ): SovereignOpsApprovalRequestMutationResult
}
