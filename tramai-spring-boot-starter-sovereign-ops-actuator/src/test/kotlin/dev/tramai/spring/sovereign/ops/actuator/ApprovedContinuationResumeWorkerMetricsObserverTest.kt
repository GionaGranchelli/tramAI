package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkerResult
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

class ApprovedContinuationResumeWorkerMetricsObserverTest {

    private lateinit var meterRegistry: MeterRegistry
    private lateinit var observer: ApprovedContinuationResumeWorkerMetricsObserver

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
        observer = ApprovedContinuationResumeWorkerMetricsObserver(
            meterRegistry = meterRegistry,
            properties = ApprovedContinuationResumeWorkerMetricsProperties(),
        )
    }

    @Test
    fun `completed cycle increments cycle counter with outcome completed`() {
        observer.cycleCompleted("worker-a", ApprovedContinuationResumeWorkerResult(5, 3, 1, 1), Duration.ofMillis(50))

        val counter = meterRegistry.counter(
            ApprovedContinuationResumeWorkerMetricsObserver.CYCLES_TOTAL,
            "outcome", "completed",
        )
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `failed cycle increments cycle counter with outcome failed`() {
        observer.cycleFailed("worker-a", IllegalStateException("something bad"))

        val counter = meterRegistry.counter(
            ApprovedContinuationResumeWorkerMetricsObserver.CYCLES_TOTAL,
            "outcome", "failed",
        )
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `failure counter uses class name only not message`() {
        observer.cycleFailed("worker-a", IllegalStateException("secret details"))

        val counter = meterRegistry.counter(
            ApprovedContinuationResumeWorkerMetricsObserver.FAILURES_TOTAL,
            "error_code", "IllegalStateException",
        )
        assertThat(counter.count()).isEqualTo(1.0)

        // no counter with the message text
        val allCounters = meterRegistry.find(
            ApprovedContinuationResumeWorkerMetricsObserver.FAILURES_TOTAL,
        ).counters()
        assertThat(allCounters).allMatch { c ->
            c.id.tags.all { it.value != "secret details" }
        }
    }

    @Test
    fun `result counters increment from worker result`() {
        observer.cycleCompleted("worker-a", ApprovedContinuationResumeWorkerResult(10, 5, 3, 2), Duration.ofMillis(100))

        assertThat(meterRegistry.counter(
            ApprovedContinuationResumeWorkerMetricsObserver.ITEMS_SCANNED_TOTAL,
        ).count()).isEqualTo(10.0)

        assertThat(meterRegistry.counter(
            ApprovedContinuationResumeWorkerMetricsObserver.ITEMS_RESUMED_TOTAL,
        ).count()).isEqualTo(5.0)

        assertThat(meterRegistry.counter(
            ApprovedContinuationResumeWorkerMetricsObserver.ITEMS_SKIPPED_TOTAL,
        ).count()).isEqualTo(3.0)

        assertThat(meterRegistry.counter(
            ApprovedContinuationResumeWorkerMetricsObserver.ITEMS_FAILED_TOTAL,
        ).count()).isEqualTo(2.0)
    }

    @Test
    fun `timer records cycle duration`() {
        observer.cycleCompleted("worker-a", ApprovedContinuationResumeWorkerResult(0, 0, 0, 0), Duration.ofMillis(150))

        val timer = meterRegistry.find(
            ApprovedContinuationResumeWorkerMetricsObserver.CYCLE_DURATION,
        ).timer()
        assertThat(timer).isNotNull
        assertThat(timer.count()).isEqualTo(1)
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isCloseTo(150.0, within(1.0))
    }

    @Test
    fun `no sensitive tags on any metric`() {
        observer.cycleCompleted("worker-a", ApprovedContinuationResumeWorkerResult(1, 1, 0, 0), Duration.ofMillis(5))
        observer.cycleFailed("worker-b", RuntimeException())

        val allMeters = meterRegistry.meters
        for (meter in allMeters) {
            for (tag in meter.id.tags) {
                assertThat(tag.key).doesNotContainIgnoringCase("approval")
                assertThat(tag.key).doesNotContainIgnoringCase("token")
                assertThat(tag.key).doesNotContainIgnoringCase("workflow")
                assertThat(tag.key).doesNotContainIgnoringCase("id")
                assertThat(tag.key).doesNotContainIgnoringCase("metadata")
            }
        }
    }

    @Test
    fun `completed and failed cycles use separate outcome tags`() {
        observer.cycleCompleted("worker-a", ApprovedContinuationResumeWorkerResult(1, 1, 0, 0), Duration.ofMillis(5))
        observer.cycleCompleted("worker-a", ApprovedContinuationResumeWorkerResult(1, 0, 1, 0), Duration.ofMillis(10))
        observer.cycleFailed("worker-a", RuntimeException())

        val completedCounter = meterRegistry.counter(
            ApprovedContinuationResumeWorkerMetricsObserver.CYCLES_TOTAL,
            "outcome", "completed",
        )
        val failedCounter = meterRegistry.counter(
            ApprovedContinuationResumeWorkerMetricsObserver.CYCLES_TOTAL,
            "outcome", "failed",
        )

        assertThat(completedCounter.count()).isEqualTo(2.0)
        assertThat(failedCounter.count()).isEqualTo(1.0)
    }

    @Test
    fun `cycle started does nothing`() {
        // should not throw
        observer.cycleStarted("worker-a")
    }
}

private fun within(value: Double) =
    org.assertj.core.data.Offset.offset(value)
