package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueSnapshot
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueStatusStore
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Cached snapshot provider for queue status gauges.
 *
 * Refreshes the snapshot on read when the configured [refreshInterval] has
 * elapsed. On refresh failure the last known values are retained and a
 * [snapshotFailureCount] is incremented — no exception messages are exposed.
 *
 * Gauges are always registered and return `0` until the first successful
 * snapshot.
 */
class ApprovedResumeQueueMetricsSnapshotProvider(
    private val queueStatusStore: ApprovedContinuationResumeQueueStatusStore,
    private val clock: Clock = Clock.systemUTC(),
    private val refreshInterval: Duration = Duration.ofSeconds(10),
) {

    @Volatile
    private var lastRefresh: Instant = Instant.EPOCH

    private val cached = AtomicReference<ApprovedContinuationResumeQueueSnapshot>()

    val eligibleNow = AtomicLong(0)
    val delayedRetry = AtomicLong(0)
    val activeLeases = AtomicLong(0)
    val expiredLeases = AtomicLong(0)
    val terminalFailures = AtomicLong(0)
    val oldestEligibleAgeSeconds = AtomicReference<Long?>(null)
    val oldestRetryDueInSeconds = AtomicReference<Long?>(null)
    val snapshotFailureCount = AtomicLong(0)

    /**
     * Register all queue snapshot gauges on the given [registry].
     *
     * Gauges call the corresponding [AtomicLong] / [AtomicReference] values
     * which are updated on refresh. This avoids running blocking JDBC inside
     * the gauge function itself.
     */
    fun registerGauges(registry: MeterRegistry) {
        val prefix = "tramai.sovereign.approved_resume_queue"
        registry.gauge("$prefix.eligible_now", this) { it.refreshIfDue(); it.eligibleNow.get().toDouble() }
        registry.gauge("$prefix.delayed_retry", this) { it.refreshIfDue(); it.delayedRetry.get().toDouble() }
        registry.gauge("$prefix.active_leases", this) { it.refreshIfDue(); it.activeLeases.get().toDouble() }
        registry.gauge("$prefix.expired_leases", this) { it.refreshIfDue(); it.expiredLeases.get().toDouble() }
        registry.gauge("$prefix.terminal_failures", this) { it.refreshIfDue(); it.terminalFailures.get().toDouble() }
        registry.gauge("$prefix.snapshot_failures.total", this) {
            it.refreshIfDue(); it.snapshotFailureCount.get().toDouble()
        }
        registry.gauge("$prefix.oldest_eligible_age_seconds", this) {
            it.refreshIfDue(); it.oldestEligibleAgeSeconds.get()?.toDouble() ?: Double.NaN
        }
        registry.gauge("$prefix.oldest_retry_due_in_seconds", this) {
            it.refreshIfDue(); it.oldestRetryDueInSeconds.get()?.toDouble() ?: Double.NaN
        }
    }

    /**
     * Refresh the cached snapshot if the refresh interval has elapsed.
     *
     * Called on each metrics scrape. On failure, retains the last known values
     * and increments [snapshotFailureCount].
     */
    fun refreshIfDue() {
        val now = clock.instant()
        if (Duration.between(lastRefresh, now) >= refreshInterval) {
            synchronized(this) {
                if (Duration.between(lastRefresh, clock.instant()) >= refreshInterval) {
                    try {
                        val snapshot = runBlocking {
                            queueStatusStore.snapshot(now)
                        }
                        updateFrom(snapshot)
                    } catch (_: Exception) {
                        snapshotFailureCount.incrementAndGet()
                    } finally {
                        lastRefresh = clock.instant()
                    }
                }
            }
        }
    }

    private fun updateFrom(snapshot: ApprovedContinuationResumeQueueSnapshot) {
        cached.set(snapshot)
        eligibleNow.set(snapshot.eligibleNow)
        delayedRetry.set(snapshot.delayedRetry)
        activeLeases.set(snapshot.activeLeases)
        expiredLeases.set(snapshot.expiredLeases)
        terminalFailures.set(snapshot.terminalFailures)
        oldestEligibleAgeSeconds.set(snapshot.oldestEligibleAgeSeconds)
        oldestRetryDueInSeconds.set(snapshot.oldestRetryDueInSeconds)
    }
}
