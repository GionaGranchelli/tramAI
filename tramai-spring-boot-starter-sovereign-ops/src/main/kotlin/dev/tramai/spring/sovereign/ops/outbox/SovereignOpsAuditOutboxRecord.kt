package dev.tramai.spring.sovereign.ops.outbox

import java.time.Instant
import java.util.UUID

/**
 * Replay-safe audit outbox record for sovereign ops mutations.
 *
 * Records audit intent alongside the approval state change.
 * The record is created in [SovereignOpsAuditOutboxStatus.PREPARED] state
 * before the approval transition -- it is not dispatchable until the
 * transition succeeds and the record is moved to
 * [SovereignOpsAuditOutboxStatus.PENDING].
 *
 * ## Status lifecycle
 * ```
 * PREPARED (appended, transition pending)
 *   ├---> PENDING (transition succeeded, dispatchable)
 *   |      └---> EMITTING (claimed)
 *   |             ├---> EMITTED (success)
 *   |             ├---> FAILED_RETRYABLE -> EMITTING (re-claim, attempt + 1)
 *   |             ├---> FAILED_PERMANENT (terminal)
 *   |             └---> EMITTING (expired claim re-claimed, attempt + 1)
 *   └---> FAILED_PERMANENT (transition failed, orphaned intent)
 * ```
 *
 * ## Security invariants
 * - Never stores raw approval ID (only [aggregateIdDigest])
 * - Never stores raw reason text (only [reasonDigest] + [reasonLength])
 * - Never stores approval tokens, resume tokens, or replay envelopes
 * - Never stores prompts, model responses, or tool arguments
 * - Never stores raw payloads
 *
 * @property outboxId Unique identifier for this outbox record.
 * @property aggregateType The domain aggregate type (e.g. "approval").
 * @property aggregateIdDigest SHA-256 hex digest of the aggregate identifier.
 * @property operation The operation name (e.g. "denyApproval").
 * @property eventKey Deterministic key for idempotent replay (e.g. sha256("deny:$approvalId:$version")).
 * @property actor The identity of the actor who performed the operation.
 * @property workflowRunId The workflow run ID, if available.
 * @property correlationId The correlation ID, if available.
 * @property approvalStatus The resulting approval status (e.g. "DENIED"), or `null` when the record is
 * not a decision record. 0.7.6f made this nullable rather than using a sentinel, so an absent status can
 * never be mistaken for a real one.
 * @property approvalVersion The version of the approval after the transition.
 * @property reasonDigest SHA-256 hex digest of the reason string.
 * @property reasonLength Length of the reason string.
 * @property createdAt When this outbox record was created.
 * @property status Current outbox processing status.
 * @property attemptCount Number of emission attempts.
 * @property lastErrorCode Last error code, if any.
 * @property claimedBy The identity that claimed this record for dispatch, if any.
 * @property claimedAt When this record was claimed for dispatch, if any.
 * @property claimExpiresAt When the current claim expires and another dispatcher may re-claim.
 * @property emittedAt When the audit event was successfully emitted, if ever.
 * @property actorIssuer The verified identity provider of [actor] (0.7.6f). `null` on evidence recorded
 * before 0.7.6f, which means "issuer unknown" -- never "same issuer as the current request".
 * @property actorIdentityDigest Canonical digest binding `(issuer, subject)`, 64 lowercase hex without a
 * prefix (see `dev.tramai.core.operator.ActorIdentityDigest`). Deliberately distinguishable from the
 * `sha256:`-prefixed digests that predate 0.7.6f.
 * @property previousApprovalStatus The approval status before the transition, recorded rather than derived.
 * @property previousApprovalVersion The approval version before the transition, recorded rather than derived.
 * @property evidenceKind What this record is evidence of. Null on records written before 0.7.6f; a reader
 *   derives those from persisted state instead of guessing (0.7.6f slice 4).
 * @property refusalReason For [SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL] evidence, the refusal
 *   reason's wire name (e.g. `missing-capability`). Persisted because it is part of the refusal's identity,
 *   not derived at dispatch time. `null` for decision evidence.
 * @property refusalPolicyVersion The policy version the refusal was evaluated against, or `null` when no
 *   policy was consulted (a capability refusal decides without reading the approval). Persisted because a
 *   hash is not a record: an auditor must be able to read *which* policy governed the refusal, and `null`
 *   here is a positive statement that none was consulted, distinct from an unknown value.
 * @property refusalExpectedVersion The approval version the caller expected when it was refused. Persisted
 *   for the same reason: it is a key input, and a key alone cannot reconstruct it.
 */
data class SovereignOpsAuditOutboxRecord(
    val outboxId: String = UUID.randomUUID().toString(),
    val aggregateType: String = "approval",
    val aggregateIdDigest: String,
    val operation: String = "denyApproval",
    val eventKey: String,
    val actor: String,
    val workflowRunId: String?,
    val correlationId: String?,
    val approvalStatus: String?,
    val approvalVersion: Long?,
    val reasonDigest: String,
    val reasonLength: Int,
    val createdAt: Instant = Instant.now(),
    val status: SovereignOpsAuditOutboxStatus = SovereignOpsAuditOutboxStatus.PREPARED,
    val attemptCount: Int = 0,
    val lastErrorCode: String? = null,
    val claimedBy: String? = null,
    val claimedAt: Instant? = null,
    val claimExpiresAt: Instant? = null,
    val emittedAt: Instant? = null,
    val actorIssuer: String? = null,
    val actorIdentityDigest: String? = null,
    val previousApprovalStatus: String? = null,
    val previousApprovalVersion: Long? = null,
    val evidenceKind: SovereignOpsAuditEvidenceKind? = null,
    val refusalReason: String? = null,
    val refusalPolicyVersion: String? = null,
    val refusalExpectedVersion: Long? = null,
) {
    companion object {
        /** Default claim expiry duration for stuck-EMITTING recovery. */
        val DEFAULT_CLAIM_EXPIRY: java.time.Duration = java.time.Duration.ofMinutes(5)
    }
}

/**
 * Processing status for an audit outbox record.
 */
enum class SovereignOpsAuditOutboxStatus {
    /** Record created but business mutation not yet committed. Not dispatchable. */
    PREPARED,

    /** Business mutation committed, ready for dispatch. */
    PENDING,

    /** Currently being emitted (claimed by a dispatcher). */
    EMITTING,

    /** Successfully emitted to the audit engine. */
    EMITTED,

    /** Emission failed but may be retried. */
    FAILED_RETRYABLE,

    /** Emission failed permanently and will not be retried. */
    FAILED_PERMANENT,
}
