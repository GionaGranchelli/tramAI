package dev.tramai.core.approval

import java.time.Instant

data class ApprovalContinuation(
    val approvalId: String,
    val workflowRunId: String,
    val correlationId: String,
    val toolCallId: String,
    val toolName: String,
    val argumentsDigest: Sha256Digest,
    val policyVersion: String,
    val workflowDigest: Sha256Digest,
    val status: ApprovalContinuationStatus,
    val createdAt: Instant,
    val approvalExpiresAt: Instant,
    val claimedBy: String?,
    val claimedAt: Instant?,
    val completedAt: Instant?,
    val version: Long,
)
