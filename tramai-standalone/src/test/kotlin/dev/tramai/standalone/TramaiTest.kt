package dev.tramai.standalone

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.TokenBudgetExceededException
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.TramaiTool
import dev.tramai.core.provider.ModelProvider
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.InMemoryOperationResponseCache
import dev.tramai.engine.TokenBudgetSettings
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.reflect.KClass
import kotlin.test.Test

class TramaiTest {

    @Test
    fun `kotlin builder creates suspend service`() {
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "hello") }

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
        }
        val service = tramai.create<SuspendService>()

        val result = runBlocking { service.respond("world") }

        assertThat(result).isEqualTo("hello")
        assertThat(provider.requests).hasSize(1)
    }

    @Test
    fun `builder supports structured return types`() {
        val provider = RecordingProvider("anthropic") {
            ModelResponse(content = """{"status":"ok"}""")
        }

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
        }
        val service = tramai.create<StructuredService>()

        val result = runBlocking { service.status("tenant-a") }

        assertThat(result).isEqualTo(Status("ok"))
    }

    @Test
    fun `java style builder creates blocking service`() {
        val provider = RecordingProvider("ollama") { ModelResponse(content = "blocking result") }

        val tramai = Tramai.builder()
            .provider(provider, default = true)
            .model("llama3.2", "ollama")
            .build()
        val service = tramai.create(BlockingService::class)

        assertThat(service.blocking("input")).isEqualTo("blocking result")
    }

    @Test
    fun `invalid tool payloads become deterministic invalid input messages`() {
        val provider = ToolLoopProvider(
            ModelResponse(
                content = "",
                toolCalls = listOf(ToolCall("call-1", "lookup", """{"missing":"query"}""")),
            ),
            ModelResponse(content = "recovered answer"),
        )
        val tool = RecordingLookupTool()

        val tramai = Tramai {
            provider(provider, default = true)
            model("gpt-5.1-chat-latest", "mock")
            tools(tool)
        }
        val service = tramai.create<ToolService>()

        val result = runBlocking { service.answer("Where is the package?") }

        assertThat(result).isEqualTo("recovered answer")
        assertThat(tool.calls).isEmpty()
        assertThat(provider.requests).hasSize(2)
        assertThat(provider.requests.last().messages.last().role).isEqualTo(MessageRole.TOOL)
        assertThat(provider.requests.last().messages.last().content)
            .contains("Error:")
            .doesNotContain("Permanent error")
    }

    @Test
    fun `idempotent tools retry transient failures inside the engine`() {
        val provider = ToolLoopProvider(
            ModelResponse(
                content = "",
                toolCalls = listOf(ToolCall("call-1", "lookup", """{"query":"order-123"}""")),
            ),
            ModelResponse(content = "tool answer"),
        )
        val tool = RetryingLookupTool()

        val tramai = Tramai {
            provider(provider, default = true)
            model("gpt-5.1-chat-latest", "mock")
            tools(tool)
        }
        val service = tramai.create<ToolService>()

        val result = runBlocking { service.answer("Where is order-123?") }

        assertThat(result).isEqualTo("tool answer")
        assertThat(tool.calls).containsExactly("order-123", "order-123")
        assertThat(tool.attemptNumbers).containsExactly(0, 1)
        assertThat(provider.requests).hasSize(2)
        assertThat(provider.requests.last().messages.last().content).contains("\"value\":\"resolved:order-123\"")
    }

    @Test
    fun `builder supports fallback routing and circuit breaking`() {
        val primary = RecordingProvider("primary") {
            throw dev.tramai.core.exception.ProviderException("service unavailable", statusCode = 503, retryable = true)
        }
        val fallback = RecordingProvider("fallback") { ModelResponse(content = "fallback answer") }

        val tramai = Tramai {
            provider(primary)
            provider(fallback)
            model("claude-sonnet-4-20250514", "primary")
            fallbackModel("claude-sonnet-4-20250514", "llama3.2", "fallback")
            circuitBreaker(
                CircuitBreakerSettings(
                    enabled = true,
                    failureThreshold = 1,
                    openDurationMillis = 1_000,
                ),
            )
        }
        val service = tramai.create<FallbackService>()

        val first = runBlocking { service.answer("invoice-123") }
        val second = runBlocking { service.answer("invoice-456") }

        assertThat(first).isEqualTo("fallback answer")
        assertThat(second).isEqualTo("fallback answer")
        assertThat(primary.requests).hasSize(1)
        assertThat(fallback.requests).hasSize(2)
        assertThat(fallback.requests.first().model).isEqualTo("llama3.2")
    }

    @Test
    fun `builder supports token budget controls`() {
        val provider = RecordingProvider("anthropic") {
            ModelResponse(content = "expensive", inputTokens = 3, outputTokens = 4)
        }

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
            tokenBudget(
                TokenBudgetSettings(
                    hardMaxTokensPerAttempt = 6,
                ),
            )
        }
        val service = tramai.create<SuspendService>()

        assertThatThrownBy { runBlocking { service.respond("world") } }
            .isInstanceOf(TokenBudgetExceededException::class.java)
    }

    @Test
    fun `builder supports response caching`() {
        var calls = 0
        val provider = RecordingProvider("anthropic") {
            calls += 1
            ModelResponse(content = "cached-$calls")
        }

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
            cache(InMemoryOperationResponseCache())
        }
        val service = tramai.create<CacheableSuspendService>()

        val first = runBlocking { service.respond("world") }
        val second = runBlocking { service.respond("world") }

        assertThat(first).isEqualTo("cached-1")
        assertThat(second).isEqualTo("cached-1")
        assertThat(provider.requests).hasSize(1)
    }
}

@AiService
private interface SuspendService {
    @Operation(
        prompt = "Respond with a greeting",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun respond(name: String): String
}

@AiService
private interface CacheableSuspendService {
    @Operation(
        prompt = "Respond with a cached greeting",
        model = "claude-sonnet-4-20250514",
        cacheable = true,
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

@AiService
private interface BlockingService {
    @Operation(
        prompt = "Return a blocking result",
        model = "llama3.2",
    )
    fun blocking(input: String): String
}

@AiService
private interface ToolService {
    @Operation(
        prompt = "Use the lookup tool before answering",
        model = "gpt-5.1-chat-latest",
        tools = ["lookup"],
    )
    suspend fun answer(question: String): String
}

@AiService
private interface FallbackService {
    @Operation(
        prompt = "Answer with fallback routing",
        model = "claude-sonnet-4-20250514",
        providerRetries = 0,
    )
    suspend fun answer(question: String): String
}

private data class Status(
    val status: String,
)

private class RecordingProvider(
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

private data class LookupInput(
    val query: String,
)

private data class LookupResult(
    val value: String,
)

private class RecordingLookupTool : TramaiTool<LookupInput, LookupResult> {
    val calls = mutableListOf<LookupInput>()

    override val name: String = "lookup"
    override val description: String = "Looks up an order"
    override val inputType: KClass<LookupInput> = LookupInput::class

    override suspend fun execute(input: LookupInput, context: ToolExecutionContext): LookupResult {
        calls += input
        return LookupResult("unused")
    }
}

private class RetryingLookupTool : TramaiTool<LookupInput, LookupResult> {
    val calls = mutableListOf<String>()
    val attemptNumbers = mutableListOf<Int>()

    override val name: String = "lookup"
    override val description: String = "Looks up an order"
    override val inputType: KClass<LookupInput> = LookupInput::class
    override val idempotent: Boolean = true

    override suspend fun execute(input: LookupInput, context: ToolExecutionContext): LookupResult {
        calls += input.query
        attemptNumbers += context.attemptNumber
        if (calls.size == 1) {
            error("temporary lookup failure")
        }
        return LookupResult("resolved:${input.query}")
    }
}

private class ToolLoopProvider(
    vararg responses: ModelResponse,
) : ModelProvider {
    private val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return queue.removeFirstOrNull() ?: error("No more queued responses")
    }

    override fun providerId(): String = "mock"
}
