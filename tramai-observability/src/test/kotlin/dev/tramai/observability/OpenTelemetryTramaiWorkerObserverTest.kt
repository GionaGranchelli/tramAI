package dev.tramai.observability

import dev.tramai.orchestration.TramaiWorkerObserver
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.AfterTest
import kotlin.test.Test

class OpenTelemetryTramaiWorkerObserverTest {
    private val exporter = InMemorySpanExporter.create()
    private val metricReader = InMemoryMetricReader.create()
    private val tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
    private val meterProvider = SdkMeterProvider.builder()
        .registerMetricReader(metricReader)
        .build()
    private val openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .setMeterProvider(meterProvider)
        .build()

    @AfterTest
    fun tearDown() {
        exporter.reset()
        tracerProvider.shutdown()
        meterProvider.shutdown()
    }

    @Test
    fun `worker lifecycle creates and ends a span`() {
        val observer = OpenTelemetryTramaiWorkerObserver(openTelemetry)
        observer.onWorkerStarted("worker-1")
        observer.onWorkerStopped("worker-1")

        val span = exporter.finishedSpanItems.single()
        assertThat(span.name).isEqualTo("worker.worker-1")
        assertThat(span.attributes.asMap())
            .containsEntry(AttributeKey.stringKey("tramai.worker.id"), "worker-1")
    }

    @Test
    fun `records worker events as span events`() {
        val observer = OpenTelemetryTramaiWorkerObserver(openTelemetry)
        observer.onWorkerStarted("worker-2")
        observer.onWorkerHeartbeat("worker-2", 5000L, 3)
        observer.onLeaseAcquired("wf-1", "worker-2")
        observer.onLeaseReleased("wf-1", "worker-2")
        observer.onWorkerStopped("worker-2")

        val span = exporter.finishedSpanItems.single()
        assertThat(span.events.map { it.name }).contains(
            "tramai.worker.heartbeat",
            "tramai.worker.lease.acquired",
            "tramai.worker.lease.released",
        )
    }

    @Test
    fun `records shutdown lifecycle events`() {
        val observer = OpenTelemetryTramaiWorkerObserver(openTelemetry)
        observer.onWorkerStarted("worker-3")
        observer.onShutdownStarted("worker-3")
        observer.onDrainProgress("worker-3", done = 2, pending = 0)
        observer.onShutdownComplete("worker-3")
        observer.onWorkerStopped("worker-3")

        val span = exporter.finishedSpanItems.single()
        assertThat(span.events.map { it.name }).contains(
            "tramai.worker.shutdown.started",
            "tramai.worker.drain.progress",
            "tramai.worker.shutdown.complete",
        )
    }

    @Test
    fun `records lease contested event with correct attributes`() {
        val observer = OpenTelemetryTramaiWorkerObserver(openTelemetry)
        observer.onWorkerStarted("worker-4")
        observer.onLeaseContested("wf-2", "worker-4", "worker-3")
        observer.onWorkerStopped("worker-4")

        val span = exporter.finishedSpanItems.single()
        assertThat(span.events)
            .anySatisfy { event ->
                assertThat(event.name).isEqualTo("tramai.worker.lease.contested")
                assertThat(event.attributes.asMap())
                    .containsEntry(AttributeKey.stringKey("tramai.worker.id"), "worker-4")
                    .containsEntry(AttributeKey.stringKey("tramai.worker.current_owner"), "worker-3")
            }
    }

    @Test
    fun `records heartbeats metric`() {
        val observer = OpenTelemetryTramaiWorkerObserver(openTelemetry)
        observer.onWorkerStarted("worker-5")
        observer.onWorkerHeartbeat("worker-5", 1000L, 2)
        observer.onWorkerHeartbeat("worker-5", 2000L, 3)
        observer.onWorkerStopped("worker-5")

        val metrics = metricReader.collectAllMetrics()
        val heartbeatMetric = metrics.single { it.name == "tramai.worker.heartbeats" }
        assertThat(heartbeatMetric.longSumData.points.sumOf { it.value }).isEqualTo(2L)
    }

    @Test
    fun `records shutdowns metric`() {
        val observer = OpenTelemetryTramaiWorkerObserver(openTelemetry)
        observer.onWorkerStarted("worker-6")
        observer.onShutdownComplete("worker-6")
        observer.onWorkerStopped("worker-6")

        val metrics = metricReader.collectAllMetrics()
        val shutdownMetric = metrics.single { it.name == "tramai.worker.shutdowns" }
        assertThat(shutdownMetric.longSumData.points.sumOf { it.value }).isEqualTo(1L)
    }

    @Test
    fun `record exception for lease renewal failure`() {
        val observer = OpenTelemetryTramaiWorkerObserver(openTelemetry)
        observer.onWorkerStarted("worker-7")
        val error = RuntimeException("connection lost")
        observer.onLeaseRenewalFailed("wf-3", "worker-7", error)
        observer.onWorkerStopped("worker-7")

        val span = exporter.finishedSpanItems.single()
        assertThat(span.events)
            .anySatisfy { event ->
                assertThat(event.name).isEqualTo("exception")
            }
    }
}
