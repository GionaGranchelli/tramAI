package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueSnapshot
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueStatusStore
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class ApprovedResumeQueueMetricsSnapshotProviderTest {

    private lateinit var meterRegistry: MeterRegistry
    private lateinit var fakeStore: FakeApprovedContinuationResumeQueueStatusStore
    private lateinit var provider: ApprovedResumeQueueMetricsSnapshotProvider
    private lateinit var fixedClock: Clock

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
        fakeStore = FakeApprovedContinuationResumeQueueStatusStore()
        fixedClock = Clock.fixed(
            Instant.parse("2026-06-01T12:00:00Z"),
            ZoneId.of("UTC"),
        )
        provider = ApprovedResumeQueueMetricsSnapshotProvider(
            queueStatusStore = fakeStore,
            clock = fixedClock,
            refreshInterval = Duration.ofSeconds(10),
        )
        provider.registerGauges(meterRegistry)
    }

    @Test
    fun `eligible gauge reports snapshot value`() {
        fakeStore.snapshotResult = emptySnapshot(eligibleNow = 5)
        provider.refreshIfDue()

        assertThat(meterRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.eligible_now"))
            .isEqualTo(5.0)
    }

    @Test
    fun `all gauges reflect snapshot values`() {
        fakeStore.snapshotResult = emptySnapshot(
            eligibleNow = 10, delayedRetry = 3, activeLeases = 2,
            expiredLeases = 1, terminalFailures = 4,
            oldestEligibleAgeSeconds = 120, oldestRetryDueInSeconds = 90,
        )
        provider.refreshIfDue()

        assertThat(meterRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.eligible_now"))
            .isEqualTo(10.0)
        assertThat(meterRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.delayed_retry"))
            .isEqualTo(3.0)
        assertThat(meterRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.active_leases"))
            .isEqualTo(2.0)
        assertThat(meterRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.expired_leases"))
            .isEqualTo(1.0)
        assertThat(meterRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.terminal_failures"))
            .isEqualTo(4.0)
        assertThat(meterRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.oldest_eligible_age_seconds"))
            .isEqualTo(120.0)
        assertThat(meterRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.oldest_retry_due_in_seconds"))
            .isEqualTo(90.0)
        assertThat(meterRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.snapshot_failures.total"))
            .isEqualTo(0.0)
    }

    @Test
    fun `gauges return zero or NaN before first refresh`() {
        assertThat(meterRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.eligible_now"))
            .isEqualTo(0.0)
        assertThat(meterRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.oldest_eligible_age_seconds"))
            .isNaN()
        assertThat(meterRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.oldest_retry_due_in_seconds"))
            .isNaN()
        assertThat(meterRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.snapshot_failures.total"))
            .isEqualTo(0.0)
    }

    @Test
    fun `snapshot failure count increments on error`() {
        val failingStore = FakeApprovedContinuationResumeQueueStatusStore(throwOnSnapshot = true)
        val failProvider = ApprovedResumeQueueMetricsSnapshotProvider(
            queueStatusStore = failingStore,
            clock = fixedClock,
            refreshInterval = Duration.ofSeconds(0), // no throttling
        )
        failProvider.refreshIfDue()
        failProvider.refreshIfDue()

        assertThat(failProvider.snapshotFailureCount.get()).isEqualTo(2)
    }

    @Test
    fun `last known values retained after refresh failure`() {
        val failStore = FakeApprovedContinuationResumeQueueStatusStore()
        val testProvider = ApprovedResumeQueueMetricsSnapshotProvider(
            queueStatusStore = failStore,
            refreshInterval = Duration.ofSeconds(0), // always refresh
        )

        // first call succeeds
        failStore.throwOnSnapshot = false
        failStore.snapshotResult = emptySnapshot(eligibleNow = 7, oldestEligibleAgeSeconds = 30)
        testProvider.refreshIfDue()
        assertThat(testProvider.eligibleNow.get()).isEqualTo(7)

        // second call fails
        failStore.throwOnSnapshot = true
        testProvider.refreshIfDue()

        // values retained
        assertThat(testProvider.eligibleNow.get()).isEqualTo(7)
        assertThat(testProvider.snapshotFailureCount.get()).isEqualTo(1)
    }

    @Test
    fun `gauge read triggers refresh`() {
        val freshRegistry = SimpleMeterRegistry()
        val freshProvider = ApprovedResumeQueueMetricsSnapshotProvider(
            queueStatusStore = fakeStore,
            clock = fixedClock,
            refreshInterval = Duration.ofSeconds(10),
        )
        fakeStore.snapshotResult = emptySnapshot(eligibleNow = 42)
        freshProvider.registerGauges(freshRegistry)

        // read gauge without calling refreshIfDue() explicitly
        val value = freshRegistry.gaugeValue("tramai.sovereign.approved_resume_queue.eligible_now")

        assertThat(value).isNotZero()
    }

    @Test
    fun `lastErrorCodeCounts not exported as gauge`() {
        fakeStore.snapshotResult = emptySnapshot(
            eligibleNow = 1,
            lastErrorCodeCounts = mapOf("Timeout" to 1),
        )
        provider.refreshIfDue()

        val relevantGauges = meterRegistry.meters.filter {
            it.id.name.contains("error_code", ignoreCase = true) ||
                it.id.name.contains("lasterrorcode", ignoreCase = true)
        }
        assertThat(relevantGauges).isEmpty()
    }
}

private class FakeApprovedContinuationResumeQueueStatusStore(
    var throwOnSnapshot: Boolean = false,
) : ApprovedContinuationResumeQueueStatusStore {

    var snapshotResult: ApprovedContinuationResumeQueueSnapshot = emptySnapshot()

    override suspend fun snapshot(now: Instant): ApprovedContinuationResumeQueueSnapshot {
        if (throwOnSnapshot) throw RuntimeException("snapshot failed")
        return snapshotResult
    }
}

private fun emptySnapshot(
    eligibleNow: Long = 0,
    delayedRetry: Long = 0,
    activeLeases: Long = 0,
    expiredLeases: Long = 0,
    terminalFailures: Long = 0,
    oldestEligibleAgeSeconds: Long? = null,
    oldestRetryDueInSeconds: Long? = null,
    lastErrorCodeCounts: Map<String, Long> = emptyMap(),
) = ApprovedContinuationResumeQueueSnapshot(
    eligibleNow = eligibleNow,
    delayedRetry = delayedRetry,
    activeLeases = activeLeases,
    expiredLeases = expiredLeases,
    terminalFailures = terminalFailures,
    oldestEligibleAgeSeconds = oldestEligibleAgeSeconds,
    oldestRetryDueInSeconds = oldestRetryDueInSeconds,
    lastErrorCodeCounts = lastErrorCodeCounts,
)

private fun MeterRegistry.gaugeValue(name: String): Double =
    find(name).gauge()?.value() ?: Double.NaN
