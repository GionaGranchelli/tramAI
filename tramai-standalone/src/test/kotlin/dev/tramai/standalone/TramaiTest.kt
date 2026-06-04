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
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedactionAuditEmitter
import dev.tramai.core.security.DlpResult
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.core.policy.NoOpPolicyDecisionAuditEmitter
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
    fun `builder injects provider from registry`() {
        val provider = RecordingProvider("claude") { ModelResponse(content = "yes") }
        val tramai = Tramai {
            provider(provider, name = "claude", default = true)
            model("claude-sonnet-4-20250514", "claude")
        }
        val service = tramai.create<SuspendService>()
        val result = runBlocking { service.respond("hi") }
        assertThat(result).isEqualTo("yes")
    }

    @Test
    fun `builder default provider`() {
        val a = RecordingProvider("a") { ModelResponse(content = "from-a") }
        val b = RecordingProvider("b") { ModelResponse(content = "from-b") }

        val tramai = Tramai {
            provider(a, name = "a")
            provider(b, name = "b", default = true)
            model("m", "a")
            model("m", "b")
        }
        val service = tramai.create<SuspendService>()
        val result = runBlocking { service.respond("hi") }
        assertThat(result).isEqualTo("from-b")
    }

    @Test
    fun `builder supports tool loop`() {
        val lookup = RecordingTool()
        val provider = ToolLoopProvider(
            ModelResponse(
                content = "Let me check.",
                toolCalls = listOf(
                    ToolCall(
                        id = "call-1",
                        name = "lookup",
                        argumentsJson = """{"query": "order-42"}""",
                    ),
                ),
            ),
            ModelResponse(content = "Found it!"),
        )

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "mock")
            tools(lookup)
        }
        val service = tramai.create<ToolService>()
        val result = runBlocking { service.respond("hi") }
        assertThat(result).isEqualTo("Found it!")
        assertThat(lookup.calls).containsExactly("order-42")
    }

    @Test
    fun `tool loop supports second retry failure`() {
        val lookup = RetryingTool()
        val provider = ToolLoopProvider(
            ModelResponse(
                content = "Let me check.",
                toolCalls = listOf(
                    ToolCall(
                        id = "call-1",
                        name = "lookup",
                        argumentsJson = """{"query": "order-42"}""",
                    ),
                ),
            ),
            ModelResponse(
                content = "Let me re-check.",
                toolCalls = listOf(
                    ToolCall(
                        id = "call-2",
                        name = "lookup",
                        argumentsJson = """{"query": "order-43"}""",
                    ),
                ),
            ),
            ModelResponse(content = "Finally!"),
        )

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "mock")
            tools(lookup)
        }
        val service = tramai.create<ToolService>()
        val result = runBlocking { service.respond("hi") }
        assertThat(result).isEqualTo("Finally!")
        assertThat(lookup.calls).containsExactly("order-42", "order-43")
        assertThat(lookup.attemptNumbers).containsExactly(1, 1)
    }

    @Test
    fun `tool loop exposes tool to provider`() {
        val lookup = RecordingTool()
        val provider = ToolLoopProvider(
            ModelResponse(content = "Let me check.",
                toolCalls = listOf(
                    ToolCall(
                        id = "call-1",
                        name = "lookup",
                        argumentsJson = """{"query": "order-42"}""",
                    ),
                ),
            ),
            ModelResponse(content = "Done!"),
        )

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "mock")
            tools(lookup)
        }
        val service = tramai.create<ToolService>()
        runBlocking { service.respond("check order 42") }

        // The second request should have tool definitions matching the exposed tools
        assertThat(provider.requests).hasSize(2)
        val firstRequest = provider.requests[0]
        // tool loop request: tool should be exposed
        assertThat(firstRequest.tools).isNotEmpty()
        // tools should have a definition matching the lookup tool
        val lookupDef = firstRequest.tools!!.find { it.name == "lookup" }
        assertThat(lookupDef).isNotNull()
        assertThat(lookupDef!!.description).isEqualTo("Looks up an order")
        assertThat(lookupDef.inputSchemaJson).isNotNull()
    }

    @Test
    fun `builder supports DLP interceptor`() {
        val redactingInterceptor = object : DlpInterceptor {
            override fun inspect(context: DlpContext, text: String): DlpResult {
                return DlpResult(
                    sanitizedText = text.replace("secret", "[REDACTED]"),
                    redactions = listOf(
                        dev.tramai.core.security.DlpRedaction(
                            ruleId = "redact-secret",
                            replacementCount = 1,
                        ),
                    ),
                )
            }
        }

        val provider = RecordingProvider("anthropic") { ModelResponse(content = "this is a secret") }
        val observer = ResponseRecordingObserver()

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
            dlp(redactingInterceptor)
            observer(observer)
        }
        val service = tramai.create<SuspendService>()
        val result = runBlocking { service.respond("anything") }

        assertThat(result).isEqualTo("this is a [REDACTED]")
        // OperationObserver sees sanitized output
        assertThat(observer.responses.first()).isEqualTo("this is a [REDACTED]")
    }

    @Test
    fun `builder supports DLP redaction audit emitter for model output`() {
        val auditCalls = mutableListOf<Pair<String, Int>>()
        val redactingInterceptor = object : DlpInterceptor {
            override fun inspect(context: DlpContext, text: String): DlpResult {
                return DlpResult(
                    sanitizedText = text.replace("secret", "[REDACTED]"),
                    redactions = listOf(dev.tramai.core.security.DlpRedaction("redact-secret", 1)),
                )
            }
        }
        val auditEmitter = DlpRedactionAuditEmitter { context, redactions ->
            auditCalls += context.contentType.name to redactions.size
        }
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "this is a secret") }

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
            dlp(redactingInterceptor)
            dlpRedactionAudit(auditEmitter)
        }
        val service = tramai.create<SuspendService>()

        val result = runBlocking { service.respond("anything") }

        assertThat(result).isEqualTo("this is a [REDACTED]")
        assertThat(auditCalls).containsExactly("MODEL_OUTPUT" to 1)
    }

    @Test
    fun `builder propagates DLP redaction audit emitter failures`() {
        val redactingInterceptor = object : DlpInterceptor {
            override fun inspect(context: DlpContext, text: String): DlpResult {
                return DlpResult(
                    sanitizedText = text.replace("secret", "[REDACTED]"),
                    redactions = listOf(dev.tramai.core.security.DlpRedaction("redact-secret", 1)),
                )
            }
        }
        val auditEmitter = DlpRedactionAuditEmitter { _, _ ->
            throw RuntimeException("audit bridge failed")
        }
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "this is a secret") }

        assertThatThrownBy {
            val tramai = Tramai {
                provider(provider, default = true)
                model("claude-sonnet-4-20250514", "anthropic")
                dlp(redactingInterceptor)
                dlpRedactionAudit(auditEmitter)
            }
            val service = tramai.create<SuspendService>()
            runBlocking { service.respond("anything") }
        }
            .isInstanceOf(dev.tramai.core.security.DlpInspectionException::class.java)
    }

    @Test
    fun `builder supports cache`() {
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "cached-response") }

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
            cache(InMemoryOperationResponseCache(maxEntries = 100))
        }
        val service = tramai.create<SuspendService>()

        val first = runBlocking { service.respond("hello") }
        val second = runBlocking { service.respond("hello") }

        assertThat(first).isEqualTo("cached-response")
        assertThat(second).isEqualTo("cached-response")
        // provider should have been called only once (second served from cache)
        assertThat(provider.requests).hasSize(1)
    }

    @Test
    fun `builder supports circuit breaker`() {
        val tramai = Tramai {
            circuitBreaker(CircuitBreakerSettings(enabled = false))
        }
        assertThat(tramai).isNotNull
    }

    @Test
    fun `builder supports token budget`() {
        val tramai = Tramai {
            tokenBudget(TokenBudgetSettings(hardMaxTokensPerAttempt = 99))
        }
        assertThat(tramai).isNotNull
    }

    @Test
    fun `builder supports tool result filtering`() {
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "hello") }
        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
        }
        val service = tramai.create<SuspendService>()
        val result = runBlocking { service.respond("hi") }
        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `builder supports engine event observer`() {
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "hello") }
        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
        }
        val service = tramai.create<SuspendService>()
        val result = runBlocking { service.respond("hi") }
        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `default builder uses no-op audit emitter`() {
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "hello") }
        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
        }
        val service = tramai.create<SuspendService>()
        val result = runBlocking { service.respond("hi") }
        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `builder with custom audit emitter receives runtime policy events`() {
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "hello") }
        val events = mutableListOf<String>()
        val emitter = PolicyDecisionAuditEmitter { _, _, _ ->
            events.add("emitted")
        }

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
            policyDecisionAudit(emitter)
        }
        val service = tramai.create<SuspendService>()
        val result = runBlocking { service.respond("hi") }
        // With legacy permissive engine, each enforcement point produces an ALLOW
        assertThat(result).isEqualTo("hello")
        assertThat(events).isNotEmpty()
    }

    @Test
    fun `builder with custom PolicyEngine enforces decisions`() {
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "hello") }
        val denyingEngine = PolicyEngine { _ ->
            PolicyDecision.Deny("always deny", "always-deny")
        }

        assertThatThrownBy {
            val tramai = Tramai {
                provider(provider, default = true)
                model("claude-sonnet-4-20250514", "anthropic")
                policyEngine(denyingEngine)
            }
            val service = tramai.create<SuspendService>()
            runBlocking { service.respond("hi") }
        }.isInstanceOf(dev.tramai.core.exception.PolicyViolationException::class.java)
    }

    @Test
    fun `builder with custom PolicyEngine and audit emitter records deny events`() {
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "hello") }
        val denyingEngine = PolicyEngine { _ ->
            PolicyDecision.Deny("always deny", "always-deny")
        }
        val auditEvents = mutableListOf<String>()
        val emitter = PolicyDecisionAuditEmitter { _, _, decision ->
            when (decision) {
                is PolicyDecision.Deny -> auditEvents.add("DENY:${decision.reasonCode}")
                else -> auditEvents.add("ALLOW")
            }
        }

        assertThatThrownBy {
            val tramai = Tramai {
                provider(provider, default = true)
                model("claude-sonnet-4-20250514", "anthropic")
                policyEngine(denyingEngine)
                policyDecisionAudit(emitter)
            }
            val service = tramai.create<SuspendService>()
            runBlocking { service.respond("hi") }
        }.isInstanceOf(dev.tramai.core.exception.PolicyViolationException::class.java)

        // Audit events should have been emitted before the exception
        assertThat(auditEvents).isNotEmpty()
        assertThat(auditEvents).allMatch { it.startsWith("DENY:") }
    }

    @Test
    fun `empty tools exposes zero tools`() {
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "hello") }

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
        }
        val service = tramai.create<SuspendService>()
        runBlocking { service.respond("hi") }

        assertThat(provider.requests).hasSize(1)
        assertThat(provider.requests[0].tools).isNullOrEmpty()
    }

    @Test
    fun `explicitly listed tool is exposed`() {
        val lookup = RecordingTool()
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "hello") }

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
            tools(lookup)
        }
        val service = tramai.create<ToolService>()
        runBlocking { service.respond("hi") }

        assertThat(provider.requests).hasSize(1)
        val toolDefs = provider.requests[0].tools
        assertThat(toolDefs).isNotEmpty()
        val lookupDef = toolDefs!!.find { it.name == "lookup" }
        assertThat(lookupDef).isNotNull()
        assertThat(lookupDef!!.description).isEqualTo("Looks up an order")
    }

    @Test
    fun `unlisted registered tool is not exposed`() {
        val lookup = RecordingTool()
        val otherTool = object : TramaiTool<String, String> {
            override val name: String = "other"
            override val description: String = "Another tool"
            override val inputType: KClass<String> = String::class
            override val idempotent: Boolean = true
            override suspend fun execute(input: String, context: ToolExecutionContext): String = "result"
        }
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "hello") }

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
            tools(lookup, otherTool)
        }
        val service = tramai.create<ToolService>()
        runBlocking { service.respond("hi") }

        assertThat(provider.requests).hasSize(1)
        val toolDefs = provider.requests[0].tools
        assertThat(toolDefs).isNotEmpty()
        // Only "lookup" should be exposed, not "other"
        assertThat(toolDefs!!.map { it.name }).containsExactly("lookup")
    }
}

@AiService
interface SuspendService {
    @Operation(model = "claude-sonnet-4-20250514", cacheable = true)
    suspend fun respond(input: String): String
}

@AiService
interface ToolService {
    @Operation(model = "claude-sonnet-4-20250514", tools = ["lookup"])
    suspend fun respond(input: String): String
}

@AiService
interface StructuredService {
    @Operation(model = "claude-sonnet-4-20250514")
    suspend fun status(tenant: String): Status
}

data class Status(val status: String)

private class RecordingProvider(
    private val id: String,
    private val response: suspend () -> ModelResponse,
) : ModelProvider {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return response()
    }

    override fun providerId(): String = id
}

private class LookupInput(val query: String)
private class LookupResult(val resolved: String)

private class RecordingTool : TramaiTool<LookupInput, LookupResult> {
    val calls = mutableListOf<String>()

    override val name: String = "lookup"
    override val description: String = "Looks up an order"
    override val inputType: KClass<LookupInput> = LookupInput::class
    override val idempotent: Boolean = true

    override suspend fun execute(input: LookupInput, context: ToolExecutionContext): LookupResult {
        calls += input.query
        return LookupResult("resolved:${input.query}")
    }
}

private class RetryingTool : TramaiTool<LookupInput, LookupResult> {
    val calls = mutableListOf<String>()
    val attemptNumbers = mutableListOf<Int>()
    private var executeCount = 0

    override val name: String = "lookup"
    override val description: String = "Looks up an order"
    override val inputType: KClass<LookupInput> = LookupInput::class
    override val idempotent: Boolean = true

    override suspend fun execute(input: LookupInput, context: ToolExecutionContext): LookupResult {
        executeCount++
        if (executeCount % 2 == 1) {
            error("temporary lookup failure")
        }
        calls += input.query
        attemptNumbers += context.attemptNumber
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

private class ResponseRecordingObserver : OperationObserver {
    val responses = mutableListOf<String>()

    override fun onCallStarted(context: OperationCallContext): OperationObservation = object : OperationObservation {
        override fun onProviderResponse(response: ModelResponse) {
            responses += response.content
        }

        override fun onProviderFailure(error: Throwable) = Unit

        override fun onStructuredParseFailure(
            rawResponse: String,
            errorSummary: String,
        ) = Unit

        override fun onCallCompleted(parseSuccess: Boolean?) = Unit
    }
}
