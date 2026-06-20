package dev.tramai.spring.sovereign.ops.outbox

/**
 * [SovereignOpsAuditOutboxWorkerObserver] that records cycle summaries
 * into a [SovereignOpsAuditOutboxWorkerStatusStore].
 *
 * Optionally delegates [onCycleCompleted] and [onCycleFailed] calls to an
 * additional observer, enabling composition with custom observers.
 *
 * ## Security invariants
 * This observer does not log, persist, or transmit:
 * - raw approval IDs
 * - raw reason text
 * - approval tokens, resume tokens, replay envelopes
 * - prompts, model responses, tool arguments
 * - exception messages, file paths, or stack traces
 */
class RecordingSovereignOpsAuditOutboxWorkerObserver(
    private val statusStore: SovereignOpsAuditOutboxWorkerStatusStore,
    private val delegate: SovereignOpsAuditOutboxWorkerObserver = SovereignOpsAuditOutboxWorkerObserver.Noop,
) : SovereignOpsAuditOutboxWorkerObserver {

    override fun onCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary) {
        statusStore.recordCycleCompleted(summary)
        delegate.onCycleCompleted(summary)
    }

    override fun onCycleFailed(action: String, errorCode: String) {
        statusStore.recordCycleFailed(action, errorCode)
        delegate.onCycleFailed(action, errorCode)
    }
}
