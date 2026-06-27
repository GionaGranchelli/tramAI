package dev.tramai.spring.sovereign.ops

import java.time.Duration

/**
 * [ApprovedContinuationResumeWorkerObserver] that records cycle summaries
 * into an [ApprovedContinuationResumeWorkerStatusStore].
 *
 * Optionally delegates cycle callbacks to an additional observer, enabling
 * composition with custom observers.
 */
class RecordingApprovedContinuationResumeWorkerObserver(
    private val statusStore: ApprovedContinuationResumeWorkerStatusStore,
    private val delegate: ApprovedContinuationResumeWorkerObserver = ApprovedContinuationResumeWorkerObserver.Noop,
) : ApprovedContinuationResumeWorkerObserver {

    override fun cycleStarted(workerId: String) {
        delegate.cycleStarted(workerId)
    }

    override fun cycleCompleted(
        workerId: String,
        result: ApprovedContinuationResumeWorkerResult,
        duration: Duration,
    ) {
        statusStore.recordCycleCompleted(workerId, result, duration)
        delegate.cycleCompleted(workerId, result, duration)
    }

    override fun cycleFailed(workerId: String, error: Throwable) {
        statusStore.recordCycleFailed(workerId, error)
        delegate.cycleFailed(workerId, error)
    }
}
