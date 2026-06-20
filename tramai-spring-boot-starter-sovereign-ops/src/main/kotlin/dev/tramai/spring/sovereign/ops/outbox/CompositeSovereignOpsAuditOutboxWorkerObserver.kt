package dev.tramai.spring.sovereign.ops.outbox

import java.util.concurrent.CancellationException

/**
 * Composite [SovereignOpsAuditOutboxWorkerObserver] that delegates to a
 * list of observer instances.
 *
 * Each observer is called in order. A [RuntimeException] from one delegate
 * does not prevent subsequent delegates from being notified.
 * [CancellationException] is always rethrown.
 */
class CompositeSovereignOpsAuditOutboxWorkerObserver(
    private val observers: List<SovereignOpsAuditOutboxWorkerObserver>,
) : SovereignOpsAuditOutboxWorkerObserver {

    override fun onCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary) {
        observers.forEach { observer ->
            try {
                observer.onCycleCompleted(summary)
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                // Isolate delegate failures. Do not log raw exception details.
            }
        }
    }

    override fun onCycleFailed(action: String, errorCode: String) {
        observers.forEach { observer ->
            try {
                observer.onCycleFailed(action, errorCode)
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                // Isolate delegate failures. Do not log raw exception details.
            }
        }
    }
}
