package dev.tramai.spring.sovereign.ops.inbox

import dev.tramai.core.approval.gateway.ApproverRole

/**
 * Extension point for providing safe inbox metadata during approval-request creation.
 *
 * Implementations produce labels for the reviewer-facing inbox without exposing
 * raw tool arguments, replay envelopes, token digests, or sensitive payloads.
 *
 * For example, a regulated claim triage workflow can provide claim-safe reviewer labels.
 *
 * @see ApprovalInboxMetadata
 * @see ApprovalInboxMetadataContext
 */
fun interface ApprovalInboxMetadataFactory {
    fun create(context: ApprovalInboxMetadataContext): ApprovalInboxMetadata?
}

/**
 * Context provided to [ApprovalInboxMetadataFactory.create] during transactional
 * approval-request creation.
 *
 * Contains only data that is safe to hand to an extension point — no raw tool
 * arguments, replay envelopes, token digests, or sensitive claim payloads.
 */
data class ApprovalInboxMetadataContext(
    val approvalId: String,
    val workflowRunId: String,
    val toolName: String,
    val requestedBy: String,
    val requiredRole: ApproverRole?,
    val correlationId: String?,
)
