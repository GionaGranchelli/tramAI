package dev.tramai.spring.sovereign.ops

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord

/**
 * Audit emitter for sovereign operations mutations.
 *
 * Implementations produce safe, hash-chained audit events for every
 * state-changing operation exposed through the ops layer. The noop
 * implementation is used when no audit infrastructure is available.
 *
 * Sensitive data (approval tokens, resume tokens, raw replay envelopes,
 * raw tool arguments, prompts, responses, raw reason text) must NEVER
 * be included in audit events.
 */
fun interface SovereignOpsAuditEmitter {

    /**
     * Whether this emitter is backed by real audit infrastructure.
     *
     * Returns `true` for real implementations (e.g. [AuditEngineSovereignOpsAuditEmitter])
     * and `false` for [NoopSovereignOpsAuditEmitter]. Mutations should fail
     * closed when this returns `false`.
     */
    fun isActive(): Boolean = true

    /**
     * Called when an approval is denied through the sovereign ops layer.
     *
     * @param approvalId The approval request identifier.
     * @param actor The identity of the actor who performed the denial.
     * @param reason The human-readable reason for denial (never stored raw).
     * @param approvalStatus The resulting approval status (e.g. "DENIED").
     * @param approvalVersion The version of the approval after the transition.
     * @param workflowRunId The workflow run ID, if available.
     * @param correlationId The correlation ID, if available.
     */
    suspend fun approvalDenied(
        approvalId: String,
        actor: String,
        reason: String,
        approvalStatus: String,
        approvalVersion: Long?,
        workflowRunId: String?,
        correlationId: String?,
    )

    /**
     * Called during outbox dispatch replay with pre-digested audit values.
     *
     * Unlike [approvalDenied], this method receives already-digested values
     * from the outbox record — the implementation must NOT re-hash them.
     *
     * The default implementation delegates to [approvalDenied], passing
     * [SovereignOpsAuditOutboxRecord.aggregateIdDigest] as [approvalId]
     * and an empty reason. Override to emit the pre-digested values directly.
     */
    suspend fun approvalDeniedFromOutbox(record: SovereignOpsAuditOutboxRecord) {
        approvalDenied(
            approvalId = record.aggregateIdDigest,
            actor = record.actor,
            reason = "",
            approvalStatus = record.approvalStatus,
            approvalVersion = record.approvalVersion,
            workflowRunId = record.workflowRunId,
            correlationId = record.correlationId,
        )
    }
}
