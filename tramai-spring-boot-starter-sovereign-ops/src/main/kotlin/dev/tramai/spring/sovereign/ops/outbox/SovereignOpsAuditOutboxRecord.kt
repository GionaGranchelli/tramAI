package dev.tramai.spring.sovereign.ops.outbox

import java.time.Instant
import java.util.UUID

/**
 * Replay-safe audit outbox record for sovereign ops mutations.
 *
 * Records audit intent durably alongside the approval state change,
 * so emission can be retried safely. The record is created atomically
 * with the approval transition — one cannot exist without the other.
 *
 * ## Security invariants
 * - Never stores raw approval ID (only [approvalIdDigest])
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
 * @property approvalStatus The resulting approval status (e.g. "DENIED").
 * @property approvalVersion The version of the approval after the transition.
 * @property reasonDigest SHA-256 hex digest of the reason string.
 * @property reasonLength Length of the reason string.
 * @property createdAt When this outbox record was created.
 * @property status Current outbox processing status.
 * @property attemptCount Number of emission attempts.
 * @property lastErrorCode Last error code, if any.
 * @property emittedAt When the audit event was successfully emitted, if ever.
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
    val approvalStatus: String,
    val approvalVersion: Long?,
    val reasonDigest: String,
    val reasonLength: Int,
    val createdAt: Instant = Instant.now(),
    val status: SovereignOpsAuditOutboxStatus = SovereignOpsAuditOutboxStatus.PENDING,
    val attemptCount: Int = 0,
    val lastErrorCode: String? = null,
    val emittedAt: Instant? = null,
)

/**
 * Processing status for an audit outbox record.
 */
enum class SovereignOpsAuditOutboxStatus {
    /** Waiting to be dispatched. */
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
