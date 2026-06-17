package dev.tramai.spring.sovereign.ops.outbox

/**
 * Operational interface for inspecting and managing the audit outbox.
 *
 * Provides visibility into all outbox states, retry of dispatchable records,
 * and manual terminal marking for stuck PREPARED records.
 *
 * ## Security invariants
 * All returned summaries use safe DTOs that expose only digested identifiers
 * and sanitised error codes — never raw approval IDs, raw reason text,
 * approval tokens, resume tokens, replay envelopes, prompts, or tool args.
 *
 * ## Capabilities
 * - **Visibility**: List records by status or across all statuses
 * - **Retry**: Re-dispatch PENDING and FAILED_RETRYABLE records
 * - **Manual terminal marking**: Close orphan PREPARED records as FAILED_PERMANENT
 *
 * ## Non-goals (in this interface)
 * - Automatic PREPARED reconciliation (planned for a future PR)
 * - Background retry scheduling
 * - REST/Actuator endpoints
 */
interface SovereignOpsAuditOutboxOperations {

    /**
     * List outbox records, optionally filtered by [status].
     *
     * When [status] is null, returns records across all statuses in
     * enum order (PREPARED → PENDING → EMITTING → EMITTED →
     * FAILED_RETRYABLE → FAILED_PERMANENT), up to [limit].
     *
     * @param status Optional status filter. When null, returns a bounded
     *               sample across all statuses.
     * @param limit Max records to return. Defaults to 100, max 500.
     * @return Safe summaries (no raw sensitive data).
     * @throws IllegalArgumentException if [limit] is <= 0 or > 500.
     */
    suspend fun listOutboxRecords(
        status: SovereignOpsAuditOutboxStatus?,
        limit: Int?,
    ): List<SovereignOpsAuditOutboxSummary>

    /**
     * Retry dispatch of pending and retryable outbox records.
     *
     * Delegates to [SovereignOpsAuditOutboxDispatcher.dispatchPending],
     * which claims PENDING, FAILED_RETRYABLE, and expired EMITTING records
     * and emits audit events via [SovereignOpsAuditEmitter.approvalDeniedFromOutbox].
     *
     * @param limit Max records to process. Defaults to 100, max 500.
     * @return Summary of the dispatch run (claimed, emitted, failed counts).
     * @throws IllegalStateException if no dispatcher is available
     *         (tramai-sovereign-ops-audit-unavailable).
     * @throws kotlinx.coroutines.CancellationException if the coroutine is cancelled.
     */
    suspend fun retryPending(limit: Int?): SovereignOpsAuditOutboxDispatchResult

    /**
     * Mark a stuck PREPARED outbox record as FAILED_PERMANENT.
     *
     * Used for manual recovery when an operator has verified that the
     * associated approval transition did not complete, or the outbox
     * intent should be discarded.
     *
     * @param outboxId The PREPARED record to close.
     * @param reason A human-readable reason for the closure.
     * @return Summary of the updated record.
     * @throws IllegalStateException if mutations are disabled.
     * @throws IllegalArgumentException if [outboxId] is invalid.
     * @throws IllegalArgumentException if [reason] is blank or too long.
     * @throws IllegalStateException if the record is not in PREPARED status.
     */
    suspend fun markPreparedFailed(
        outboxId: String,
        reason: String,
    ): SovereignOpsAuditOutboxSummary
}
