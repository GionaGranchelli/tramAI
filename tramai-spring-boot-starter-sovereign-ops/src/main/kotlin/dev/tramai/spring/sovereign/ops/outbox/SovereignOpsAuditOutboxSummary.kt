package dev.tramai.spring.sovereign.ops.outbox

import java.time.Instant

/**
 * Safe summary of an outbox record returned by [SovereignOpsAuditOutboxOperations].
 *
 * Does NOT expose:
 * - Raw approval ID (only [aggregateIdDigest])
 * - Raw reason text (only [reasonLength])
 * - Approval tokens, resume tokens, or replay envelopes
 * - Prompts, model responses, or tool arguments
 * - Raw payloads
 *
 * @property outboxId Unique identifier for this outbox record.
 * @property aggregateType The domain aggregate type (e.g. "approval").
 * @property aggregateIdDigest Safe digest of the aggregate identifier.
 * @property operation The operation name (e.g. "denyApproval").
 * @property eventKey Deterministic key for idempotent replay.
 * @property actor The identity who performed the operation.
 * @property workflowRunId The workflow run ID, if available.
 * @property correlationId The correlation ID, if available.
 * @property approvalStatus The resulting approval status (e.g. "DENIED").
 * @property approvalVersion The version of the approval after the transition.
 * @property reasonLength Length of the reason string (not the content).
 * @property status Current outbox processing status.
 * @property attemptCount Number of emission attempts.
 * @property lastErrorCode Last error code, if any (sanitised for safe display).
 * @property claimedBy The identity that claimed this record, if any.
 * @property claimedAt When this record was claimed, if any.
 * @property claimExpiresAt When the claim expires and another dispatcher may re-claim.
 * @property createdAt When this outbox record was created.
 * @property emittedAt When the audit event was successfully emitted, if ever.
 */
data class SovereignOpsAuditOutboxSummary(
    val outboxId: String,
    val aggregateType: String,
    val aggregateIdDigest: String,
    val operation: String,
    val eventKey: String,
    val actor: String,
    val workflowRunId: String?,
    val correlationId: String?,
    val approvalStatus: String,
    val approvalVersion: Long?,
    val reasonLength: Int,
    val status: SovereignOpsAuditOutboxStatus,
    val attemptCount: Int,
    val lastErrorCode: String?,
    val claimedBy: String?,
    val claimedAt: Instant?,
    val claimExpiresAt: Instant?,
    val createdAt: Instant,
    val emittedAt: Instant?,
)
