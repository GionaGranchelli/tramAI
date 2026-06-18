package dev.tramai.spring.sovereign.ops.outbox

/**
 * Observer for sovereign ops audit outbox worker cycles and failures.
 *
 * Called by [SovereignOpsAuditOutboxWorkerLifecycle] after every
 * [SovereignOpsAuditOutboxBackgroundWorker.runOnce] cycle.
 *
 * ## Security invariants
 * Implementations must not log, persist, or transmit:
 * - raw approval IDs
 * - raw reason text
 * - approval tokens, resume tokens, replay envelopes
 * - prompts, model responses, tool arguments
 * - exception messages, file paths, or stack traces
 *
 * [SovereignOpsAuditOutboxWorkerRunSummary] already enforces these
 * constraints — [onCycleFailed] receives only a sanitized action
 * name and exception class name.
 */
interface SovereignOpsAuditOutboxWorkerObserver {

    /**
     * Called after a successful [runOnce] cycle completes (including cycles
     * where [SovereignOpsAuditOutboxWorkerRunSummary.failure] is non-null —
     * the summary carries the sanitized failure record).
     */
    fun onCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary)

    /**
     * Called when an unexpected exception escaped [runOnce] and was caught
     * by the lifecycle loop. [action] is "unexpected" and [errorCode] is
     * the exception class simple name.
     *
     * [CancellationException] is never sent here — it is always rethrown.
     */
    fun onCycleFailed(
        action: String,
        errorCode: String,
    )

    companion object {
        /** Default no-op observer. */
        val Noop: SovereignOpsAuditOutboxWorkerObserver = NoopSovereignOpsAuditOutboxWorkerObserver
    }
}

private object NoopSovereignOpsAuditOutboxWorkerObserver : SovereignOpsAuditOutboxWorkerObserver {
    override fun onCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary) = Unit
    override fun onCycleFailed(action: String, errorCode: String) = Unit
}
