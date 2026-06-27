package dev.tramai.core.approval.gateway

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.WorkflowRunId
import java.time.Instant

/**
 * A record representing a securely stored resume credential for an approval.
 *
 * Instances are created during transactional approval-request creation and
 * stored in an internal encrypted credential store. They are never exposed
 * through inbox, REST, audit, logs, or reviewer UI.
 *
 * @property approvalId The approval this credential belongs to (value type).
 * @property workflowRunId The workflow run that will be resumed (value type).
 * @property resumeToken The sealed resume credential.
 * @property createdAt When this credential was created (same as the approval request).
 * @property expiresAt When this credential expires (same as the approval expiry).
 * @property version Optimistic locking version.
 */
data class ApprovalResumeCredentialRecord(
    val approvalId: ApprovalId,
    val workflowRunId: WorkflowRunId,
    val resumeToken: SealedResumeToken,
    val createdAt: Instant,
    val expiresAt: Instant,
    val version: Long,
)
