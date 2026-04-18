package io.aurora.engine

import io.aurora.core.annotations.AiService
import io.aurora.core.annotations.Operation
import io.aurora.core.annotations.SystemPrompt
import io.aurora.core.exception.ConfigurationException
import io.aurora.core.exception.ProviderException
import io.aurora.core.exception.StructuredOutputException
import io.aurora.core.model.MessageRole
import io.aurora.core.model.ModelRequest
import io.aurora.core.model.ModelResponse
import io.aurora.core.provider.ModelProvider
import io.aurora.core.provider.ProviderRegistry
import io.aurora.structured.JacksonStructuredOutputHandler
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class AuroraEngineTest {

    @Test
    fun `creates a suspend proxy and returns provider content`() {
        val provider = RecordingProvider { ModelResponse(content = "hardcoded response") }
        val engine = AuroraEngine(provider)
        val service = engine.create<SuspendAnalyzer>()

        val result = runBlocking { service.analyze("invoice-123") }

        assertThat(result).isEqualTo("hardcoded response")
        assertThat(provider.requests).hasSize(1)
        assertThat(provider.requests.single().model).isEqualTo("claude-sonnet-4-20250514")
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
        val engine = AuroraEngine(provider)
        val service = engine.create<BlockingSummarizer>()

        val result = service.summarize("raw input")

        assertThat(result).isEqualTo("summary")
    }

    @Test
    fun `routes unit return types and ignores provider content`() {
        val provider = RecordingProvider { ModelResponse(content = "ignored") }
        val engine = AuroraEngine(provider)
        val service = engine.create<SuspendNotifier>()

        val result = runBlocking { service.notify("hello") }

        assertThat(result).isEqualTo(Unit)
        assertThat(provider.requests.single().messages.last().content)
            .contains("Send a notification")
            .contains("hello")
    }

    @Test
    fun `rejects interfaces without ai service annotation`() {
        val engine = AuroraEngine(RecordingProvider { ModelResponse(content = "unused") })

        assertThatThrownBy { engine.create<NotAnAiService>() }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("@AiService")
    }

    @Test
    fun `rejects methods without operation annotation`() {
        val engine = AuroraEngine(RecordingProvider { ModelResponse(content = "unused") })

        assertThatThrownBy { engine.create<MissingOperationAnnotation>() }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("@Operation")
    }

    @Test
    fun `rejects structured return types before aurora structured exists`() {
        val engine = AuroraEngine(RecordingProvider { ModelResponse(content = """{"status":"ok"}""") })
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
        val engine = AuroraEngine(
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
        val engine = AuroraEngine(
            provider = provider,
            structuredOutputHandler = JacksonStructuredOutputHandler(),
        )
        val service = engine.create<StructuredStatusService>()

        assertThatThrownBy { runBlocking { service.status("tenant-a") } }
            .isInstanceOf(StructuredOutputException::class.java)
            .hasMessageContaining("failed after 3 attempt")
    }

    @Test
    fun `wraps unexpected provider errors with provider exception`() {
        val engine = AuroraEngine(FailingProvider())
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
        val engine = AuroraEngine(providerRegistry = registry)
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
        val engine = AuroraEngine(providerRegistry = registry)
        val service = engine.create<ExplicitProviderService>()

        val result = runBlocking { service.analyze("invoice-123") }

        assertThat(result).isEqualTo("ollama result")
        assertThat(anthropic.requests).isEmpty()
        assertThat(ollama.requests).hasSize(1)
    }

    @Test
    fun `fails clearly when no provider is registered for a model`() {
        val engine = AuroraEngine(
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
