package dev.tramai.core.approval

import java.time.Instant

data class ApprovalRequest(
    val approvalId: String,
    val binding: ApprovalBinding,
    val status: ApprovalStatus,
    val requestedBy: String?,
    val requestedAt: Instant,
    val expiresAt: Instant?,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionComment: String?,
    val version: Long,
)
