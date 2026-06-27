package dev.tramai.spring.sovereign.ops

import java.time.Duration
import java.time.Instant

interface ApprovedContinuationResumeWorkerStatusStore {
    fun snapshot(): ApprovedContinuationResumeWorkerStatusSnapshot

    fun markLifecycleStarted()
    fun markLifecycleStopped()

    /** Record when a cycle started, for duration computation. */
    fun recordCycleStartedAt(startedAt: Instant)

    fun recordCycleCompleted(
        workerId: String,
        result: ApprovedContinuationResumeWorkerResult,
        duration: Duration,
    )

    /** @param error only the class name (never the message) is stored. */
    fun recordCycleFailed(workerId: String, error: Throwable)
}
