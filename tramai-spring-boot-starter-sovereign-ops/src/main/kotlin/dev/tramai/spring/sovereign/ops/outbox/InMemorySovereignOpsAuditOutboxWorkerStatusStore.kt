package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.SovereignOpsOutboxWorkerProperties
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe in-memory implementation of [SovereignOpsAuditOutboxWorkerStatusStore].
 *
 * Does NOT store:
 * - raw exception messages
 * - file paths
 * - stack traces
 * - raw outbox records
 */
class InMemorySovereignOpsAuditOutboxWorkerStatusStore(
    private val properties: SovereignOpsOutboxWorkerProperties,
) : SovereignOpsAuditOutboxWorkerStatusStore {

    @Volatile
    private var lifecycleRunning = false

    @Volatile
    private var lastCycleStartedAt: Instant? = null

    @Volatile
    private var lastCycleCompletedAt: Instant? = null

    @Volatile
    private var lastRecovered: SovereignOpsAuditOutboxRecoverySummary? = null

    @Volatile
    private var lastDispatched: SovereignOpsAuditOutboxDispatchResult? = null

    @Volatile
    private var lastFailure: SovereignOpsAuditOutboxWorkerFailureSummary? = null

    private val totalCyclesCompleted = AtomicLong(0)
    private val totalCyclesFailed = AtomicLong(0)

    override fun snapshot(): SovereignOpsAuditOutboxWorkerStatusSnapshot {
        val started = lastCycleStartedAt
        val completed = lastCycleCompletedAt
        val duration = if (started != null && completed != null) {
            Duration.between(started, completed).toMillis()
        } else {
            null
        }

        return SovereignOpsAuditOutboxWorkerStatusSnapshot(
            enabled = properties.enabled,
            running = lifecycleRunning,
            recoverPreparedEnabled = properties.recoverPrepared,
            dispatchPendingEnabled = properties.dispatchPending,
            batchSize = properties.batchSize,
            intervalMillis = properties.interval.toMillis(),
            initialDelayMillis = properties.initialDelay.toMillis(),
            lastCycleStartedAt = started,
            lastCycleCompletedAt = completed,
            lastCycleDurationMillis = duration,
            lastRecovered = lastRecovered,
            lastDispatched = lastDispatched,
            lastFailure = lastFailure,
            totalCyclesCompleted = totalCyclesCompleted.get(),
            totalCyclesFailed = totalCyclesFailed.get(),
        )
    }

    override fun markLifecycleStarted() {
        lifecycleRunning = true
    }

    override fun markLifecycleStopped() {
        lifecycleRunning = false
    }

    override fun recordCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary) {
        lastCycleStartedAt = summary.startedAt
        lastCycleCompletedAt = summary.completedAt
        lastRecovered = summary.recovered
        lastDispatched = summary.dispatched
        lastFailure = summary.failure
        totalCyclesCompleted.incrementAndGet()
    }

    override fun recordCycleFailed(action: String, errorCode: String) {
        lastFailure = SovereignOpsAuditOutboxWorkerFailureSummary(
            action = action,
            errorCode = errorCode,
        )
        totalCyclesFailed.incrementAndGet()
    }
}
