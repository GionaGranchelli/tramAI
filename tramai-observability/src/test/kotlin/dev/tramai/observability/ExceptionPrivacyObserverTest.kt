package dev.tramai.observability

import dev.tramai.orchestration.WorkflowContext
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.stream.Collectors

/**
 * Issue #551 — no failure path exports an exception message or stack trace.
 *
 * Every path below takes a canary-bearing exception. The canary must be absent from every exported span's
 * attributes, events (including event attribute values and the exception event), and status description,
 * while the non-sensitive diagnostics — `exception.type`, event names, identifiers and metric counts —
 * must still be there. A test that only asserts the canary is absent would pass on a span that recorded
 * nothing, so each case also asserts the surviving diagnostic.
 *
 * Six paths, matching the production callbacks one for one: workflow step failure, workflow failure, and
 * the worker's lease-renewal, lease-release, poll and step failures.
 */
class ExceptionPrivacyObserverTest {
    private lateinit var exporter: InMemorySpanExporter
    private lateinit var provider: SdkTracerProvider

    @BeforeEach
    fun setUp() {
        exporter = InMemorySpanExporter.create()
        provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
    }

    @AfterEach
    fun tearDown() {
        provider.close()
    }

    @Test
    fun `workflow step failure exports the type but never the message or stack trace`() {
        val observer = OpenTelemetryWorkflowObserver(provider.get("test"))
        val context = WorkflowContext(workflowId = WORKFLOW_ID)
        observer.onWorkflowStarted(WORKFLOW_NAME, context)

        observer.onStepFailed(WORKFLOW_NAME, "classify", canaryFailure(), context)
        observer.onWorkflowCompleted(WORKFLOW_NAME, context)

        val span = singleSpan()
        assertNoCanary(span)
        assertThat(exposesFailureType(span)).isTrue()
        assertThat(eventNames(span)).contains("exception", "tramai.workflow.step.failed")
    }

    @Test
    fun `workflow failure exports a fixed status description and never the message`() {
        val observer = OpenTelemetryWorkflowObserver(provider.get("test"))
        val context = WorkflowContext(workflowId = WORKFLOW_ID)
        observer.onWorkflowStarted(WORKFLOW_NAME, context)

        observer.onWorkflowFailed(WORKFLOW_NAME, canaryFailure(), context)

        val span = singleSpan()
        assertNoCanary(span)
        assertThat(span.status.statusCode.name).isEqualTo("ERROR")
        // The old code put `error.message` here; the description must be the fixed one.
        assertThat(span.status.description).isEqualTo("Workflow failed")
        assertThat(exposesFailureType(span)).isTrue()
    }

    @Test
    fun `worker lease renewal failure exports the type but never the message or stack trace`() {
        val observer = OpenTelemetryTramaiWorkerObserver(provider.get("test"))
        observer.onWorkerStarted(WORKER_ID)

        observer.onLeaseRenewalFailed(WORKFLOW_ID, WORKER_ID, canaryFailure())
        observer.onWorkerStopped(WORKER_ID)

        val span = singleSpan()
        assertNoCanary(span)
        assertThat(exposesFailureType(span)).isTrue()
        assertThat(eventNames(span)).contains("exception", "tramai.worker.lease.renewal_failed")
    }

    @Test
    fun `worker lease release failure exports the type but never the message or stack trace`() {
        val observer = OpenTelemetryTramaiWorkerObserver(provider.get("test"))
        observer.onWorkerStarted(WORKER_ID)

        observer.onLeaseReleaseFailed(WORKFLOW_ID, WORKER_ID, canaryFailure())
        observer.onWorkerStopped(WORKER_ID)

        val span = singleSpan()
        assertNoCanary(span)
        assertThat(exposesFailureType(span)).isTrue()
        assertThat(eventNames(span)).contains("exception", "tramai.worker.lease.release_failed")
    }

    @Test
    fun `worker poll failure exports the type but never the message or stack trace`() {
        val observer = OpenTelemetryTramaiWorkerObserver(provider.get("test"))
        observer.onWorkerStarted(WORKER_ID)

        observer.onPollFailed(WORKER_ID, canaryFailure())
        observer.onWorkerStopped(WORKER_ID)

        val span = singleSpan()
        assertNoCanary(span)
        assertThat(exposesFailureType(span)).isTrue()
        assertThat(eventNames(span)).contains("exception", "tramai.worker.poll.failed")
    }

    @Test
    fun `worker step failure exports the type but never the message or stack trace`() {
        val observer = OpenTelemetryTramaiWorkerObserver(provider.get("test"))
        observer.onWorkerStarted(WORKER_ID)

        observer.onStepAttemptFailed(RUN_ID, "classify", ATTEMPT_ID, WORKER_ID, canaryFailure())
        observer.onWorkerStopped(WORKER_ID)

        val span = singleSpan()
        assertNoCanary(span)
        // Identifiers stay: they are the diagnostic value of the event.
        assertThat(exposesFailureType(span)).isTrue()
        assertThat(eventAttributeValues(span)).contains(RUN_ID, "classify", ATTEMPT_ID)
        assertThat(eventNames(span)).contains("exception", "tramai.worker.step.failed")
    }

    // ------------------------------------------------------------------ helpers

    /** One canary-bearing failure, raised from a real stack frame. */
    private fun canaryFailure(): Throwable = IllegalStateException("$CANARY message body with bearer $CANARY")

    private fun singleSpan(): SpanData {
        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(1)
        return spans.single()
    }

    private fun eventNames(span: SpanData): List<String> = span.events.map { it.name }

    /** The non-sensitive diagnostic that must survive: the failure's type, in either spelling. */
    private fun exposesFailureType(span: SpanData): Boolean {
        val values =
            span.events.flatMap { event ->
                event.attributes
                    .asMap()
                    .values
                    .map { it.toString() }
            }
        return values.any { it.contains("IllegalStateException") }
    }

    /** Every attribute value on the span and on each of its events, as strings. */
    private fun eventAttributeValues(span: SpanData): List<String> {
        val values = mutableListOf<String>()
        span.attributes.forEach { key, value -> values.add("${key.key}=$value") }
        span.events.forEach { event -> event.attributes.forEach { key, value -> values.add(value.toString()) } }
        span.events.forEach { event -> event.attributes.forEach { key, _ -> values.add(key.key) } }
        return values
    }

    /**
     * Asserts the canary appears nowhere that a back-end would export: attributes, event attribute keys and
     * values, event names, the status description, and the span's full string form.
     */
    private fun assertNoCanary(span: SpanData) {
        val renderings =
            listOf(
                span.attributes.asMap().toString(),
                span.events.joinToString("|") { it.name + it.attributes.asMap().toString() },
                span.status.description ?: "",
                span.toString(),
            )
        renderings.forEach { rendering ->
            assertThat(rendering).doesNotContain(CANARY)
        }
        // A stack trace would smuggle the message back in through the frames.
        val traceLike =
            span.events
                .stream()
                .flatMap {
                    it.attributes
                        .asMap()
                        .values
                        .stream()
                }.map { it.toString() }
                .collect(Collectors.toList())
        traceLike.forEach { value -> assertThat(value).doesNotContain("\tat ") }
    }

    private companion object {
        const val CANARY = "CANARY-EXC-4f19"
        const val WORKFLOW_NAME = "claims"
        const val WORKFLOW_ID = "run-1"
        const val WORKER_ID = "worker-1"
        const val RUN_ID = "run-1"
        const val ATTEMPT_ID = "attempt-1"
    }
}
