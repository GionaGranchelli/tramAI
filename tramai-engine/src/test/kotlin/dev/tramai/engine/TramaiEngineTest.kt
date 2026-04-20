package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.SystemPrompt
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.ProviderCapabilityException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.StructuredOutputException
import dev.tramai.core.exception.TokenBudgetExceededException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolResult
import dev.tramai.core.model.UsageMetrics
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.provider.StreamCapable
import dev.tramai.structured.JacksonStructuredOutputHandler
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
    fun `honors retry after hints when retrying provider failures`() {
        val callTimes = mutableListOf<Long>()
        val observer = RecordingObserver()
        val provider = RecordingProvider {
            callTimes += System.nanoTime()
            if (callTimes.size == 1) {
                throw ProviderException(
                    message = "rate limited",
                    statusCode = 429,
                    retryable = true,
                    retryAfterMillis = 40,
                )
            }
            ModelResponse(content = "recovered")
        }
        val engine = TramaiEngine(
            provider = provider,
            operationObserver = observer,
            retryPolicySettings = RetryPolicySettings(
                maxRetryAfterMillis = 100,
                jitterRatio = 0.0,
            ),
        )
        val service = engine.create<BlockingSummarizer>()

        val result = service.summarize("raw input")

        assertThat(result).isEqualTo("recovered")
        assertThat(provider.requests).hasSize(2)
        val elapsedMillis = (callTimes[1] - callTimes[0]) / 1_000_000
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(35)
        assertThat(observer.records.flatMap { it.engineEvents.map(EngineEventRecord::name) })
            .contains("tramai.retry.scheduled")
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

    @Test
    fun `falls back to the next configured route after retryable primary failure`() {
        val observer = RecordingObserver()
        val primary = NamedProvider("primary") {
            throw ProviderException("rate limited", statusCode = 429, retryable = true)
        }
        val fallback = NamedProvider("fallback") { ModelResponse(content = "fallback result") }
        val registry = ProviderRegistry.builder()
            .provider("primary", primary)
            .provider("fallback", fallback)
            .model("claude-sonnet-4-20250514", "primary")
            .fallbackModel("claude-sonnet-4-20250514", "llama3.2", "fallback")
            .build()
        val engine = TramaiEngine(providerRegistry = registry, operationObserver = observer)
        val service = engine.create<FallbackService>()

        val result = runBlocking { service.analyze("invoice-123") }

        assertThat(result).isEqualTo("fallback result")
        assertThat(primary.requests).hasSize(1)
        assertThat(fallback.requests).hasSize(1)
        assertThat(fallback.requests.single().model).isEqualTo("llama3.2")
        assertThat(observer.records)
            .anySatisfy { record ->
                assertThat(record.context.providerId).isEqualTo("fallback")
                assertThat(record.engineEvents).anySatisfy { event ->
                    assertThat(event.name).isEqualTo("tramai.route.selected")
                    assertThat(event.attributes["is_fallback"]).isEqualTo(true)
                }
            }
    }

    @Test
    fun `circuit breaker skips an unhealthy primary route until the open window expires`() {
        val observer = RecordingObserver()
        val primary = NamedProvider("primary") {
            throw ProviderException("service unavailable", statusCode = 503, retryable = true)
        }
        val fallback = NamedProvider("fallback") { ModelResponse(content = "fallback result") }
        val registry = ProviderRegistry.builder()
            .provider("primary", primary)
            .provider("fallback", fallback)
            .model("claude-sonnet-4-20250514", "primary")
            .fallbackProvider("claude-sonnet-4-20250514", "fallback")
            .build()
        val engine = TramaiEngine(
            providerRegistry = registry,
            operationObserver = observer,
            circuitBreakerSettings = CircuitBreakerSettings(
                enabled = true,
                failureThreshold = 1,
                openDurationMillis = 40,
            ),
        )
        val service = engine.create<FallbackService>()

        val first = runBlocking { service.analyze("invoice-123") }
        val second = runBlocking { service.analyze("invoice-456") }
        Thread.sleep(55)
        val third = runBlocking { service.analyze("invoice-789") }

        assertThat(first).isEqualTo("fallback result")
        assertThat(second).isEqualTo("fallback result")
        assertThat(third).isEqualTo("fallback result")
        assertThat(primary.requests).hasSize(2)
        assertThat(fallback.requests).hasSize(3)
        assertThat(observer.records.flatMap { it.engineEvents.map(EngineEventRecord::name) })
            .contains("tramai.circuit.opened")
    }

    @Test
    fun `streaming falls back before first token when the primary startup fails`() {
        val primary = NamedStreamingProvider("primary") {
            flow {
                emit(StreamChunk.Error(ProviderException("rate limited", statusCode = 429, retryable = true)))
            }
        }
        val fallback = NamedStreamingProvider("fallback") {
            flow {
                emit(StreamChunk.Token("ok"))
                emit(StreamChunk.Complete("ok", UsageMetrics(outputTokens = 1)))
            }
        }
        val registry = ProviderRegistry.builder()
            .provider("primary", primary)
            .provider("fallback", fallback)
            .model("claude-sonnet-4-20250514", "primary")
            .fallbackProvider("claude-sonnet-4-20250514", "fallback")
            .build()
        val observer = RecordingObserver()
        val engine = TramaiEngine(providerRegistry = registry, operationObserver = observer)
        val service = engine.create<StreamingService>()

        val chunks = runBlocking { service.stream("invoice-123").toList() }

        assertThat(chunks).containsExactly(
            StreamChunk.Token("ok"),
            StreamChunk.Complete("ok", UsageMetrics(outputTokens = 1)),
        )
        assertThat(primary.streamRequests).hasSize(1)
        assertThat(fallback.streamRequests).hasSize(1)
        assertThat(observer.records.flatMap { it.engineEvents.map(EngineEventRecord::name) })
            .contains("tramai.streaming.startup_retry")
    }

    @Test
    fun `streaming operations emit chunks and record terminal completion`() {
        val observer = RecordingObserver()
        val provider = StreamingProvider {
            flow {
                emit(StreamChunk.Token("hel"))
                emit(StreamChunk.Token("lo"))
                emit(StreamChunk.Complete("hello", UsageMetrics(inputTokens = 4, outputTokens = 2)))
            }
        }
        val engine = TramaiEngine(provider = provider, operationObserver = observer)
        val service = engine.create<StreamingService>()

        val chunks = runBlocking { service.stream("invoice-123").toList() }

        assertThat(chunks).containsExactly(
            StreamChunk.Token("hel"),
            StreamChunk.Token("lo"),
            StreamChunk.Complete("hello", UsageMetrics(inputTokens = 4, outputTokens = 2)),
        )
        val record = observer.records.single()
        assertThat(record.context.methodName).isEqualTo("stream")
        assertThat(record.response?.content).isEqualTo("hello")
        assertThat(record.response?.inputTokens).isEqualTo(4)
        assertThat(record.response?.outputTokens).isEqualTo(2)
        assertThat(record.providerFailure).isNull()
        assertThat(record.parseSuccess).isNull()
    }

    @Test
    fun `fails when a provider attempt exceeds the hard token budget`() {
        val provider = RecordingProvider {
            ModelResponse(content = "too expensive", inputTokens = 4, outputTokens = 3)
        }
        val engine = TramaiEngine(
            provider = provider,
            tokenBudgetSettings = TokenBudgetSettings(
                hardMaxTokensPerAttempt = 6,
            ),
        )
        val service = engine.create<BlockingSummarizer>()

        assertThatThrownBy { service.summarize("raw input") }
            .isInstanceOfSatisfying(TokenBudgetExceededException::class.java) { error ->
                assertThat(error.scope).isEqualTo("attempt")
                assertThat(error.limitTokens).isEqualTo(6)
                assertThat(error.observedTokens).isEqualTo(7)
            }
    }

    @Test
    fun `fails when cumulative operation tokens exceed the hard operation budget`() {
        val provider = SequencedProvider(
            ModelResponse(content = "not json", inputTokens = 2, outputTokens = 2),
            ModelResponse(content = """{"status":"ok"}""", inputTokens = 2, outputTokens = 2),
        )
        val engine = TramaiEngine(
            provider = provider,
            structuredOutputHandler = JacksonStructuredOutputHandler(),
            tokenBudgetSettings = TokenBudgetSettings(
                hardMaxTokensPerOperation = 6,
            ),
        )
        val service = engine.create<StructuredStatusService>()

        assertThatThrownBy { runBlocking { service.status("tenant-a") } }
            .isInstanceOfSatisfying(TokenBudgetExceededException::class.java) { error ->
                assertThat(error.scope).isEqualTo("operation")
                assertThat(error.limitTokens).isEqualTo(6)
                assertThat(error.observedTokens).isEqualTo(8)
            }
    }

    @Test
    fun `soft token budget emits an engine event without failing the call`() {
        val observer = RecordingObserver()
        val provider = RecordingProvider {
            ModelResponse(content = "answer", inputTokens = 2, outputTokens = 3)
        }
        val engine = TramaiEngine(
            provider = provider,
            operationObserver = observer,
            tokenBudgetSettings = TokenBudgetSettings(
                softMaxTokensPerOperation = 4,
            ),
        )
        val service = engine.create<BlockingSummarizer>()

        val result = service.summarize("raw input")

        assertThat(result).isEqualTo("answer")
        assertThat(observer.records.flatMap { it.engineEvents.map(EngineEventRecord::name) })
            .contains("tramai.token_budget.soft_limit_exceeded")
    }

    @Test
    fun `returns cached raw result for cacheable operations`() {
        var calls = 0
        val provider = RecordingProvider {
            calls += 1
            ModelResponse(content = "cached-$calls")
        }
        val engine = TramaiEngine(
            provider = provider,
            responseCache = InMemoryOperationResponseCache(),
        )
        val service = engine.create<CacheableRawService>()

        val first = service.cached("tenant-a")
        val second = service.cached("tenant-a")

        assertThat(first).isEqualTo("cached-1")
        assertThat(second).isEqualTo("cached-1")
        assertThat(provider.requests).hasSize(1)
    }

    @Test
    fun `cache ttl expiry triggers a fresh provider call`() {
        val provider = RecordingProvider(
            SequenceResponder(
                suspend { ModelResponse(content = "first") },
                suspend { ModelResponse(content = "second") },
            )::next,
        )
        val engine = TramaiEngine(
            provider = provider,
            responseCache = InMemoryOperationResponseCache(),
        )
        val service = engine.create<ShortLivedCacheService>()

        val first = service.cached("tenant-a")
        Thread.sleep(20)
        val second = service.cached("tenant-a")

        assertThat(first).isEqualTo("first")
        assertThat(second).isEqualTo("second")
        assertThat(provider.requests).hasSize(2)
    }

    @Test
    fun `caching is skipped for tool-enabled operations`() {
        val provider = ToolCallingRecordingProvider(
            ModelResponse(
                content = "",
                toolCalls = listOf(ToolCall("tool-1", "lookup", """{"query":"tenant-a"}""")),
            ),
            ModelResponse(content = "tool answer"),
            ModelResponse(
                content = "",
                toolCalls = listOf(ToolCall("tool-2", "lookup", """{"query":"tenant-a"}""")),
            ),
            ModelResponse(content = "tool answer"),
        )
        val tool = object : ResolvedTool {
            override val name: String = "lookup"
            override val description: String = "Looks up a tenant"
            override val inputSchemaJson: String = """{"type":"object"}"""
            override val idempotent: Boolean = false
            override val sideEffectLevel: dev.tramai.core.model.SideEffectLevel =
                dev.tramai.core.model.SideEffectLevel.READ_ONLY

            override suspend fun execute(
                input: Any,
                context: ToolExecutionContext,
            ): ToolResult = ToolResult.Success(
                """{"value":"resolved"}""",
            )
        }
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf(tool.name to tool)),
            responseCache = InMemoryOperationResponseCache(),
        )
        val service = engine.create<CacheableToolService>()

        val first = runBlocking { service.answer("tenant-a") }
        val second = runBlocking { service.answer("tenant-a") }

        assertThat(first).isEqualTo("tool answer")
        assertThat(second).isEqualTo("tool answer")
        assertThat(provider.requests).hasSize(4)
    }

    @Test
    fun `streaming returns a terminal error when token budget is exceeded on completion`() {
        val provider = StreamingProvider {
            flow {
                emit(StreamChunk.Token("hi"))
                emit(StreamChunk.Complete("hi", UsageMetrics(inputTokens = 2, outputTokens = 4)))
            }
        }
        val engine = TramaiEngine(
            provider = provider,
            tokenBudgetSettings = TokenBudgetSettings(
                hardMaxTokensPerAttempt = 5,
            ),
        )
        val service = engine.create<StreamingService>()

        val chunks = runBlocking { service.stream("invoice-123").toList() }

        assertThat(chunks).hasSize(2)
        assertThat(chunks.first()).isEqualTo(StreamChunk.Token("hi"))
        assertThat(chunks.last()).isInstanceOfSatisfying(StreamChunk.Error::class.java) { errorChunk ->
            assertThat(errorChunk.cause).isInstanceOf(TokenBudgetExceededException::class.java)
        }
    }

    @Test
    fun `streaming cancellation propagates to the provider and is observed`() {
        val observer = RecordingObserver()
        val provider = StreamingProvider {
            flow {
                try {
                    emit(StreamChunk.Token("first"))
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val engine = TramaiEngine(provider = provider, operationObserver = observer)
        val service = engine.create<StreamingService>()

        val chunks = runBlocking { service.stream("invoice-123").take(1).toList() }

        assertThat(chunks).containsExactly(StreamChunk.Token("first"))
        assertThat(provider.cancelled).isTrue()
        val record = observer.records.single()
        assertThat(record.providerFailure).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        assertThat(record.response).isNull()
    }

    @Test
    fun `rejects streaming operations when the provider does not support streaming`() {
        val engine = TramaiEngine(RecordingProvider { ModelResponse(content = "unused") })
        val service = engine.create<StreamingService>()

        assertThatThrownBy { runBlocking { service.stream("invoice-123").toList() } }
            .isInstanceOf(ProviderCapabilityException::class.java)
            .hasMessageContaining("does not support streaming")
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

@AiService
private interface StreamingService {
    @Operation(
        prompt = "Stream a response",
        model = "claude-sonnet-4-20250514",
    )
    fun stream(invoiceId: String): Flow<StreamChunk>
}

@AiService
private interface CacheableRawService {
    @Operation(
        prompt = "Return a cached answer",
        model = "claude-sonnet-4-20250514",
        cacheable = true,
    )
    fun cached(tenantId: String): String
}

@AiService
private interface ShortLivedCacheService {
    @Operation(
        prompt = "Return a short lived cached answer",
        model = "claude-sonnet-4-20250514",
        cacheable = true,
        cacheTtlMillis = 10,
    )
    fun cached(tenantId: String): String
}

@AiService
private interface CacheableToolService {
    @Operation(
        prompt = "Use the lookup tool",
        model = "claude-sonnet-4-20250514",
        tools = ["lookup"],
        cacheable = true,
    )
    suspend fun answer(question: String): String
}

@AiService
private interface FallbackService {
    @Operation(
        prompt = "Analyze with fallback routing",
        model = "claude-sonnet-4-20250514",
        providerRetries = 0,
    )
    suspend fun analyze(invoiceId: String): String
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

private class StreamingProvider(
    private val streamResponder: StreamingProvider.(ModelRequest) -> Flow<StreamChunk>,
) : ModelProvider, StreamCapable {
    var cancelled: Boolean = false

    override suspend fun complete(request: ModelRequest): ModelResponse {
        error("StreamingProvider.complete should not be used in this test")
    }

    override suspend fun stream(request: ModelRequest): Flow<StreamChunk> = streamResponder(request)
}

private class ToolCallingRecordingProvider(
    vararg responses: ModelResponse,
) : ModelProvider {
    private val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return queue.removeFirstOrNull() ?: error("No more queued responses")
    }
}

private class NamedStreamingProvider(
    private val name: String,
    private val streamResponder: NamedStreamingProvider.(ModelRequest) -> Flow<StreamChunk>,
) : ModelProvider, StreamCapable {
    val streamRequests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        error("NamedStreamingProvider.complete should not be used in this test")
    }

    override suspend fun stream(request: ModelRequest): Flow<StreamChunk> {
        streamRequests += request
        return streamResponder(request)
    }

    override fun providerId(): String = name
}

private class RecordingObserver : OperationObserver {
    val records = mutableListOf<Record>()

    override fun onCallStarted(context: OperationCallContext): OperationObservation {
        val record = Record(context = context)
        records += record
        return object : OperationObservation {
            override fun onProviderResponse(response: ModelResponse) {
                record.response = response
            }

            override fun onProviderFailure(error: Throwable) {
                record.providerFailure = error
            }

            override fun onStructuredParseFailure(
                rawResponse: String,
                errorSummary: String,
            ) = Unit

            override fun onEngineEvent(
                name: String,
                attributes: Map<String, Any?>,
            ) {
                record.engineEvents += EngineEventRecord(name, attributes)
            }

            override fun onCallCompleted(parseSuccess: Boolean?) {
                record.parseSuccess = parseSuccess
            }
        }
    }

    data class Record(
        val context: OperationCallContext,
        var response: ModelResponse? = null,
        var providerFailure: Throwable? = null,
        var parseSuccess: Boolean? = null,
        val engineEvents: MutableList<EngineEventRecord> = mutableListOf(),
    )
}

private data class EngineEventRecord(
    val name: String,
    val attributes: Map<String, Any?>,
)
