package dev.tramai.observability

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.provider.ModelProvider
import dev.tramai.engine.TramaiEngine
import dev.tramai.engine.create
import dev.tramai.observability.OpenTelemetryOperationObserverTest.MetricNames.ATTEMPTS
import dev.tramai.observability.OpenTelemetryOperationObserverTest.MetricNames.DURATION
import dev.tramai.observability.OpenTelemetryOperationObserverTest.MetricNames.ENGINE_EVENTS
import dev.tramai.observability.OpenTelemetryOperationObserverTest.MetricNames.INPUT_TOKENS
import dev.tramai.observability.OpenTelemetryOperationObserverTest.MetricNames.OUTPUT_TOKENS
import dev.tramai.observability.OpenTelemetryOperationObserverTest.MetricNames.PARSE_FAILURES
import dev.tramai.structured.JacksonStructuredOutputHandler
import com.sun.net.httpserver.HttpServer
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.HistogramPointData
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test

class OpenTelemetryOperationObserverTest {
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
    fun `records span attributes for successful provider call`() {
        val provider = RecordingProvider("anthropic") {
            ModelResponse(
                content = "hello",
                inputTokens = 12,
                outputTokens = 5,
                modelUsed = "claude-sonnet-4-20250514",
            )
        }
        val engine = TramaiEngine(
            provider = provider,
            operationObserver = OpenTelemetryOperationObserver(openTelemetry),
        )
        val service = engine.create<RawService>()

        val result = runBlocking { service.respond("world") }

        assertThat(result).isEqualTo("hello")
        val span = exporter.finishedSpanItems.single()
        assertThat(span.name).isEqualTo("ai.respond")
        assertThat(span.attributes.asMap())
            .containsEntry(AttributeKey.stringKey("gen_ai.system"), "anthropic")
            .containsEntry(AttributeKey.stringKey("gen_ai.request.model"), "claude-sonnet-4-20250514")
            .containsEntry(AttributeKey.stringKey("gen_ai.response.model"), "claude-sonnet-4-20250514")

        val metrics = metricReader.collectAllMetrics()
        assertThat(longSumPoint(metrics, ATTEMPTS).value).isEqualTo(1L)
        assertThat(longSumPoint(metrics, INPUT_TOKENS).value).isEqualTo(12L)
        assertThat(longSumPoint(metrics, OUTPUT_TOKENS).value).isEqualTo(5L)
        assertThat(histogramPoint(metrics, DURATION).count).isEqualTo(1L)
        assertThat(histogramPoint(metrics, DURATION).sum).isGreaterThanOrEqualTo(0.0)
        assertThat(histogramPoint(metrics, "tramai.operation.input_tokens.per_attempt").sum).isEqualTo(12.0)
        assertThat(histogramPoint(metrics, "tramai.operation.output_tokens.per_attempt").sum).isEqualTo(5.0)
    }

    @Test
    fun `records parse failure event before successful retry`() {
        val provider = SequencedProvider(
            "anthropic",
            ModelResponse(content = "not json"),
            ModelResponse(content = """{"status":"ok"}"""),
        )
        val engine = TramaiEngine(
            provider = provider,
            structuredOutputHandler = JacksonStructuredOutputHandler(),
            operationObserver = OpenTelemetryOperationObserver(openTelemetry),
        )
        val service = engine.create<StructuredService>()

        val result = runBlocking { service.status("tenant-a") }

        assertThat(result).isEqualTo(Status("ok"))
        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        val firstAttempt = spans.first()
        assertThat(firstAttempt.events).anySatisfy { event ->
            assertThat(event.name).isEqualTo("tramai.parse.failure")
            assertThat(event.attributes.asMap().keys.map { it.key }).contains("tramai.raw_response_length", "tramai.validation_error")
        }
        assertThat(firstAttempt.attributes.asMap())
            .containsEntry(AttributeKey.booleanKey("tramai.structured.parse_success"), false)

        val metrics = metricReader.collectAllMetrics()
        val parseFailure = longSumPoint(metrics, PARSE_FAILURES)
        assertThat(parseFailure.value).isEqualTo(1L)
        assertThat(parseFailure.attributes.asMap())
            .containsEntry(AttributeKey.stringKey("tramai.outcome"), "parse_failure")
    }

    @Test
    fun `records engine events as span events and metrics`() {
        val observation = OpenTelemetryOperationObserver(openTelemetry).onCallStarted(
            OperationCallContext(
                serviceInterface = "test.Service",
                methodName = "respond",
                providerId = "openai",
                requestedModel = "gpt-5.1-chat-latest",
                attempt = 0,
            ),
        )

        observation.onEngineEvent(
            name = "tramai.retry.scheduled",
            attributes = mapOf(
                "delay_millis" to 42L,
                "delay_source" to "retry_after",
                "is_fallback" to false,
            ),
        )
        observation.onCallCompleted(parseSuccess = null)

        val span = exporter.finishedSpanItems.single()
        assertThat(span.events).anySatisfy { event ->
            assertThat(event.name).isEqualTo("tramai.retry.scheduled")
            assertThat(event.attributes.asMap())
                .containsEntry(AttributeKey.longKey("delay_millis"), 42L)
                .containsEntry(AttributeKey.stringKey("delay_source"), "retry_after")
                .containsEntry(AttributeKey.booleanKey("is_fallback"), false)
        }

        val eventMetric = longSumPoint(metricReader.collectAllMetrics(), ENGINE_EVENTS)
        assertThat(eventMetric.value).isEqualTo(1L)
        assertThat(eventMetric.attributes.asMap())
            .containsEntry(AttributeKey.stringKey("tramai.event.name"), "tramai.retry.scheduled")
            .containsEntry(AttributeKey.longKey("delay_millis"), 42L)
            .containsEntry(AttributeKey.stringKey("delay_source"), "retry_after")
            .containsEntry(AttributeKey.booleanKey("is_fallback"), false)
    }

    @Test
    fun `exports operation metrics over OTLP HTTP`() {
        val exportLatch = CountDownLatch(1)
        var capturedRequestPath = ""
        var capturedContentType = ""
        var capturedBody = ByteArray(0)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/metrics") { exchange ->
            capturedRequestPath = exchange.requestURI.path
            capturedContentType = exchange.requestHeaders.getFirst("Content-Type")
            capturedBody = exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
            exportLatch.countDown()
        }
        server.start()

        val metricExporter = OtlpHttpMetricExporter.builder()
            .setEndpoint("http://localhost:${server.address.port}/v1/metrics")
            .build()
        val periodicMetricReader = PeriodicMetricReader.builder(metricExporter)
            .setInterval(Duration.ofMillis(25))
            .build()
        val otlpMeterProvider = SdkMeterProvider.builder()
            .registerMetricReader(periodicMetricReader)
            .build()
        val otlpOpenTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(otlpMeterProvider)
            .build()

        try {
            val provider = RecordingProvider("openai") {
                ModelResponse(
                    content = "hello",
                    inputTokens = 9,
                    outputTokens = 4,
                    modelUsed = "gpt-5.1-chat-latest",
                )
            }
            val engine = TramaiEngine(
                provider = provider,
                operationObserver = OpenTelemetryOperationObserver(otlpOpenTelemetry),
            )
            val service = engine.create<RawService>()

            val result = runBlocking { service.respond("world") }

            assertThat(result).isEqualTo("hello")
            assertThat(otlpMeterProvider.forceFlush().join(15, TimeUnit.SECONDS).isSuccess()).isTrue()
            assertThat(exportLatch.await(15, TimeUnit.SECONDS)).isTrue()
            assertThat(capturedRequestPath).isEqualTo("/v1/metrics")
            assertThat(capturedContentType).startsWith("application/x-protobuf")
            assertThat(capturedBody).isNotEmpty()
        } finally {
            otlpMeterProvider.shutdown()
            server.stop(0)
        }
    }

    private fun longSumPoint(
        metrics: Collection<io.opentelemetry.sdk.metrics.data.MetricData>,
        name: String,
    ): LongPointData {
        return metrics.single { it.name == name }.longSumData.points.single()
    }

    private fun histogramPoint(
        metrics: Collection<io.opentelemetry.sdk.metrics.data.MetricData>,
        name: String,
    ): HistogramPointData {
        return metrics.single { it.name == name }.histogramData.points.single()
    }

    private object MetricNames {
        const val ATTEMPTS = "tramai.operation.attempts"
        const val DURATION = "tramai.operation.duration"
        const val ENGINE_EVENTS = "tramai.engine.events"
        const val INPUT_TOKENS = "tramai.operation.input_tokens"
        const val OUTPUT_TOKENS = "tramai.operation.output_tokens"
        const val PARSE_FAILURES = "tramai.operation.parse_failures"
    }
}

@AiService
private interface RawService {
    @Operation(
        prompt = "Return a raw response",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun respond(name: String): String
}

@AiService
private interface StructuredService {
    @Operation(
        prompt = "Return a structured status",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun status(tenantId: String): Status
}

private data class Status(
    val status: String,
)

private class RecordingProvider(
    private val name: String,
    private val responder: suspend (ModelRequest) -> ModelResponse,
) : ModelProvider {
    override suspend fun complete(request: ModelRequest): ModelResponse = responder(request)

    override fun providerId(): String = name
}

private class SequencedProvider(
    private val name: String,
    vararg responses: ModelResponse,
) : ModelProvider {
    private val queuedResponses = ArrayDeque(responses.toList())

    override suspend fun complete(request: ModelRequest): ModelResponse {
        return queuedResponses.removeFirst()
    }

    override fun providerId(): String = name
}
