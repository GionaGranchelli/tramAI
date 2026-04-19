package dev.tramai.observability

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.engine.TramaiEngine
import dev.tramai.engine.create
import dev.tramai.structured.JacksonStructuredOutputHandler
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.AfterTest
import kotlin.test.Test

class OpenTelemetryOperationObserverTest {
    private val exporter = InMemorySpanExporter.create()
    private val tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
    private val openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .build()

    @AfterTest
    fun tearDown() {
        exporter.reset()
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
            .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.system"), "anthropic")
            .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.request.model"), "claude-sonnet-4-20250514")
            .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.response.model"), "claude-sonnet-4-20250514")
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
            assertThat(event.attributes.asMap().keys.map { it.key }).contains("tramai.raw_response", "tramai.validation_error")
        }
        assertThat(firstAttempt.attributes.asMap())
            .containsEntry(io.opentelemetry.api.common.AttributeKey.booleanKey("tramai.structured.parse_success"), false)
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
