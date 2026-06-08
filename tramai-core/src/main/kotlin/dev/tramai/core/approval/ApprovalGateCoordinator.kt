package dev.tramai.core.approval

import java.time.Instant

data class CreateApprovalCommand(
    val workflowRunId: String,
    val toolName: String,
    val argumentsDigest: Sha256Digest,
    val policyVersion: String,
    val workflowDigest: Sha256Digest,
    val requestedBy: String,
    val expiresAt: Instant,
)

data class ApprovalChallenge(
    val approvalId: String,
    val token: ApprovalToken,
    val expiresAt: Instant,
)

data class AuthorizeResumeCommand(
    val approvalId: String,
    val expectedVersion: Long,
    val presentedToken: ApprovalToken,
    val consumedBy: String,
    val workflowRunId: String,
    val toolName: String,
    val argumentsDigest: Sha256Digest,
    val policyVersion: String,
    val workflowDigest: Sha256Digest,
)

data class ApprovalAuthorization(
    val approvalId: String,
    val consumedBy: String,
    val consumedAt: Instant,
    val version: Long,
)

interface ApprovalGateCoordinator {
    suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge

    suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization

    /**
     * Cancel an existing approval request.
     *
     * Transitions the approval to DENIED status. Safe to call on approvals
     * that have already been consumed or completed — the underlying store
     * validates the transition legality.
     *
     * @param approvalId The approval to cancel.
     * @param expectedVersion The version the caller expects.
     * @param reason A safe reason string for audit purposes.
     */
    suspend fun cancelApproval(
        approvalId: String,
        expectedVersion: Long,
        reason: String,
    )
}
