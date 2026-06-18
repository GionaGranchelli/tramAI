package dev.tramai.spring.sovereign.ops.observability

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatchResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecoverySummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerFailureSummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerRunSummary
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

class OpenTelemetrySovereignOpsAuditOutboxWorkerObserverTest {

    private val metricReader = InMemoryMetricReader.create()
    private val meterProvider = SdkMeterProvider.builder()
        .registerMetricReader(metricReader)
        .build()
    private val otel = OpenTelemetrySdk.builder()
        .setMeterProvider(meterProvider)
        .build()
    private val observer = OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(otel)

    private val fixedTime = Instant.parse("2026-06-18T10:00:00Z")
    private val laterTime = Instant.parse("2026-06-18T10:00:01Z")

    @AfterEach
    fun tearDown() {
        meterProvider.shutdown()
    }

    // ── Attribute keys ──────────────────────────────────────────────

    private val KEY_WORKER_OUTCOME = AttributeKey.stringKey("tramai.sovereign.ops.outbox.worker.outcome")
    private val KEY_WORKER_FAILURE_ACTION = AttributeKey.stringKey("tramai.sovereign.ops.outbox.worker.failure_action")
    private val KEY_WORKER_ERROR_TYPE = AttributeKey.stringKey("tramai.sovereign.ops.outbox.worker.error_type")
    private val KEY_FAILURE_ACTION = AttributeKey.stringKey("tramai.sovereign.ops.outbox.worker.failure_action")
    private val KEY_FAILURE_ERROR_TYPE = AttributeKey.stringKey("tramai.sovereign.ops.outbox.worker.error_type")
    private val KEY_RECOVERY_RESULT = AttributeKey.stringKey("tramai.sovereign.ops.outbox.recovery.result")
    private val KEY_DISPATCH_RESULT = AttributeKey.stringKey("tramai.sovereign.ops.outbox.dispatch.result")

    // ── Helpers ─────────────────────────────────────────────────────

    private fun metrics() = metricReader.collectAllMetrics()

    private fun cyclePoints() =
        metrics().find { it.name == "tramai.sovereign.ops.outbox.worker.cycles" }
            ?.longSumData?.points?.filterNotNull() ?: emptyList()

    private fun failurePoints() =
        metrics().find { it.name == "tramai.sovereign.ops.outbox.worker.failures" }
            ?.longSumData?.points?.filterNotNull() ?: emptyList()

    private fun recoveryPoints() =
        metrics().find { it.name == "tramai.sovereign.ops.outbox.worker.recovered.records" }
            ?.longSumData?.points?.filterNotNull() ?: emptyList()

    private fun dispatchPoints() =
        metrics().find { it.name == "tramai.sovereign.ops.outbox.worker.dispatched.records" }
            ?.longSumData?.points?.filterNotNull() ?: emptyList()

    // ── Tests ───────────────────────────────────────────────────────

    @Test
    fun `successful cycle emits correct worker attributes`() {
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            startedAt = fixedTime,
            completedAt = laterTime,
            recovered = null,
            dispatched = null,
            failure = null,
        )

        observer.onCycleCompleted(summary)

        val points = cyclePoints()
        assertThat(points).hasSize(1)
        val attrs = points[0].attributes
        assertThat(attrs.get(KEY_WORKER_OUTCOME)).isEqualTo("success")
        assertThat(attrs.get(KEY_WORKER_FAILURE_ACTION)).isEqualTo("none")
        assertThat(attrs.get(KEY_WORKER_ERROR_TYPE)).isEqualTo("none")
        assertThat(points[0].value).isEqualTo(1)
    }

    @Test
    fun `failed cycle emits failure attributes`() {
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            startedAt = fixedTime,
            completedAt = laterTime,
            recovered = null,
            dispatched = null,
            failure = SovereignOpsAuditOutboxWorkerFailureSummary("recoverPrepared", "RuntimeException"),
        )

        observer.onCycleCompleted(summary)

        val points = cyclePoints()
        assertThat(points).hasSize(1)
        val attrs = points[0].attributes
        assertThat(attrs.get(KEY_WORKER_OUTCOME)).isEqualTo("failure")
        assertThat(attrs.get(KEY_WORKER_FAILURE_ACTION)).isEqualTo("recoverPrepared")
        assertThat(attrs.get(KEY_WORKER_ERROR_TYPE)).isEqualTo("RuntimeException")
    }

    @Test
    fun `duration is recorded correctly`() {
        val t1 = Instant.parse("2026-06-18T10:00:00Z")
        val t2 = Instant.parse("2026-06-18T10:00:00.500Z")
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            startedAt = t1,
            completedAt = t2,
            recovered = null,
            dispatched = null,
            failure = null,
        )

        observer.onCycleCompleted(summary)

        val durationMetric = metrics().find { it.name == "tramai.sovereign.ops.outbox.worker.duration" }
        assertThat(durationMetric).isNotNull
        val point = durationMetric!!.histogramData.points.single()
        assertThat(point.sum).isEqualTo(500.0)
    }

    @Test
    fun `negative duration is clamped to zero`() {
        val t1 = Instant.parse("2026-06-18T10:00:01Z")
        val t2 = Instant.parse("2026-06-18T10:00:00Z")
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            startedAt = t1,
            completedAt = t2,
            recovered = null,
            dispatched = null,
            failure = null,
        )

        observer.onCycleCompleted(summary)

        val durationMetric = metrics().find { it.name == "tramai.sovereign.ops.outbox.worker.duration" }
        val point = durationMetric!!.histogramData.points.single()
        assertThat(point.sum).isEqualTo(0.0)
    }

    @Test
    fun `recovery counters use recovery_result attribute`() {
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            startedAt = fixedTime,
            completedAt = laterTime,
            recovered = SovereignOpsAuditOutboxRecoverySummary(
                inspected = 10,
                movedToPending = 3,
                markedFailedPermanent = 2,
                resolverFailures = 1,
            ),
            dispatched = null,
            failure = null,
        )

        observer.onCycleCompleted(summary)

        val points = recoveryPoints()
        assertThat(points).hasSize(4)

        val inspectedPt = points.find { it.attributes.get(KEY_RECOVERY_RESULT) == "inspected" }
        assertThat(inspectedPt).isNotNull
        assertThat(inspectedPt!!.value).isEqualTo(10)

        val movedPt = points.find { it.attributes.get(KEY_RECOVERY_RESULT) == "moved_to_pending" }
        assertThat(movedPt).isNotNull
        assertThat(movedPt!!.value).isEqualTo(3)

        val failedPt = points.find { it.attributes.get(KEY_RECOVERY_RESULT) == "failed_permanent" }
        assertThat(failedPt).isNotNull
        assertThat(failedPt!!.value).isEqualTo(2)

        val resolverPt = points.find { it.attributes.get(KEY_RECOVERY_RESULT) == "resolver_failure" }
        assertThat(resolverPt).isNotNull
        assertThat(resolverPt!!.value).isEqualTo(1)
    }

    @Test
    fun `dispatch counters use dispatch_result attribute`() {
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            startedAt = fixedTime,
            completedAt = laterTime,
            recovered = null,
            dispatched = SovereignOpsAuditOutboxDispatchResult(
                claimed = 50,
                emitted = 45,
                failedRetryable = 3,
                failedPermanent = 2,
            ),
            failure = null,
        )

        observer.onCycleCompleted(summary)

        val points = dispatchPoints()
        assertThat(points).hasSize(4)

        val claimedPt = points.find { it.attributes.get(KEY_DISPATCH_RESULT) == "claimed" }
        assertThat(claimedPt).isNotNull
        assertThat(claimedPt!!.value).isEqualTo(50)

        val emittedPt = points.find { it.attributes.get(KEY_DISPATCH_RESULT) == "emitted" }
        assertThat(emittedPt).isNotNull
        assertThat(emittedPt!!.value).isEqualTo(45)

        val retryablePt = points.find { it.attributes.get(KEY_DISPATCH_RESULT) == "failed_retryable" }
        assertThat(retryablePt).isNotNull
        assertThat(retryablePt!!.value).isEqualTo(3)

        val permanentPt = points.find { it.attributes.get(KEY_DISPATCH_RESULT) == "failed_permanent" }
        assertThat(permanentPt).isNotNull
        assertThat(permanentPt!!.value).isEqualTo(2)
    }

    @Test
    fun `failure metric uses correct attribute keys`() {
        observer.onCycleFailed("unexpected", "IllegalStateException")

        val points = failurePoints()
        assertThat(points).hasSize(1)
        assertThat(points[0].attributes.get(KEY_FAILURE_ACTION)).isEqualTo("unexpected")
        assertThat(points[0].attributes.get(KEY_FAILURE_ERROR_TYPE)).isEqualTo("IllegalStateException")
    }

    @Test
    fun `P1 fix - summary failure plus onCycleFailed does not double-count cycles`() {
        // Simulate lifecycle: a summary with failure is passed to onCycleCompleted,
        // AND the lifecycle also calls onCycleFailed for that same failure.
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            startedAt = fixedTime,
            completedAt = laterTime,
            recovered = SovereignOpsAuditOutboxRecoverySummary(10, 0, 0, 0),
            dispatched = null,
            failure = SovereignOpsAuditOutboxWorkerFailureSummary("recoverPrepared", "IllegalStateException"),
        )

        observer.onCycleCompleted(summary)
        observer.onCycleFailed("recoverPrepared", "IllegalStateException")

        // worker.cycles should be exactly 1
        val cyclesTotal = cyclePoints().sumOf { it.value }
        assertThat(cyclesTotal).isEqualTo(1L)

        // worker.failures should also be exactly 1
        val failuresTotal = failurePoints().sumOf { it.value }
        assertThat(failuresTotal).isEqualTo(1L)
    }

    @Test
    fun `sanitization - no sensitive strings in failure attributes`() {
        observer.onCycleFailed("unexpected", "IllegalStateException")

        val points = failurePoints()
        for (pt in points) {
            val attrs = pt.attributes
            attrs.forEach { key, value ->
                assertThat(value.toString()).doesNotContain(
                    "secret", "token", "password", "approval-123",
                    "sensitive@example.com", "/secret/path",
                )
            }
        }
    }

    @Test
    fun `sanitization - no sensitive strings in cycle attributes`() {
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            startedAt = fixedTime,
            completedAt = laterTime,
            recovered = null,
            dispatched = null,
            failure = null,
        )

        observer.onCycleCompleted(summary)

        val points = cyclePoints()
        for (pt in points) {
            val attrs = pt.attributes
            attrs.forEach { key, value ->
                assertThat(value.toString()).doesNotContain(
                    "secret", "token", "password", "approval-123",
                    "sensitive@example.com", "/secret/path",
                )
            }
        }
    }

    @Test
    fun `null recovery and dispatch are handled gracefully`() {
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            startedAt = fixedTime,
            completedAt = laterTime,
            recovered = null,
            dispatched = null,
            failure = null,
        )

        observer.onCycleCompleted(summary)

        // Should not throw, recovery/dispatch metrics should be empty
        assertThat(recoveryPoints()).isEmpty()
        assertThat(dispatchPoints()).isEmpty()
    }

    @Test
    fun `zero-value recovery records are not emitted`() {
        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            startedAt = fixedTime,
            completedAt = laterTime,
            recovered = SovereignOpsAuditOutboxRecoverySummary(
                inspected = 0,
                movedToPending = 5,
                markedFailedPermanent = 0,
                resolverFailures = 0,
            ),
            dispatched = null,
            failure = null,
        )

        observer.onCycleCompleted(summary)

        val points = recoveryPoints()
        assertThat(points).hasSize(1)
        assertThat(points[0].attributes.get(KEY_RECOVERY_RESULT)).isEqualTo("moved_to_pending")
        assertThat(points[0].value).isEqualTo(5)
    }
}
