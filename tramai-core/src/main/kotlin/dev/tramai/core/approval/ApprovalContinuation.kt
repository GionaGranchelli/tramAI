package dev.tramai.core.approval

import java.time.Instant

data class ApprovalContinuation(
    val approvalId: String,
    val workflowRunId: String,
    val correlationId: String,
    val toolCallId: String,
    val toolName: String,
    val argumentsDigest: Sha256Digest,
    val arguments: SensitiveToolArguments,
    val policyVersion: String,
    val workflowDigest: Sha256Digest,
    val status: ApprovalContinuationStatus,
    val createdAt: Instant,
    val expiresAt: Instant,
    val claimedBy: String?,
    val claimedAt: Instant?,
    val completedAt: Instant?,
    val version: Long,
) {
    override fun toString(): String {
        return "ApprovalContinuation(approvalId=$approvalId, workflowRunId=$workflowRunId, " +
            "correlationId=$correlationId, toolCallId=$toolCallId, toolName=$toolName, " +
            "argumentsDigest=$argumentsDigest, arguments=$arguments, " +
            "policyVersion=$policyVersion, workflowDigest=$workflowDigest, " +
            "status=$status, createdAt=$createdAt, expiresAt=$expiresAt, " +
            "claimedBy=$claimedBy, claimedAt=$claimedAt, completedAt=$completedAt, version=$version)"
    }
}
