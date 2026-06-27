package dev.tramai.core.approval.gateway

import java.time.Instant

/**
 * A record representing a securely stored resume credential for an approval.
 *
 * Instances are created during transactional approval-request creation and
 * stored in an internal encrypted credential store. They are never exposed
 * through inbox, REST, audit, logs, or reviewer UI.
 *
 * @property approvalId The approval this credential belongs to.
 * @property workflowRunId The workflow run that will be resumed.
 * @property resumeToken The sealed resume credential.
 * @property createdAt When this credential was created (same as the approval request).
 * @property expiresAt When this credential expires (same as the approval expiry).
 * @property version Optimistic locking version.
 */
data class ApprovalResumeCredentialRecord(
    val approvalId: String,
    val workflowRunId: String,
    val resumeToken: SealedResumeToken,
    val createdAt: Instant,
    val expiresAt: Instant,
    val version: Long,
)
