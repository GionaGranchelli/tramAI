package dev.tramai.observability

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.provider.ModelProvider
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.TramaiEngine
import dev.tramai.engine.create
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Integration test verifying that a real engine cancellation produces
 * correct OpenTelemetry span and metric attributes.
 */
class OpenTelemetryCancellationIntegrationTest {
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
    fun `engine cancellation is attributed as cancelled in spans and metrics`() {
        val provider = object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                throw CancellationException("cancelled")
            }
        }

        var caught: CancellationException? = null
        val engine = TramaiEngine(
            provider = provider,
            operationObserver = OpenTelemetryOperationObserver(openTelemetry),
        )
        val service = engine.create<CancellableService>()

        runBlocking {
            try {
                service.respond("hello")
            } catch (e: CancellationException) {
                caught = e
            }
        }

        assertThat(caught).isNotNull
        assertThat(caught).hasMessage("cancelled")

        // Span assertions
        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(1)
        val span = spans.single()
        assertThat(span.attributes.asMap())
            .containsEntry(AttributeKey.stringKey("tramai.outcome"), "cancelled")
        // Span must not be classified as a generic error
        assertThat(span.status.statusCode).isNotEqualTo(StatusCode.ERROR)

        // Metric assertions
        val metrics = metricReader.collectAllMetrics()
        val attemptMetric = longSumPoint(metrics, "tramai.operation.attempts")
        assertThat(attemptMetric.value).isOne()
        assertThat(attemptMetric.attributes.asMap())
            .containsEntry(AttributeKey.stringKey("tramai.outcome"), "cancelled")
        // No error type attribute should be present for cancellation
        assertThat(attemptMetric.attributes.asMap().keys.map { it.key })
            .doesNotContain("tramai.error.type")
    }

    @Test
    fun `tool cancellation is attributed as cancelled in spans and metrics`() {
        val tool = CancellingTool()
        val provider = object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                return ModelResponse(
                    content = "tool time",
                    toolCalls = listOf(ToolCall("1", "cancelling-tool", """{}""")),
                )
            }
        }

        var caught: kotlinx.coroutines.CancellationException? = null
        val registry = dev.tramai.core.provider.ProviderRegistry.builder()
            .provider("primary", provider)
            .model("test-model", "primary")
            .defaultProvider("primary")
            .build()

        val engine = TramaiEngine(
            providerRegistry = registry,
            toolRegistry = ToolRegistry(mapOf(tool.name to tool)),
            operationObserver = OpenTelemetryOperationObserver(openTelemetry),
        )
        val service = engine.create<CancellableService>()

        runBlocking {
            try {
                service.respond("hello")
            } catch (e: kotlinx.coroutines.CancellationException) {
                caught = e
            }
        }

        assertThat(caught).isNotNull

        // Span assertions
        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(1)
        val span = spans.single()
        assertThat(span.attributes.asMap())
            .containsEntry(AttributeKey.stringKey("tramai.outcome"), "cancelled")
        assertThat(span.status.statusCode).isNotEqualTo(StatusCode.ERROR)

        // Metric assertions
        val metrics = metricReader.collectAllMetrics()
        val attemptMetric = longSumPoint(metrics, "tramai.operation.attempts")
        assertThat(attemptMetric.value).isOne()
        assertThat(attemptMetric.attributes.asMap())
            .containsEntry(AttributeKey.stringKey("tramai.outcome"), "cancelled")
        assertThat(attemptMetric.attributes.asMap().keys.map { it.key })
            .doesNotContain("tramai.error.type")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun longSumPoint(
        metrics: Collection<MetricData>,
        name: String,
    ): LongPointData {
        return metrics.single { it.name == name }.longSumData.points.single()
    }

}

/**
 * Tool that throws CancellationException on every call.
 */
private class CancellingTool : ResolvedTool {
    override val name: String = "cancelling-tool"
    override val description: String = "a tool that cancels"
    override val inputSchemaJson: String = """{"type":"object"}"""
    override val idempotent: Boolean = true
    override val sideEffectLevel: dev.tramai.core.model.SideEffectLevel = dev.tramai.core.model.SideEffectLevel.READ_ONLY
    override val security = null

    override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
        throw kotlinx.coroutines.CancellationException("cancelled during tool execution")
    }
}

@AiService
interface CancellableService {
    @Operation(model = "test-model")
    suspend fun respond(input: String): String
}

@AiService
interface ToolCancellableService {
    @Operation(model = "test-model")
    suspend fun respond(input: String): String
}
