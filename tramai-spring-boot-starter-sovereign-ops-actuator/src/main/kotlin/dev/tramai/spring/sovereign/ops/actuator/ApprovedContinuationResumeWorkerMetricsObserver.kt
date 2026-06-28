package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkerObserver
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkerResult
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.time.Duration

/**
 * [ApprovedContinuationResumeWorkerObserver] that records safe, low-cardinality
 * Micrometer metrics for resume worker cycles.
 *
 * Metrics (all disabled by default):
 * - [CYCLES_TOTAL]: counter tagged with `outcome=completed|failed`
 * - [ITEMS_SCANNED_TOTAL]: total items scanned per cycle
 * - [ITEMS_RESUMED_TOTAL]: total items successfully resumed
 * - [ITEMS_SKIPPED_TOTAL]: total items skipped
 * - [ITEMS_FAILED_TOTAL]: total items that failed to resume
 * - [CYCLE_DURATION]: timer recording cycle duration
 * - [FAILURES_TOTAL]: counter tagged with `error_code` (class simple name only)
 *
 * No approval IDs, workflow run IDs, resume tokens, or raw exception messages
 * are exported as metric tags or values.
 *
 * @param meterRegistry the Micrometer registry to register metrics on.
 * @param properties metrics configuration including optional worker ID tag.
 */
class ApprovedContinuationResumeWorkerMetricsObserver(
    private val meterRegistry: MeterRegistry,
    private val properties: ApprovedContinuationResumeWorkerMetricsProperties,
) : ApprovedContinuationResumeWorkerObserver {

    companion object {
        const val METRICS_PREFIX = "tramai.sovereign.approved_resume_worker"
        const val CYCLES_TOTAL = "${METRICS_PREFIX}.cycles.total"
        const val ITEMS_SCANNED_TOTAL = "${METRICS_PREFIX}.items.scanned.total"
        const val ITEMS_RESUMED_TOTAL = "${METRICS_PREFIX}.items.resumed.total"
        const val ITEMS_SKIPPED_TOTAL = "${METRICS_PREFIX}.items.skipped.total"
        const val ITEMS_FAILED_TOTAL = "${METRICS_PREFIX}.items.failed.total"
        const val CYCLE_DURATION = "${METRICS_PREFIX}.cycle.duration"
        const val FAILURES_TOTAL = "${METRICS_PREFIX}.failures.total"
    }

    private val itemsScannedTotal: Counter = Counter.builder(ITEMS_SCANNED_TOTAL)
        .description("Total items scanned by resume worker")
        .register(meterRegistry)

    private val itemsResumedTotal: Counter = Counter.builder(ITEMS_RESUMED_TOTAL)
        .description("Total items resumed successfully")
        .register(meterRegistry)

    private val itemsSkippedTotal: Counter = Counter.builder(ITEMS_SKIPPED_TOTAL)
        .description("Total items skipped during resume cycles")
        .register(meterRegistry)

    private val itemsFailedTotal: Counter = Counter.builder(ITEMS_FAILED_TOTAL)
        .description("Total items that failed to resume")
        .register(meterRegistry)

    private val cycleDuration: Timer = Timer.builder(CYCLE_DURATION)
        .description("Duration of resume worker cycles")
        .publishPercentileHistogram()
        .register(meterRegistry)

    override fun cycleStarted(workerId: String) = Unit

    override fun cycleCompleted(
        workerId: String,
        result: ApprovedContinuationResumeWorkerResult,
        duration: Duration,
    ) {
        meterRegistry.counter(CYCLES_TOTAL, Tags.of("outcome", "completed")).increment()

        itemsScannedTotal.increment(result.scanned.toDouble())
        itemsResumedTotal.increment(result.resumed.toDouble())
        itemsSkippedTotal.increment(result.skipped.toDouble())
        itemsFailedTotal.increment(result.failed.toDouble())

        cycleDuration.record(duration)
    }

    override fun cycleFailed(workerId: String, error: Throwable) {
        meterRegistry.counter(CYCLES_TOTAL, Tags.of("outcome", "failed")).increment()

        // error_code = simple class name only, never the message
        val errorCode = error::class.simpleName ?: "Unknown"
        meterRegistry.counter(
            FAILURES_TOTAL,
            Tags.of("error_code", errorCode),
        ).increment()
    }
}
