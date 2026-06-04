package dev.tramai.core.approval

data class ApprovalBinding(
    val workflowRunId: String,
    val toolName: String,
    val argumentsDigest: String,
    val policyVersion: String,
    val workflowDigest: String,
    /**
     * SHA-256 digest of a generated approval token (nonce). The raw token is
     * provided to the requestor at creation time. PR #15 will consume and
     * verify the raw token exactly once.
     */
    val approvalTokenDigest: String,
)
