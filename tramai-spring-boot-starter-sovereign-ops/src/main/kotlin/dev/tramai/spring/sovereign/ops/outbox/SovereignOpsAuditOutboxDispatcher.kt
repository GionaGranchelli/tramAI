package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.SovereignOpsAuditEmitter
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant

/**
 * Dispatches pending audit outbox records to the [SovereignOpsAuditEmitter].
 *
 * Behavior:
 * 1. Claims up to [limit] pending records.
 * 2. For each claimed record, emits the audit event.
 * 3. On success: marks the record as [SovereignOpsAuditOutboxStatus.EMITTED].
 * 4. On [RuntimeException] (including emission failure): marks as
 *    [SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE].
 * 5. On [CancellationException]: rethrows without marking.
 *
 * @param outboxStore The outbox store to claim and update records.
 * @param auditEmitter The audit emitter for actual emission.
 * @param claimedBy Identity used when claiming pending records.
 * @param clock Clock for timestamps.
 */
class SovereignOpsAuditOutboxDispatcher(
    private val outboxStore: SovereignOpsAuditOutboxStore,
    private val auditEmitter: SovereignOpsAuditEmitter,
    private val claimedBy: String = "sovereign-ops-dispatcher",
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Dispatch pending outbox records.
     *
     * @param limit Maximum number of records to process.
     * @return Summary of the dispatch run.
     */
    suspend fun dispatchPending(limit: Int): SovereignOpsAuditOutboxDispatchResult {
        val now = clock.instant()

        val claimed = outboxStore.claimPending(
            claimedBy = claimedBy,
            limit = limit,
            now = now,
        )

        var emitted = 0
        var failedRetryable = 0
        var failedPermanent = 0

        for (record in claimed) {
            try {
                auditEmitter.approvalDenied(
                    approvalId = record.aggregateIdDigest,  // safe: only digest
                    actor = record.actor,
                    reason = "",  // never re-emit raw reason
                    approvalStatus = record.approvalStatus,
                    approvalVersion = record.approvalVersion,
                    workflowRunId = record.workflowRunId,
                    correlationId = record.correlationId,
                )
                outboxStore.markEmitted(
                    outboxId = record.outboxId,
                    expectedStatus = SovereignOpsAuditOutboxStatus.EMITTING,
                    emittedAt = clock.instant(),
                )
                emitted++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                outboxStore.markFailed(
                    outboxId = record.outboxId,
                    expectedStatus = SovereignOpsAuditOutboxStatus.EMITTING,
                    errorCode = e::class.simpleName ?: "unknown",
                    retryable = true,
                )
                failedRetryable++
            }
        }

        return SovereignOpsAuditOutboxDispatchResult(
            claimed = claimed.size,
            emitted = emitted,
            failedRetryable = failedRetryable,
            failedPermanent = failedPermanent,
        )
    }
}

/**
 * Summary of a dispatch run.
 */
data class SovereignOpsAuditOutboxDispatchResult(
    val claimed: Int,
    val emitted: Int,
    val failedRetryable: Int,
    val failedPermanent: Int,
)
