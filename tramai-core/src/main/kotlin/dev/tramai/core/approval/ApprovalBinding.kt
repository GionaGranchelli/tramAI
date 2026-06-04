package dev.tramai.core.approval

/**
 * Immutable binding metadata that ties an approval request to a specific tool
 * invocation. All fields are non-nullable and must be provided at creation time.
 *
 * @property workflowRunId Identifies the concrete workflow execution run.
 * @property toolName Name of the tool being approved for execution.
 * @property argumentsDigest SHA-256 digest of the serialised tool arguments, locking the exact parameters.
 * @property policyVersion Policy version active at the time of the approval request.
 * @property workflowDigest SHA-256 digest of the workflow definition version, ensuring policy revalidation on changes.
 * @property approvalTokenDigest SHA-256 digest of a generated nonce token; the raw token is provided to the requestor
 *                                at creation time for single-use verification (PR #15).
 */
data class ApprovalBinding(
    val workflowRunId: String,
    val toolName: String,
    val argumentsDigest: Sha256Digest,
    val policyVersion: String,
    val workflowDigest: Sha256Digest,
    val approvalTokenDigest: Sha256Digest,
)
