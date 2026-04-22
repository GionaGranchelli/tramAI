package dev.tramai.observability
import dev.tramai.orchestration.InMemoryWorkflowCheckpointStore
import dev.tramai.orchestration.InMemoryWorkflowLeaseStore
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowLeasePolicy
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.orchestration.workflow
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.HistogramPointData
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    @Test
    fun `same workflow id across different workflows keeps failure and parallel branch events separate`() {
        val observer = OpenTelemetryWorkflowObserver(openTelemetry)
        val allowParallelCompletion = CompletableDeferred<Unit>()
        val firstBranchStarted = CompletableDeferred<Unit>()
        val sharedWorkflowId = "shared-workflow-id"
        val parallelWorkflow = workflow<ObservedWorkflowState>("parallel-observed-workflow") {
            parallelStep(
                name = "fanout",
                items = { listOf("a", "b") },
                invoke = { item ->
                    firstBranchStarted.complete(Unit)
                    allowParallelCompletion.await()
                    "branch-$item"
                },
                merge = { state, results -> state.copy(answer = results.joinToString(",")) },
            )
        }.build { it.answer ?: error("answer must exist") }
        val failingWorkflow = workflow<ObservedWorkflowState>("failing-observed-workflow") {
            localStep(
                name = "explode",
                transform = { _, _ -> error("boom") },
            )
        }.build { it.answer ?: error("answer must exist") }
        runBlocking {
            val firstRun = async {
                parallelWorkflow.run(
                    initialState = ObservedWorkflowState(request = "parallel"),
                    context = WorkflowContext(workflowId = sharedWorkflowId),
                    observer = observer,
                )
            }
            firstBranchStarted.await()
            assertThatThrownBy {
                runBlocking {
                    failingWorkflow.run(
                        initialState = ObservedWorkflowState(request = "failure"),
                        context = WorkflowContext(workflowId = sharedWorkflowId),
                        observer = observer,
                    )
                }
            }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("boom")
            allowParallelCompletion.complete(Unit)
            assertThat(firstRun.await()).isEqualTo("branch-a,branch-b")
        }
        val spansByName = exporter.finishedSpanItems.associateBy { it.name }
        assertThat(spansByName.keys)
            .containsExactlyInAnyOrder(
                "workflow.parallel-observed-workflow",
                "workflow.failing-observed-workflow",
            )
        val parallelSpan = spansByName.getValue("workflow.parallel-observed-workflow")
        assertThat(parallelSpan.status.statusCode).isEqualTo(StatusCode.UNSET)
        assertThat(parallelSpan.attributes.asMap())
            .containsEntry(AttributeKey.stringKey("tramai.workflow.name"), "parallel-observed-workflow")
            .containsEntry(AttributeKey.stringKey("tramai.workflow.id"), sharedWorkflowId)
            .containsEntry(AttributeKey.stringKey("tramai.workflow.outcome"), "success")
        assertThat(parallelSpan.events)
            .anySatisfy { event ->
                assertThat(event.name).isEqualTo("tramai.workflow.step.started")
                assertThat(event.attributes.asMap())
                    .containsEntry(AttributeKey.stringKey("step_name"), "fanout[0]")
            }
        assertThat(parallelSpan.events)
            .anySatisfy { event ->
                assertThat(event.name).isEqualTo("tramai.workflow.step.completed")
                assertThat(event.attributes.asMap())
                    .containsEntry(AttributeKey.stringKey("step_name"), "fanout[1]")
            }
        val failingSpan = spansByName.getValue("workflow.failing-observed-workflow")
        assertThat(failingSpan.status.statusCode).isEqualTo(StatusCode.ERROR)
        assertThat(failingSpan.attributes.asMap())
            .containsEntry(AttributeKey.stringKey("tramai.workflow.name"), "failing-observed-workflow")
            .containsEntry(AttributeKey.stringKey("tramai.workflow.id"), sharedWorkflowId)
            .containsEntry(AttributeKey.stringKey("tramai.workflow.outcome"), "failure")
        assertThat(failingSpan.events)
            .anySatisfy { event ->
                assertThat(event.name).isEqualTo("tramai.workflow.step.failed")
                assertThat(event.attributes.asMap())
                    .containsEntry(AttributeKey.stringKey("step_name"), "explode")
            }
    }
    @Test
    fun `resumed workflow keeps checkpoint loaded attribution when another workflow shares the workflow id`() {
        val observer = OpenTelemetryWorkflowObserver(openTelemetry)
        val persistence = WorkflowPersistence(
            checkpointStore = InMemoryWorkflowCheckpointStore(),
            stateCodec = ObservedWorkflowStateCodec,
            deleteCheckpointOnCompletion = false,
        )
        val sharedWorkflowId = "shared-resume-id"
        val allowResumeCompletion = CompletableDeferred<Unit>()
        val resumedStepStarted = CompletableDeferred<Unit>()
        var failResumeSeed = true
        val resumableWorkflow = workflow<ObservedWorkflowState>("resumable-observed-workflow") {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(answer = "draft:${state.request}") },
            )
            localStep(
                name = "finalize",
                transform = { state, _ ->
                    if (failResumeSeed) {
                        failResumeSeed = false
                        error("seed failure")
                    }
                    resumedStepStarted.complete(Unit)
                    allowResumeCompletion.await()
                    state.copy(answer = "final:${state.answer}")
                },
            )
        }.build { it.answer ?: error("answer must exist") }
        val otherWorkflow = workflow<ObservedWorkflowState>("other-observed-workflow") {
            localStep(
                name = "summarize",
                transform = { state, _ -> state.copy(answer = "summary:${state.request}") },
            )
        }.build { it.answer ?: error("answer must exist") }
        assertThatThrownBy {
            runBlocking {
                resumableWorkflow.run(
                    initialState = ObservedWorkflowState(request = "resume"),
                    context = WorkflowContext(workflowId = sharedWorkflowId),
                    observer = observer,
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("seed failure")
        exporter.reset()
        metricReader.collectAllMetrics()
        runBlocking {
            val resumedRun = async {
                resumableWorkflow.resume(
                    context = WorkflowContext(workflowId = sharedWorkflowId),
                    observer = observer,
                    persistence = persistence,
                )
            }
            resumedStepStarted.await()
            assertThat(
                otherWorkflow.run(
                    initialState = ObservedWorkflowState(request = "other"),
                    context = WorkflowContext(workflowId = sharedWorkflowId),
                    observer = observer,
                ),
            ).isEqualTo("summary:other")
            allowResumeCompletion.complete(Unit)
            assertThat(resumedRun.await()).isEqualTo("final:draft:resume")
        }
        val spansByName = exporter.finishedSpanItems.associateBy { it.name }
        assertThat(spansByName.keys)
            .containsExactlyInAnyOrder(
                "workflow.resumable-observed-workflow",
                "workflow.other-observed-workflow",
            )
        val resumedSpan = spansByName.getValue("workflow.resumable-observed-workflow")
        assertThat(resumedSpan.events.map { it.name })
            .contains("tramai.workflow.checkpoint.loaded")
        assertThat(resumedSpan.attributes.asMap())
            .containsEntry(AttributeKey.stringKey("tramai.workflow.name"), "resumable-observed-workflow")
            .containsEntry(AttributeKey.stringKey("tramai.workflow.id"), sharedWorkflowId)
            .containsEntry(AttributeKey.stringKey("tramai.workflow.outcome"), "success")
        val otherSpan = spansByName.getValue("workflow.other-observed-workflow")
        assertThat(otherSpan.events)
            .anySatisfy { event ->
                assertThat(event.name).isEqualTo("tramai.workflow.step.completed")
                assertThat(event.attributes.asMap())
                    .containsEntry(AttributeKey.stringKey("step_name"), "summarize")
            }
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
