package dev.tramai.spring.sovereign.ops

import java.time.Duration

/**
 * Observer for [ApprovedContinuationResumeWorker] cycles.
 *
 * Called by [ApprovedContinuationResumeWorkerLifecycle] after every cycle.
 */
interface ApprovedContinuationResumeWorkerObserver {

    fun cycleStarted(workerId: String)

    fun cycleCompleted(
        workerId: String,
        result: ApprovedContinuationResumeWorkerResult,
        duration: Duration,
    )

    fun cycleFailed(workerId: String, error: Throwable)

    companion object {
        val Noop: ApprovedContinuationResumeWorkerObserver = NoopApprovedContinuationResumeWorkerObserver
    }
}

private object NoopApprovedContinuationResumeWorkerObserver : ApprovedContinuationResumeWorkerObserver {
    override fun cycleStarted(workerId: String) = Unit

    override fun cycleCompleted(
        workerId: String,
        result: ApprovedContinuationResumeWorkerResult,
        duration: Duration,
    ) = Unit

    override fun cycleFailed(workerId: String, error: Throwable) = Unit
}
