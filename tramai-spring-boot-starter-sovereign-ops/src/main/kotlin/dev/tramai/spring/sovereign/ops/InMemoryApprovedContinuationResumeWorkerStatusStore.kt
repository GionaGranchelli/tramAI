package dev.tramai.spring.sovereign.ops

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe in-memory implementation of [ApprovedContinuationResumeWorkerStatusStore].
 *
 * Does NOT store:
 * - approval IDs
 * - resume tokens
 * - raw metadata
 * - exception messages
 * - stack traces
 */
class InMemoryApprovedContinuationResumeWorkerStatusStore(
    private val properties: SovereignOpsApprovedResumeWorkerProperties,
) : ApprovedContinuationResumeWorkerStatusStore {

    @Volatile
    private var lifecycleRunning = false

    @Volatile
    private var lastCycleStartedAt: Instant? = null

    @Volatile
    private var lastCycleCompletedAt: Instant? = null

    @Volatile
    private var lastCycleDurationMillis: Long? = null

    @Volatile
    private var lastResult: ApprovedContinuationResumeWorkerResult? = null

    @Volatile
    private var lastFailureAt: Instant? = null

    @Volatile
    private var lastFailureErrorCode: String? = null

    private val totalCyclesCompleted = AtomicLong(0)
    private val totalCyclesFailed = AtomicLong(0)

    override fun snapshot(): ApprovedContinuationResumeWorkerStatusSnapshot =
        ApprovedContinuationResumeWorkerStatusSnapshot(
            enabled = properties.enabled,
            lifecycleEnabled = properties.lifecycleEnabled,
            running = lifecycleRunning,
            batchSize = properties.batchSize,
            intervalMillis = properties.interval.toMillis(),
            lastCycleStartedAt = lastCycleStartedAt,
            lastCycleCompletedAt = lastCycleCompletedAt,
            lastCycleDurationMillis = lastCycleDurationMillis,
            lastResult = lastResult,
            lastFailureAt = lastFailureAt,
            lastFailureErrorCode = lastFailureErrorCode,
            totalCyclesCompleted = totalCyclesCompleted.get(),
            totalCyclesFailed = totalCyclesFailed.get(),
        )

    override fun markLifecycleStarted() {
        lifecycleRunning = true
    }

    override fun markLifecycleStopped() {
        lifecycleRunning = false
    }

    override fun recordCycleCompleted(
        workerId: String,
        result: ApprovedContinuationResumeWorkerResult,
        duration: java.time.Duration,
    ) {
        val completedAt = Instant.now()
        lastCycleCompletedAt = completedAt
        lastCycleStartedAt = completedAt.minus(duration)
        lastCycleDurationMillis = duration.toMillis()
        lastResult = result
        totalCyclesCompleted.incrementAndGet()
    }

    override fun recordCycleFailed(workerId: String, error: Throwable) {
        lastFailureAt = Instant.now()
        lastFailureErrorCode = error::class.simpleName ?: "Exception"
        totalCyclesFailed.incrementAndGet()
    }

    override fun recordCycleStartedAt(startedAt: Instant) {
        lastCycleStartedAt = startedAt
    }
}
