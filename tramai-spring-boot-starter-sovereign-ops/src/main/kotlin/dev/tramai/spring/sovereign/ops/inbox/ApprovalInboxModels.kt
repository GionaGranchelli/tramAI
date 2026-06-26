package dev.tramai.spring.sovereign.ops.inbox

import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApproverRole
import java.time.Instant

/**
 * Query parameters for the approval inbox.
 *
 * All filters are optional. Default status is PENDING.
 */
data class ApprovalInboxQuery(
    val status: ApprovalStatus? = ApprovalStatus.PENDING,
    val requiredRole: ApproverRole? = null,
    val requestedBy: String? = null,
    val expiresBefore: Instant? = null,
    val limit: Int = 50,
    val cursor: String? = null,
)

/**
 * Page of [ApprovalInboxWorkItem] results with cursor-based pagination.
 */
data class ApprovalInboxPage(
    val items: List<ApprovalInboxWorkItem>,
    val nextCursor: String? = null,
)

/**
 * Safe projection for reviewer-facing inbox queries.
 *
 * This is intentionally NOT [dev.tramai.core.approval.ApprovalRequest].
 * It is a safe UI/query projection that never exposes:
 * - resume tokens
 * - approval token digests
 * - raw tool arguments
 * - replay envelopes
 * - provider messages / prompts / decision comments
 * - medical details or claim payloads
 */
data class ApprovalInboxWorkItem(
    val approvalId: ApprovalId,
    val workflowRunId: String,
    val toolName: String,
    val status: ApprovalStatus,
    val requestedBy: String,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val requiredRole: ApproverRole?,
    val riskLevel: String?,
    val subjectType: String?,
    val subjectId: String?,
    val recommendationType: String?,
    val continuationStatus: ApprovalContinuationStatus?,
    val version: Long,
)
