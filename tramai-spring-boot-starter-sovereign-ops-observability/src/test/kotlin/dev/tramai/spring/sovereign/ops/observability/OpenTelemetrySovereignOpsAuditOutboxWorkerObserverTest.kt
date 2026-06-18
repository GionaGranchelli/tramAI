package dev.tramai.spring.sovereign.ops.observability

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatchResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecoverySummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerRunSummary
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test

class OpenTelemetrySovereignOpsAuditOutboxWorkerObserverTest {

    private val metricReader = InMemoryMetricReader.create()
    private val meterProvider = SdkMeterProvider.builder()
        .registerMetricReader(metricReader)
        .build()
    private val openTelemetry = OpenTelemetrySdk.builder()
        .setMeterProvider(meterProvider)
        .build()

    @AfterTest
    fun tearDown() {
        meterProvider.shutdown()
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun summary(
        recovered: SovereignOpsAuditOutboxRecoverySummary? = null,
        dispatched: SovereignOpsAuditOutboxDispatchResult? = null,
        startedAt: Instant = Instant.EPOCH,
        completedAt: Instant = Instant.EPOCH,
    ) = SovereignOpsAuditOutboxWorkerRunSummary(
        recovered = recovered,
        dispatched = dispatched,
        startedAt = startedAt,
        completedAt = completedAt,
    )

    private fun recovery(
        inspected: Int = 0,
        movedToPending: Int = 0,
        markedFailedPermanent: Int = 0,
        resolverFailures: Int = 0,
    ) = SovereignOpsAuditOutboxRecoverySummary(
        inspected = inspected,
        movedToPending = movedToPending,
        markedFailedPermanent = markedFailedPermanent,
        resolverFailures = resolverFailures,
    )

    private fun dispatch(
        claimed: Int = 0,
        emitted: Int = 0,
        failedRetryable: Int = 0,
        failedPermanent: Int = 0,
    ) = SovereignOpsAuditOutboxDispatchResult(
        claimed = claimed,
        emitted = emitted,
        failedRetryable = failedRetryable,
        failedPermanent = failedPermanent,
    )

    // ── Cycle counter tests ─────────────────────────────────────────

    @Test
    fun `cycle counter emits recoverPrepared action for recovery-only cycle`() {
        val observer = OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry)
        observer.onCycleCompleted(summary(recovered = recovery(inspected = 5, movedToPending = 3)))

        val metrics = metricReader.collectAllMetrics()
        val cycleMetric = metrics.single { it.name == "tramai.sovereign.ops.outbox.worker.cycles" }
        val recoveryPoint = cycleMetric.longSumData.points.single { it.attributes.get(AttributeKey.stringKey("action")) == "recoverPrepared" }
        assertThat(recoveryPoint.value).isEqualTo(1)
        assertThat(recoveryPoint.attributes.get(AttributeKey.stringKey("outcome"))).isEqualTo("success")
    }

    @Test
    fun `cycle counter emits dispatchPending action for dispatch-only cycle`() {
        val observer = OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry)
        observer.onCycleCompleted(summary(dispatched = dispatch(claimed = 10, emitted = 8)))

        val metrics = metricReader.collectAllMetrics()
        val cycleMetric = metrics.single { it.name == "tramai.sovereign.ops.outbox.worker.cycles" }
        val dispatchPoint = cycleMetric.longSumData.points.single { it.attributes.get(AttributeKey.stringKey("action")) == "dispatchPending" }
        assertThat(dispatchPoint.value).isEqualTo(1)
        assertThat(dispatchPoint.attributes.get(AttributeKey.stringKey("outcome"))).isEqualTo("success")
    }

    @Test
    fun `cycle counter emits both actions when both recovery and dispatch ran`() {
        val observer = OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry)
        observer.onCycleCompleted(summary(
            recovered = recovery(inspected = 2),
            dispatched = dispatch(claimed = 1, emitted = 1),
        ))

        val metrics = metricReader.collectAllMetrics()
        val cycleMetric = metrics.single { it.name == "tramai.sovereign.ops.outbox.worker.cycles" }
        val actions = cycleMetric.longSumData.points.map { it.attributes.get(AttributeKey.stringKey("action")) }
        assertThat(actions).containsExactlyInAnyOrder("recoverPrepared", "dispatchPending")
    }

    // ── Duration histogram test ─────────────────────────────────────

    @Test
    fun `duration histogram records elapsed time`() {
        val started = Instant.EPOCH
        val completed = started.plusMillis(1234)
        val observer = OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry)
        observer.onCycleCompleted(summary(startedAt = started, completedAt = completed))

        val metrics = metricReader.collectAllMetrics()
        val durationMetric = metrics.single { it.name == "tramai.sovereign.ops.outbox.worker.duration" }
        val points = durationMetric.histogramData.points
        assertThat(points).hasSize(1)
        // 1234 ms ± 1 ms (floating point tolerance)
        assertThat(points.single().sum / points.single().count).isCloseTo(1234.0, org.assertj.core.data.Offset.offset(1.0))
    }

    @Test
    fun `duration histogram clamps negative values to zero`() {
        val started = Instant.EPOCH
        val completed = started.minusMillis(500) // backwards — negative duration
        val observer = OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry)
        observer.onCycleCompleted(summary(startedAt = started, completedAt = completed))

        val metrics = metricReader.collectAllMetrics()
        val durationMetric = metrics.single { it.name == "tramai.sovereign.ops.outbox.worker.duration" }
        val points = durationMetric.histogramData.points
        assertThat(points.single().sum / points.single().count).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.1))
    }

    // ── Recovery record counter test ────────────────────────────────

    @Test
    fun `recovered records counter emits per-outcome counts`() {
        val observer = OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry)
        observer.onCycleCompleted(summary(recovered = recovery(
            inspected = 10,
            movedToPending = 7,
            markedFailedPermanent = 2,
            resolverFailures = 1,
        )))

        val metrics = metricReader.collectAllMetrics()
        val recMetric = metrics.single { it.name == "tramai.sovereign.ops.outbox.worker.recovered.records" }
        val points = recMetric.longSumData.points
        assertThat(points).hasSize(4)

        val byOutcome = points.associate { p ->
            p.attributes.get(AttributeKey.stringKey("outcome")) to p.value
        }
        assertThat(byOutcome).containsEntry("inspected", 10L)
        assertThat(byOutcome).containsEntry("movedToPending", 7L)
        assertThat(byOutcome).containsEntry("failedPermanent", 2L)
        assertThat(byOutcome).containsEntry("resolverFailures", 1L)
    }

    @Test
    fun `recovered records counter skips zero-count outcomes`() {
        val observer = OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry)
        observer.onCycleCompleted(summary(recovered = recovery(inspected = 1, movedToPending = 1)))

        val metrics = metricReader.collectAllMetrics()
        val recMetric = metrics.single { it.name == "tramai.sovereign.ops.outbox.worker.recovered.records" }
        val points = recMetric.longSumData.points
        val outcomes = points.map { it.attributes.get(AttributeKey.stringKey("outcome")) }
        assertThat(outcomes).containsExactlyInAnyOrder("inspected", "movedToPending")
    }

    // ── Dispatch record counter tests ───────────────────────────────

    @Test
    fun `dispatched records counter emits per-outcome counts`() {
        val observer = OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry)
        observer.onCycleCompleted(summary(dispatched = dispatch(
            claimed = 5,
            emitted = 4,
            failedRetryable = 1,
            failedPermanent = 0,
        )))

        val metrics = metricReader.collectAllMetrics()
        val dispMetric = metrics.single { it.name == "tramai.sovereign.ops.outbox.worker.dispatched.records" }
        val points = dispMetric.longSumData.points
        assertThat(points).hasSize(3)

        val byOutcome = points.associate { p ->
            p.attributes.get(AttributeKey.stringKey("outcome")) to p.value
        }
        assertThat(byOutcome).containsEntry("claimed", 5L)
        assertThat(byOutcome).containsEntry("emitted", 4L)
        assertThat(byOutcome).containsEntry("failedRetryable", 1L)
    }

    // ── Cycle failure tests ─────────────────────────────────────────

    @Test
    fun `onCycleFailed emits failure counter and cycle counter`() {
        val observer = OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry)
        observer.onCycleFailed(action = "unexpected", errorCode = "IllegalStateException")

        val metrics = metricReader.collectAllMetrics()

        // Failure counter
        val failureMetric = metrics.single { it.name == "tramai.sovereign.ops.outbox.worker.failures" }
        val failurePoint = failureMetric.longSumData.points.single { it.attributes.get(AttributeKey.stringKey("action")) == "unexpected" }
        assertThat(failurePoint.value).isEqualTo(1)
        assertThat(failurePoint.attributes.get(AttributeKey.stringKey("error_type"))).isEqualTo("IllegalStateException")

        // Cycle counter also reflects failure
        val cycleMetric = metrics.single { it.name == "tramai.sovereign.ops.outbox.worker.cycles" }
        val cyclePoint = cycleMetric.longSumData.points.single { it.attributes.get(AttributeKey.stringKey("action")) == "unexpected" }
        assertThat(cyclePoint.value).isEqualTo(1)
        assertThat(cyclePoint.attributes.get(AttributeKey.stringKey("outcome"))).isEqualTo("failure")
    }

    // ── Sanitization tests ──────────────────────────────────────────

    @Test
    fun `sanitization — no exception message appears in attributes`() {
        val observer = OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry)
        observer.onCycleFailed(action = "unexpected", errorCode = "RuntimeException")
        // errorCode is the class simple name, NOT the message — prove it

        val metrics = metricReader.collectAllMetrics()
        val failureMetric = metrics.single { it.name == "tramai.sovereign.ops.outbox.worker.failures" }
        val allAttributes = failureMetric.longSumData.points.flatMap { it.attributes.asMap().values }

        // No attribute value should contain raw exception message text
        val sensitivePatterns = listOf("connection", "stack", "trace", "secret", "password", "token")
        for (attr in allAttributes) {
            val attrStr = attr.toString().lowercase()
            for (pattern in sensitivePatterns) {
                assertThat(attrStr).doesNotContain(pattern)
            }
        }
    }

    @Test
    fun `sanitization — no sensitive data from run summary appears in attributes`() {
        val observer = OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry)
        // Run summary itself is already sanitized by the worker — the observer
        // just extracts counts. Prove no spill-through of any sensitive pattern.
        observer.onCycleCompleted(summary(
            recovered = recovery(inspected = 1),
            dispatched = dispatch(emitted = 1),
        ))

        val metrics = metricReader.collectAllMetrics()
        // Collect ALL attribute values across all metrics
        val allAttrs = metrics.flatMap { metric ->
            when {
                metric.longSumData.points.isNotEmpty() -> metric.longSumData.points.flatMap { it.attributes.asMap().values }
                metric.histogramData.points.isNotEmpty() -> metric.histogramData.points.flatMap { it.attributes.asMap().values }
                else -> emptyList()
            }
        }

        val sensitivePatterns = listOf(
            "/secret/path",
            "sensitive@example.com",
            "approval-123",
            "token",
            "raw reason",
            "denial-reason",
            "stack trace",
        )
        for (attr in allAttrs) {
            val attrStr = attr.toString().lowercase()
            for (pattern in sensitivePatterns) {
                assertThat(attrStr)
                    .`as`("Attribute '$attrStr' must not contain sensitive pattern '$pattern'")
                    .doesNotContain(pattern)
            }
        }
    }

    @Test
    fun `sanitization — attributes are low-cardinality constants only`() {
        val observer = OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry)
        observer.onCycleCompleted(summary(
            recovered = recovery(inspected = 3, movedToPending = 2),
            dispatched = dispatch(claimed = 1, emitted = 1),
        ))
        observer.onCycleFailed("unexpected", "NullPointerException")

        val metrics = metricReader.collectAllMetrics()
        val allAttrs = metrics.flatMap { metric ->
            when {
                metric.longSumData.points.isNotEmpty() -> metric.longSumData.points.flatMap { it.attributes.asMap().values }
                metric.histogramData.points.isNotEmpty() -> metric.histogramData.points.flatMap { it.attributes.asMap().values }
                else -> emptyList()
            }
        }.map { it.toString() }

        // All actions should be from the fixed set
        for (attr in allAttrs) {
            if (attr == "recoverPrepared" || attr == "dispatchPending" || attr == "unexpected") continue
            if (attr == "success" || attr == "failure") continue
            if (attr == "inspected" || attr == "movedToPending" || attr == "failedPermanent" || attr == "resolverFailures") continue
            if (attr == "claimed" || attr == "emitted" || attr == "failedRetryable") continue
            if (attr == "none" || attr == "NullPointerException") continue
            // numeric values are fine (the counter values)
            if (attr.toDoubleOrNull() != null) continue
            // Should have been covered by the above
            assertThat(attr).`as`("Unexpected attribute value: $attr").isIn(
                "recoverPrepared", "dispatchPending", "unexpected",
                "success", "failure",
                "inspected", "movedToPending", "failedPermanent", "resolverFailures",
                "claimed", "emitted", "failedRetryable",
                "none", "NullPointerException",
            )
        }
    }
}
