package dev.tramai.spring.sovereign.ops

import java.time.Duration
import java.util.concurrent.CancellationException

/**
 * Composite [ApprovedContinuationResumeWorkerObserver] that delegates to a
 * list of observer instances.
 *
 * Each observer is called in order. An [Exception] from one delegate
 * does not prevent subsequent delegates from being notified.
 * [CancellationException] is always rethrown.
 */
class CompositeApprovedContinuationResumeWorkerObserver(
    private val observers: List<ApprovedContinuationResumeWorkerObserver>,
) : ApprovedContinuationResumeWorkerObserver {

    override fun cycleStarted(workerId: String) {
        observers.forEach { observer ->
            try {
                observer.cycleStarted(workerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Isolate delegate failures. Do not log raw exception details.
            }
        }
    }

    override fun cycleCompleted(
        workerId: String,
        result: ApprovedContinuationResumeWorkerResult,
        duration: Duration,
    ) {
        observers.forEach { observer ->
            try {
                observer.cycleCompleted(workerId, result, duration)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Isolate delegate failures. Do not log raw exception details.
            }
        }
    }

    override fun cycleFailed(workerId: String, error: Throwable) {
        observers.forEach { observer ->
            try {
                observer.cycleFailed(workerId, error)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Isolate delegate failures. Do not log raw exception details.
            }
        }
    }
}
