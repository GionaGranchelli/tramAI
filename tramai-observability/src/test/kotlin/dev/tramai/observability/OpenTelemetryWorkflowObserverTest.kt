package dev.tramai.observability

import dev.tramai.orchestration.InMemoryWorkflowCheckpointStore
import dev.tramai.orchestration.InMemoryWorkflowLeaseStore
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowLeasePolicy
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.orchestration.workflow
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.HistogramPointData
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.AfterTest
import kotlin.test.Test

class OpenTelemetryWorkflowObserverTest {
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
    fun `records checkpoint and lease workflow events as span events and metrics`() {
        val workflow = workflow<ObservedWorkflowState>("observed-workflow") {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(answer = "draft:${state.request}") },
            )
        }.build { it.answer ?: error("answer must exist") }
        val persistence = WorkflowPersistence(
            checkpointStore = InMemoryWorkflowCheckpointStore(),
            stateCodec = ObservedWorkflowStateCodec,
            leaseStore = InMemoryWorkflowLeaseStore(clockMillis = { 1_000L }),
            leasePolicy = WorkflowLeasePolicy(
                ownerId = "worker-1",
                leaseDurationMillis = 5_000,
            ),
            deleteCheckpointOnCompletion = false,
        )
        val observer = OpenTelemetryWorkflowObserver(openTelemetry)

        val result = runBlocking {
            workflow.run(
                initialState = ObservedWorkflowState(request = "invoice-123"),
                context = WorkflowContext(
                    workflowId = "wf-otel",
                    attributes = mapOf("tenant" to "alpha"),
                ),
                observer = observer,
                persistence = persistence,
            )
        }

        assertThat(result).isEqualTo("draft:invoice-123")

        val span = exporter.finishedSpanItems.single()
        assertThat(span.name).isEqualTo("workflow.observed-workflow")
        assertThat(span.attributes.asMap())
            .containsEntry(AttributeKey.stringKey("tramai.workflow.name"), "observed-workflow")
            .containsEntry(AttributeKey.stringKey("tramai.workflow.id"), "wf-otel")
            .containsEntry(AttributeKey.stringKey("tramai.workflow.context.tenant"), "alpha")
            .containsEntry(AttributeKey.stringKey("tramai.workflow.outcome"), "success")
        assertThat(span.events.map { it.name })
            .contains(
                "tramai.workflow.lease.claimed",
                "tramai.workflow.checkpoint.saved",
                "tramai.workflow.lease.renewed",
                "tramai.workflow.step.started",
                "tramai.workflow.step.completed",
                "tramai.workflow.lease.released",
            )

        val metrics = metricReader.collectAllMetrics()
        assertThat(longSumPoint(metrics, "tramai.workflow.runs").value).isEqualTo(1L)
        assertThat(histogramPoint(metrics, "tramai.workflow.duration").count).isEqualTo(1L)
        val eventTotal = longSumTotal(metrics, "tramai.workflow.events")
        assertThat(eventTotal).isGreaterThanOrEqualTo(1L)
        assertThat(metrics.single { it.name == "tramai.workflow.events" }.longSumData.points)
            .anySatisfy { point ->
                assertThat(point.attributes.asMap())
                    .containsEntry(AttributeKey.stringKey("tramai.event.name"), "tramai.workflow.lease.claimed")
            }
    }

    @Test
    fun `records checkpoint loaded event on resume`() {
        val workflow = workflow<ObservedWorkflowState>("resume-observed-workflow") {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(answer = "draft:${state.request}") },
            )
        }.build { it.answer ?: error("answer must exist") }
        val persistence = WorkflowPersistence(
            checkpointStore = InMemoryWorkflowCheckpointStore(),
            stateCodec = ObservedWorkflowStateCodec,
            deleteCheckpointOnCompletion = false,
        )
        val observer = OpenTelemetryWorkflowObserver(openTelemetry)

        runBlocking {
            workflow.run(
                initialState = ObservedWorkflowState(request = "invoice-456"),
                context = WorkflowContext(workflowId = "wf-resume"),
                observer = observer,
                persistence = persistence,
            )
        }
        exporter.reset()
        metricReader.collectAllMetrics()

        val resumed = runBlocking {
            workflow.resume(
                context = WorkflowContext(workflowId = "wf-resume"),
                observer = observer,
                persistence = persistence,
            )
        }

        assertThat(resumed).isEqualTo("draft:invoice-456")
        val span = exporter.finishedSpanItems.single()
        assertThat(span.events.map { it.name })
            .contains("tramai.workflow.checkpoint.loaded")
    }

    private fun longSumPoint(
        metrics: Collection<io.opentelemetry.sdk.metrics.data.MetricData>,
        name: String,
    ): LongPointData = metrics.single { it.name == name }.longSumData.points.single()

    private fun longSumTotal(
        metrics: Collection<io.opentelemetry.sdk.metrics.data.MetricData>,
        name: String,
    ): Long = metrics.single { it.name == name }.longSumData.points.sumOf { it.value }

    private fun histogramPoint(
        metrics: Collection<io.opentelemetry.sdk.metrics.data.MetricData>,
        name: String,
    ): HistogramPointData = metrics.single { it.name == name }.histogramData.points.single()
}

private data class ObservedWorkflowState(
    val request: String,
    val answer: String? = null,
)

private object ObservedWorkflowStateCodec : WorkflowStateCodec<ObservedWorkflowState> {
    override fun encode(state: ObservedWorkflowState): String = listOf(
        state.request,
        state.answer.orEmpty(),
    ).joinToString("|")

    override fun decode(payload: String): ObservedWorkflowState {
        val parts = payload.split("|", limit = 2)
        return ObservedWorkflowState(
            request = parts[0],
            answer = parts.getOrNull(1).orEmpty().ifBlank { null },
        )
    }
}
