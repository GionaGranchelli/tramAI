package dev.tramai.spring.sovereign.ops.rest

import java.time.Instant

/**
 * Request body for POST approve and deny endpoints.
 */
data class ApproveDenyRequest(
    val actorId: String,
    val actorRole: String,
    val comment: String? = null,
    val expectedVersion: Long? = null,
    val correlationId: String? = null,
)

/**
 * Request body for POST resume endpoint.
 */
data class ResumeRequest(
    val resumeToken: String,
    val resumedBy: String,
    val expectedApprovalVersion: Long? = null,
    val expectedContinuationVersion: Long? = null,
)

/**
 * Safe typed REST response for mutation endpoints.
 *
 * Never exposes: resume tokens, approval token digests, raw tool arguments,
 * replay envelopes, internal stack traces, or sensitive metadata.
 */
data class ApprovalControlPlaneResponse(
    val status: String,
    val approvalId: String,
    val actorId: String? = null,
    val version: Long? = null,
    val message: String? = null,
)

/**
 * Response body for GET approval status endpoint.
 */
data class ApprovalStatusResponse(
    val approvalId: String,
    val status: String,
    val version: Long,
    val requestedBy: String,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
    val continuationStatus: String? = null,
)
