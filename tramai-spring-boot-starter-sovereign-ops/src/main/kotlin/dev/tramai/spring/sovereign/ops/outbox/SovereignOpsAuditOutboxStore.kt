package dev.tramai.spring.sovereign.ops.outbox

import java.time.Instant

/**
 * Store for audit outbox records.
 *
 * Implementations must be thread-safe. The outbox store is not responsible
 * for atomicity with approval state changes — that is the responsibility of
 * [SovereignOpsApprovalMutationStore].
 */
interface SovereignOpsAuditOutboxStore {

    /**
     * Whether this store provides durable persistence (survives restarts).
     *
     * Returns `false` for in-memory implementations. Mutations that use a
     * non-durable outbox store fail closed to prevent audit intent loss
     * on process restart.
     */
    fun isDurable(): Boolean = false

    /**
     * Append a new outbox record.
     * @throws IllegalArgumentException if a record with the same [SovereignOpsAuditOutboxRecord.outboxId] exists.
     * @throws IllegalArgumentException if a record with the same [SovereignOpsAuditOutboxRecord.eventKey] exists.
     */
    suspend fun append(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord

    /**
     * Claim dispatchable records (PENDING, FAILED_RETRYABLE, or expired EMITTING).
     *
     * Transitions matching records to [SovereignOpsAuditOutboxStatus.EMITTING]
     * with [claimedBy] and [now] as the claim timestamp.
     * Records with [SovereignOpsAuditOutboxStatus.EMITTING] are only re-claimed
     * if their [SovereignOpsAuditOutboxRecord.claimExpiresAt] is before [now].
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

    /** Find an outbox record by its deterministic event key. */
    suspend fun findByEventKey(eventKey: String): SovereignOpsAuditOutboxRecord?

    /** List pending records (for diagnostics). */
    suspend fun listPending(limit: Int): List<SovereignOpsAuditOutboxRecord>
}
