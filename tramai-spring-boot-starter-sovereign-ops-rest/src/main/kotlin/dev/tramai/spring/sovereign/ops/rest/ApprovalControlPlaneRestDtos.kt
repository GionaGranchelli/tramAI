package dev.tramai.spring.sovereign.ops.rest

import java.time.Instant

/**
 * Request body for POST approve and deny endpoints.
 *
 * [expectedVersion] is nullable **only** so the adapter can answer a missing or explicit-null value with a
 * non-sensitive 400 instead of letting Jackson's coercion decide. It is never defaulted, never inferred and
 * never substituted: without it the request does not reach a control-plane command at all.
 *
 * [actorId] and [actorRole] are untrusted caller claims. They are not authentication and are not treated as
 * such; operator authorization is 0.7.6e.
 */
data class ApproveDenyRequest(
    val actorId: String,
    val actorRole: String,
    val expectedVersion: Long? = null,
    val comment: String? = null,
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
    /** For `STALE`: the version the request carried. */
    val expectedVersion: Long? = null,
    /** For `STALE`: the authoritative version, so the caller can re-read and re-decide. */
    val currentVersion: Long? = null,
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
