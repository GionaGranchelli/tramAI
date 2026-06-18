package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.SovereignOpsOutboxWorkerProperties
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant

/**
 * Background worker that periodically recovers prepared outbox records
 * and dispatches pending audit outbox records.
 *
 * Exposes [runOnce] for deterministic testing. The scheduled loop
 * calls [runOnce], not the business logic directly.
 *
 * ## Security invariants
 * - No raw approval IDs, raw reason text, tokens, envelopes, prompts,
 *   or tool arguments are logged or exposed in summaries.
 * - [CancellationException] is never swallowed by this worker.
 * - Runtime failures do not kill the worker loop.
 */
class SovereignOpsAuditOutboxBackgroundWorker(
    private val operations: SovereignOpsAuditOutboxOperations,
    private val properties: SovereignOpsOutboxWorkerProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun runOnce(): SovereignOpsAuditOutboxWorkerRunSummary {
        val startedAt = clock.instant()
        var recovered: SovereignOpsAuditOutboxRecoverySummary? = null
        var dispatched: SovereignOpsAuditOutboxDispatchResult? = null
        var failure: SovereignOpsAuditOutboxWorkerFailureSummary? = null

        if (properties.recoverPrepared) {
            try {
                recovered = operations.recoverPrepared(properties.batchSize)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                failure = e.toFailureSummary(action = "recoverPrepared")
            }
        }

        if (failure == null && properties.dispatchPending) {
            try {
                dispatched = operations.retryPending(properties.batchSize)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                failure = e.toFailureSummary(action = "dispatchPending")
            }
        }

        return SovereignOpsAuditOutboxWorkerRunSummary(
            recovered = recovered,
            dispatched = dispatched,
            failure = failure,
            startedAt = startedAt,
            completedAt = clock.instant(),
        )
    }

    private fun RuntimeException.toFailureSummary(
        action: String,
    ): SovereignOpsAuditOutboxWorkerFailureSummary =
        SovereignOpsAuditOutboxWorkerFailureSummary(
            action = action,
            errorCode = this::class.simpleName ?: "RuntimeException",
        )
}

data class SovereignOpsAuditOutboxWorkerRunSummary(
    val recovered: SovereignOpsAuditOutboxRecoverySummary?,
    val dispatched: SovereignOpsAuditOutboxDispatchResult?,
    val failure: SovereignOpsAuditOutboxWorkerFailureSummary? = null,
    val startedAt: Instant,
    val completedAt: Instant,
)

data class SovereignOpsAuditOutboxWorkerFailureSummary(
    val action: String,
    val errorCode: String,
)
