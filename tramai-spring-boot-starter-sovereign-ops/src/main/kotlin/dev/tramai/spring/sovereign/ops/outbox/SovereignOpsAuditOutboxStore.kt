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
     * Append a new outbox record in [SovereignOpsAuditOutboxStatus.PREPARED] state.
     *
     * PREPARED records are NOT dispatchable — they must be moved to
     * [SovereignOpsAuditOutboxStatus.PENDING] via [markReadyForDispatch]
     * after the business mutation commits.
     *
     * @throws IllegalArgumentException if a record with the same [SovereignOpsAuditOutboxRecord.outboxId] exists.
     * @throws IllegalArgumentException if a record with the same [SovereignOpsAuditOutboxRecord.eventKey] exists.
     * @throws IllegalArgumentException if the record status is not PREPARED.
     */
    suspend fun append(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord

    /**
     * Move a PREPARED record to PENDING, making it eligible for dispatch.
     *
     * Called after the business mutation commits successfully.
     *
     * @param outboxId The record to mark ready.
     * @param expectedStatus Must be [SovereignOpsAuditOutboxStatus.PREPARED].
     * @throws IllegalStateException if record not found.
     * @throws IllegalArgumentException if status doesn't match.
     * @throws IllegalStateException if CAS update fails (concurrent modification).
     */
    suspend fun markReadyForDispatch(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
    ): SovereignOpsAuditOutboxRecord

    /**
     * Claim dispatchable records (PENDING, FAILED_RETRYABLE, or expired EMITTING).
     *
     * Transitions matching records to [SovereignOpsAuditOutboxStatus.EMITTING]
     * with [claimedBy] and [now] as the claim timestamp.
     * Records with [SovereignOpsAuditOutboxStatus.EMITTING] are only re-claimed
     * if their [SovereignOpsAuditOutboxRecord.claimExpiresAt] is before [now].
     *
     * PREPARED records are never dispatchable.
     */
    suspend fun claimPending(
        claimedBy: String,
        limit: Int,
        now: Instant,
    ): List<SovereignOpsAuditOutboxRecord>

    /**
     * Mark a record as successfully emitted.
     *
     * Uses status and [SovereignOpsAuditOutboxRecord.attemptCount] as an
     * optimistic dispatch-generation fence to prevent stale claim owners
     * from completing a successor attempt.
     * @throws IllegalStateException if CAS update fails (concurrent modification).
     */
    suspend fun markEmitted(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        expectedAttemptCount: Int,
        emittedAt: Instant,
    ): SovereignOpsAuditOutboxRecord

    /**
     * Mark a record as failed.
     *
     * Uses status and [SovereignOpsAuditOutboxRecord.attemptCount] as an
     * optimistic dispatch-generation fence. PREPARED failures use generation
     * zero.
     * @throws IllegalStateException if CAS update fails (concurrent modification).
     */
    suspend fun markFailed(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        expectedAttemptCount: Int,
        errorCode: String,
        retryable: Boolean,
    ): SovereignOpsAuditOutboxRecord

    /** Retrieve an outbox record by ID. */
    suspend fun get(outboxId: String): SovereignOpsAuditOutboxRecord?

    /** Find an outbox record by its deterministic event key. */
    suspend fun findByEventKey(eventKey: String): SovereignOpsAuditOutboxRecord?

    /** List pending records (for diagnostics). */
    suspend fun listPending(limit: Int): List<SovereignOpsAuditOutboxRecord>

    /** List records by exact status (for diagnostics and recovery). */
    suspend fun listByStatus(
        status: SovereignOpsAuditOutboxStatus,
        limit: Int,
    ): List<SovereignOpsAuditOutboxRecord>

    /** List EMITTING records whose claim has expired. */
    suspend fun listExpiredEmitting(
        now: Instant,
        limit: Int,
    ): List<SovereignOpsAuditOutboxRecord>
}
