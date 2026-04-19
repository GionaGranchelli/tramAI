package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.SystemPrompt
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.StructuredOutputException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.structured.JacksonStructuredOutputHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class TramaiEngineTest {

    @Test
    fun `creates a suspend proxy and returns provider content`() {
        val provider = RecordingProvider { ModelResponse(content = "hardcoded response") }
        val engine = TramaiEngine(provider)
        val service = engine.create<SuspendAnalyzer>()

        val result = runBlocking { service.analyze("invoice-123") }

        assertThat(result).isEqualTo("hardcoded response")
        assertThat(provider.requests).hasSize(1)
        assertThat(provider.requests.single().model).isEqualTo("claude-sonnet-4-20250514")
        assertThat(provider.requests.single().timeoutMillis).isEqualTo(30_000)
        assertThat(provider.requests.single().messages.map { it.role })
            .containsExactly(MessageRole.SYSTEM, MessageRole.USER)
        assertThat(provider.requests.single().messages.last().content)
            .contains("Analyze the invoice")
            .contains("invoiceId")
            .contains("invoice-123")
    }

    @Test
    fun `supports blocking interfaces`() {
        val provider = RecordingProvider { ModelResponse(content = "summary") }
        val engine = TramaiEngine(provider)
        val service = engine.create<BlockingSummarizer>()

        val result = service.summarize("raw input")

        assertThat(result).isEqualTo("summary")
    }

    @Test
    fun `routes unit return types and ignores provider content`() {
        val provider = RecordingProvider { ModelResponse(content = "ignored") }
        val engine = TramaiEngine(provider)
        val service = engine.create<SuspendNotifier>()

        val result = runBlocking { service.notify("hello") }

        assertThat(result).isEqualTo(Unit)
        assertThat(provider.requests.single().messages.last().content)
            .contains("Send a notification")
            .contains("hello")
    }

    @Test
    fun `rejects interfaces without ai service annotation`() {
        val engine = TramaiEngine(RecordingProvider { ModelResponse(content = "unused") })

        assertThatThrownBy { engine.create<NotAnAiService>() }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("@AiService")
    }

    @Test
    fun `rejects methods without operation annotation`() {
        val engine = TramaiEngine(RecordingProvider { ModelResponse(content = "unused") })

        assertThatThrownBy { engine.create<MissingOperationAnnotation>() }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("@Operation")
    }

    @Test
    fun `rejects invalid retry and timeout settings`() {
        val engine = TramaiEngine(RecordingProvider { ModelResponse(content = "unused") })

        assertThatThrownBy { engine.create<InvalidTimeoutService>() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("timeoutMillis")
    }

    @Test
    fun `rejects structured return types before tramai structured exists`() {
        val engine = TramaiEngine(RecordingProvider { ModelResponse(content = """{"status":"ok"}""") })
        val service = engine.create<StructuredStatusService>()

        assertThatThrownBy { runBlocking { service.status("tenant-a") } }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("StructuredOutputHandler")
    }

    @Test
    fun `structured outputs retry after malformed first response`() {
        val provider = SequencedProvider(
            ModelResponse(content = "not json"),
            ModelResponse(content = """{"status":"ok"}"""),
        )
        val engine = TramaiEngine(
            provider = provider,
            structuredOutputHandler = JacksonStructuredOutputHandler(),
        )
        val service = engine.create<StructuredStatusService>()

        val result = runBlocking { service.status("tenant-a") }

        assertThat(result).isEqualTo(StatusResult(status = "ok"))
        assertThat(provider.requests).hasSize(2)
        assertThat(provider.requests.first().messages.last().content)
            .contains("Respond only with valid JSON matching this schema")
        assertThat(provider.requests.last().messages.map { it.role })
            .contains(MessageRole.ASSISTANT, MessageRole.USER)
    }

    @Test
    fun `structured outputs fail with typed exception after retries are exhausted`() {
        val provider = SequencedProvider(
            ModelResponse(content = "still not json"),
            ModelResponse(content = "still wrong"),
            ModelResponse(content = "nope"),
        )
        val engine = TramaiEngine(
            provider = provider,
            structuredOutputHandler = JacksonStructuredOutputHandler(),
        )
        val service = engine.create<StructuredStatusService>()

        assertThatThrownBy { runBlocking { service.status("tenant-a") } }
            .isInstanceOf(StructuredOutputException::class.java)
            .hasMessageContaining("failed after 3 attempt")
    }

    @Test
    fun `structured output exception preserves failure context`() {
        val provider = SequencedProvider(
            ModelResponse(content = "not json"),
            ModelResponse(content = "still wrong"),
            ModelResponse(content = "final bad payload"),
        )
        val engine = TramaiEngine(
            provider = provider,
            structuredOutputHandler = JacksonStructuredOutputHandler(),
        )
        val service = engine.create<StructuredStatusService>()

        assertThatThrownBy { runBlocking { service.status("tenant-a") } }
            .isInstanceOfSatisfying(StructuredOutputException::class.java) { error ->
                assertThat(error.originalPrompt).isEqualTo("Return a structured status")
                assertThat(error.lastRawResponse).isEqualTo("final bad payload")
                assertThat(error.validationError).contains("JSON")
                assertThat(error.attemptCount).isEqualTo(3)
            }
    }

    @Test
    fun `retries retryable provider failures before succeeding`() {
        val provider = RecordingProvider(
            SequenceResponder(
                suspend {
                    throw ProviderException("rate limited", statusCode = 429, retryable = true)
                },
                suspend {
                    throw ProviderException("service unavailable", statusCode = 503, retryable = true)
                },
                suspend {
                    ModelResponse(content = "recovered")
                },
            )::next,
        )
        val engine = TramaiEngine(provider)
        val service = engine.create<BlockingSummarizer>()

        val result = service.summarize("raw input")

        assertThat(result).isEqualTo("recovered")
        assertThat(provider.requests).hasSize(3)
    }

    @Test
    fun `does not retry non retryable provider failures`() {
        val provider = RecordingProvider {
            throw ProviderException("unauthorized", statusCode = 401, retryable = false)
        }
        val engine = TramaiEngine(provider)
        val service = engine.create<BlockingSummarizer>()

        assertThatThrownBy { service.summarize("raw input") }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("unauthorized")

        assertThat(provider.requests).hasSize(1)
    }

    @Test
    fun `retries timeout failures within provider budget`() {
        val provider = RecordingProvider(
            SequenceResponder(
                suspend {
                    delay(30)
                    ModelResponse(content = "too slow")
                },
                suspend {
                    ModelResponse(content = "fast enough")
                },
            )::next,
        )
        val engine = TramaiEngine(provider)
        val service = engine.create<TimeoutRetryService>()

        val result = runBlocking { service.analyze("invoice-123") }

        assertThat(result).isEqualTo("fast enough")
        assertThat(provider.requests).hasSize(2)
    }

    @Test
    fun `fails with timeout exception after provider retries are exhausted`() {
        val provider = RecordingProvider {
            delay(30)
            ModelResponse(content = "never reached")
        }
        val engine = TramaiEngine(provider)
        val service = engine.create<AlwaysTimeoutService>()

        assertThatThrownBy { runBlocking { service.analyze("invoice-123") } }
            .isInstanceOf(TimeoutException::class.java)
            .hasMessageContaining("timed out")
            .hasMessageContaining("AlwaysTimeoutService.analyze")

        assertThat(provider.requests).hasSize(2)
    }

    @Test
    fun `wraps unexpected provider errors with provider exception`() {
        val engine = TramaiEngine(FailingProvider())
        val service = engine.create<SuspendAnalyzer>()

        assertThatThrownBy { runBlocking { service.analyze("invoice-123") } }
            .isInstanceOf(ProviderException::class.java)
            .hasCauseInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("failed while invoking")
    }

    @Test
    fun `resolves providers through explicit model registration`() {
        val anthropic = NamedProvider("anthropic") { ModelResponse(content = "anthropic result") }
        val ollama = NamedProvider("ollama") { ModelResponse(content = "ollama result") }
        val registry = ProviderRegistry.builder()
            .provider("anthropic", anthropic)
            .provider("ollama", ollama)
            .model("claude-sonnet-4-20250514", "anthropic")
            .model("llama3.2", "ollama")
            .build()
        val engine = TramaiEngine(providerRegistry = registry)
        val service = engine.create<BlockingSummarizer>()

        val result = service.summarize("raw input")

        assertThat(result).isEqualTo("anthropic result")
        assertThat(anthropic.requests).hasSize(1)
        assertThat(ollama.requests).isEmpty()
    }

    @Test
    fun `operation level provider selection overrides model registration`() {
        val anthropic = NamedProvider("anthropic") { ModelResponse(content = "anthropic result") }
        val ollama = NamedProvider("ollama") { ModelResponse(content = "ollama result") }
        val registry = ProviderRegistry.builder()
            .provider("anthropic", anthropic)
            .provider("ollama", ollama)
            .model("claude-sonnet-4-20250514", "anthropic")
            .build()
        val engine = TramaiEngine(providerRegistry = registry)
        val service = engine.create<ExplicitProviderService>()

        val result = runBlocking { service.analyze("invoice-123") }

        assertThat(result).isEqualTo("ollama result")
        assertThat(anthropic.requests).isEmpty()
        assertThat(ollama.requests).hasSize(1)
    }

    @Test
    fun `fails clearly when no provider is registered for a model`() {
        val engine = TramaiEngine(
            providerRegistry = ProviderRegistry.builder()
                .provider("anthropic", NamedProvider("anthropic") { ModelResponse(content = "unused") })
                .build(),
        )
        val service = engine.create<BlockingSummarizer>()

        assertThatThrownBy { service.summarize("raw input") }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("No provider is registered for model")
    }
}

@AiService
@SystemPrompt("You are a precise billing assistant.")
private interface SuspendAnalyzer {
    @Operation(
        prompt = "Analyze the invoice and return a raw summary",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun analyze(invoiceId: String): String
}

@AiService
private interface BlockingSummarizer {
    @Operation(
        prompt = "Summarize the raw input",
        model = "claude-sonnet-4-20250514",
    )
    fun summarize(rawInput: String): String
}

@AiService
private interface ExplicitProviderService {
    @Operation(
        prompt = "Analyze with an explicitly selected provider",
        model = "claude-sonnet-4-20250514",
        provider = "ollama",
    )
    suspend fun analyze(invoiceId: String): String
}

@AiService
private interface TimeoutRetryService {
    @Operation(
        prompt = "Analyze with a tight timeout",
        model = "claude-sonnet-4-20250514",
        providerRetries = 1,
        timeoutMillis = 10,
    )
    suspend fun analyze(invoiceId: String): String
}

@AiService
private interface AlwaysTimeoutService {
    @Operation(
        prompt = "Analyze with a tight timeout",
        model = "claude-sonnet-4-20250514",
        providerRetries = 1,
        timeoutMillis = 10,
    )
    suspend fun analyze(invoiceId: String): String
}

@AiService
private interface InvalidTimeoutService {
    @Operation(
        prompt = "Invalid timeout",
        model = "claude-sonnet-4-20250514",
        timeoutMillis = 0,
    )
    suspend fun analyze(invoiceId: String): String
}

@AiService
private interface SuspendNotifier {
    @Operation(
        prompt = "Send a notification",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun notify(payload: String)
}

@AiService
private interface StructuredStatusService {
    @Operation(
        prompt = "Return a structured status",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun status(tenantId: String): StatusResult
}

private interface NotAnAiService {
    @Operation(
        prompt = "Unused",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun invoke(value: String): String
}

@AiService
private interface MissingOperationAnnotation {
    suspend fun broken(value: String): String
}

private data class StatusResult(
    val status: String,
)

private class RecordingProvider(
    private val responder: suspend (ModelRequest) -> ModelResponse,
) : ModelProvider {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return responder(request)
    }
}

private class FailingProvider : ModelProvider {
    override suspend fun complete(request: ModelRequest): ModelResponse {
        error("boom")
    }
}

private class NamedProvider(
    private val name: String,
    private val responder: suspend (ModelRequest) -> ModelResponse,
) : ModelProvider {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return responder(request)
    }

    override fun providerId(): String = name
}

private class SequencedProvider(
    vararg responses: ModelResponse,
) : ModelProvider {
    private val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return queue.removeFirstOrNull() ?: error("No more queued responses")
    }
}

private class SequenceResponder(
    private vararg val steps: suspend () -> ModelResponse,
) {
    private var index = 0

    suspend fun next(@Suppress("UNUSED_PARAMETER") request: ModelRequest): ModelResponse {
        val step = steps.getOrNull(index) ?: error("No more queued responses")
        index += 1
        return step()
    }
}
