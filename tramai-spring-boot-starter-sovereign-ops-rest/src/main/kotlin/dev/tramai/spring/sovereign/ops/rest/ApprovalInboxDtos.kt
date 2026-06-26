package dev.tramai.spring.sovereign.ops.rest

import java.time.Instant

/**
 * Response body for GET /approvals list endpoint.
 */
data class ApprovalInboxListResponse(
    val items: List<ApprovalInboxItemResponse>,
    val nextCursor: String? = null,
)

/**
 * Safe projection of an approval work item for REST responses.
 *
 * Never exposes: resumeToken, approvalTokenDigest, argumentsDigest,
 * raw tool arguments, replay envelope, decision comments, or claim payload.
 */
data class ApprovalInboxItemResponse(
    val approvalId: String,
    val workflowRunId: String,
    val toolName: String,
    val status: String,
    val requestedBy: String,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val requiredRole: String? = null,
    val riskLevel: String? = null,
    val subjectType: String? = null,
    val subjectId: String? = null,
    val recommendationType: String? = null,
    val continuationStatus: String? = null,
    val version: Long,
)

/**
 * Response body for GET /approvals/{id}/work-item endpoint.
 */
data class ApprovalWorkItemResponse(
    val approvalId: String,
    val workflowRunId: String,
    val toolName: String,
    val status: String,
    val requestedBy: String,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val requiredRole: String? = null,
    val riskLevel: String? = null,
    val subjectType: String? = null,
    val subjectId: String? = null,
    val recommendationType: String? = null,
    val continuationStatus: String? = null,
    val version: Long,
)
