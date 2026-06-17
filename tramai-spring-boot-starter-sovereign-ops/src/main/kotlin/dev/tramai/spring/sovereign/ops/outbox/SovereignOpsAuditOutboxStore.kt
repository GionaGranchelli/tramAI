package dev.tramai.spring.sovereign.ops.outbox

import java.time.Instant

/**
 * Durable store for audit outbox records.
 *
 * Implementations must be thread-safe. The outbox store is not responsible
 * for atomicity with approval state changes — that is the responsibility of
 * [dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore].
 */
interface SovereignOpsAuditOutboxStore {

    /**
     * Append a new outbox record.
     * @throws IllegalArgumentException if a record with the same [SovereignOpsAuditOutboxRecord.outboxId] exists.
     */
    suspend fun append(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord

    /**
     * Claim pending records for dispatch.
     * Transitions matching records from [SovereignOpsAuditOutboxStatus.PENDING] to [SovereignOpsAuditOutboxStatus.EMITTING].
     */
    suspend fun claimPending(
        claimedBy: String,
        limit: Int,
        now: Instant,
    ): List<SovereignOpsAuditOutboxRecord>

    /**
     * Mark a record as successfully emitted.
     */
    suspend fun markEmitted(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        emittedAt: Instant,
    ): SovereignOpsAuditOutboxRecord

    /**
     * Mark a record as failed.
     */
    suspend fun markFailed(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        errorCode: String,
        retryable: Boolean,
    ): SovereignOpsAuditOutboxRecord

    /** Retrieve an outbox record by ID. */
    suspend fun get(outboxId: String): SovereignOpsAuditOutboxRecord?

    /** List pending records (for diagnostics). */
    suspend fun listPending(limit: Int): List<SovereignOpsAuditOutboxRecord>
}
