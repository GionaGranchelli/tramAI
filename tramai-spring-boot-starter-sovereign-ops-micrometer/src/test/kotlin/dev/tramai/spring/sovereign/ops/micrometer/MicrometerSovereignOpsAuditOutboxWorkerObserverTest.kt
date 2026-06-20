package dev.tramai.spring.sovereign.ops.micrometer

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatchResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecoverySummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerFailureSummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerRunSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class MicrometerSovereignOpsAuditOutboxWorkerObserverTest {

    private val registry: MeterRegistry = SimpleMeterRegistry()
    private val observer = MicrometerSovereignOpsAuditOutboxWorkerObserver(registry)

    @AfterEach
    fun tearDown() {
        registry.close()
    }

    @Test
    fun `successful cycle records cycle counter and duration timer`() {
        val now = Instant.now()
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            recovered = null,
            dispatched = null,
            startedAt = now,
            completedAt = now.plus(Duration.ofMillis(500)),
        )

        observer.onCycleCompleted(summary)

        val cycleCounter = registry.get("tramai.sovereign.ops.outbox.worker.cycles")
            .tag("outcome", "success")
            .tag("failure_action", "none")
            .tag("error_type", "none")
            .counter()
        assertThat(cycleCounter.count()).isEqualTo(1.0)

        val durationTimer = registry.get("tramai.sovereign.ops.outbox.worker.duration")
            .tag("outcome", "success")
            .tag("failure_action", "none")
            .tag("error_type", "none")
            .timer()
        assertThat(durationTimer.count()).isEqualTo(1)
        assertThat(durationTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(500.0)
    }

    @Test
    fun `failed cycle records outcome failure with correct tags`() {
        val now = Instant.now()
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            recovered = null,
            dispatched = null,
            failure = SovereignOpsAuditOutboxWorkerFailureSummary(
                action = "dispatchPending",
                errorCode = "dispatch_timeout",
            ),
            startedAt = now,
            completedAt = now.plus(Duration.ofMillis(300)),
        )

        observer.onCycleCompleted(summary)

        val cycleCounter = registry.get("tramai.sovereign.ops.outbox.worker.cycles")
            .tag("outcome", "failure")
            .tag("failure_action", "dispatchPending")
            .tag("error_type", "dispatch_timeout")
            .counter()
        assertThat(cycleCounter.count()).isEqualTo(1.0)

        val durationTimer = registry.get("tramai.sovereign.ops.outbox.worker.duration")
            .tag("outcome", "failure")
            .tag("failure_action", "dispatchPending")
            .tag("error_type", "dispatch_timeout")
            .timer()
        assertThat(durationTimer.count()).isEqualTo(1)
        assertThat(durationTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(300.0)
    }

    @Test
    fun `negative duration is clamped to zero`() {
        val now = Instant.now()
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            recovered = null,
            dispatched = null,
            startedAt = now,
            completedAt = now.minus(Duration.ofMillis(250)),
        )

        observer.onCycleCompleted(summary)

        val durationTimer = registry.get("tramai.sovereign.ops.outbox.worker.duration")
            .tag("outcome", "success")
            .tag("failure_action", "none")
            .tag("error_type", "none")
            .timer()
        assertThat(durationTimer.count()).isEqualTo(1)
        assertThat(durationTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(0.0)
    }

    @Test
    fun `onCycleFailed records worker failure counter`() {
        observer.onCycleFailed("unexpected", "IllegalStateException")

        val failureCounter = registry.get("tramai.sovereign.ops.outbox.worker.failures")
            .tag("failure_action", "unexpected")
            .tag("error_type", "IllegalStateException")
            .counter()
        assertThat(failureCounter.count()).isEqualTo(1.0)
    }

    @Test
    fun `recovery summary records recovered record counters by result`() {
        val now = Instant.now()
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            recovered = SovereignOpsAuditOutboxRecoverySummary(
                inspected = 10,
                movedToPending = 3,
                markedFailedPermanent = 2,
                skippedUnresolved = 1,
                resolverFailures = 1,
            ),
            dispatched = null,
            startedAt = now,
            completedAt = now.plus(Duration.ofMillis(100)),
        )

        observer.onCycleCompleted(summary)

        val inspected = registry.get("tramai.sovereign.ops.outbox.worker.recovered.records")
            .tag("result", "inspected").counter()
        assertThat(inspected.count()).isEqualTo(10.0)

        val movedToPending = registry.get("tramai.sovereign.ops.outbox.worker.recovered.records")
            .tag("result", "moved_to_pending").counter()
        assertThat(movedToPending.count()).isEqualTo(3.0)

        val failedPermanent = registry.get("tramai.sovereign.ops.outbox.worker.recovered.records")
            .tag("result", "failed_permanent").counter()
        assertThat(failedPermanent.count()).isEqualTo(2.0)

        val resolverFailure = registry.get("tramai.sovereign.ops.outbox.worker.recovered.records")
            .tag("result", "resolver_failure").counter()
        assertThat(resolverFailure.count()).isEqualTo(1.0)
    }

    @Test
    fun `dispatch summary records dispatched record counters by result`() {
        val now = Instant.now()
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            recovered = null,
            dispatched = SovereignOpsAuditOutboxDispatchResult(
                claimed = 5,
                emitted = 4,
                failedRetryable = 1,
                failedPermanent = 0,
            ),
            startedAt = now,
            completedAt = now.plus(Duration.ofMillis(200)),
        )

        observer.onCycleCompleted(summary)

        val claimed = registry.get("tramai.sovereign.ops.outbox.worker.dispatched.records")
            .tag("result", "claimed").counter()
        assertThat(claimed.count()).isEqualTo(5.0)

        val emitted = registry.get("tramai.sovereign.ops.outbox.worker.dispatched.records")
            .tag("result", "emitted").counter()
        assertThat(emitted.count()).isEqualTo(4.0)

        val failedRetryable = registry.get("tramai.sovereign.ops.outbox.worker.dispatched.records")
            .tag("result", "failed_retryable").counter()
        assertThat(failedRetryable.count()).isEqualTo(1.0)

        val failedPermanent = registry.find("tramai.sovereign.ops.outbox.worker.dispatched.records")
            .tag("result", "failed_permanent")
            .counter()
        assertThat(failedPermanent).isNull()
    }
}
