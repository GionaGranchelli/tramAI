package dev.tramai.core.policy

import java.time.Instant

/**
 * A persisted approval request that authorizes one exact tool execution.
 *
 * Created by the approval subsystem when [PolicyDecision.RequireApproval]
 * is returned by the [PolicyEngine]. The approval is single-use and bound
 * to the exact action parameters via [argumentsDigest].
 */
data class ApprovalRequest(
    val approvalId: String,
    val workflowRunId: String,
    val toolName: String,
    val argumentsDigest: String,
    /** Identity that requested the approval. */
    val requestedBy: String,
    val policyVersion: String,
    val workflowDigest: String,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val nonce: String,
    val status: ApprovalStatus,
    /** Identity that granted or denied the approval (null until decided). */
    val decidedBy: String? = null,
    /** When the decision was made (null until decided). */
    val decidedAt: Instant? = null,
    /** Optional comment from the approver. */
    val decisionComment: String? = null,
)

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED,
}
