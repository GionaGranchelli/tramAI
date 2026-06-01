package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ApprovalRequiredException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolResult
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.provider.StreamCapable
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.core.structured.StructuredOutputResult
import dev.tramai.core.structured.StructuredOutputContract
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

class PolicyEnforcementTest {

    // -- Test providers --------------------------------------------------------

    private class CountingProvider(
        val id: String = "test-provider",
        private val content: String = "ok",
    ) : ModelProvider {
        val callCount = AtomicInteger(0)
        override fun providerId() = id
        override suspend fun complete(request: ModelRequest): ModelResponse {
            callCount.incrementAndGet()
            return ModelResponse(
                content = content,
                inputTokens = 10,
                outputTokens = 5,
                modelUsed = request.model,
            )
        }
    }

    private fun providerThatReturns(content: String) = CountingProvider(content = content)

    private fun providerWithToolCall(toolName: String) = object : ModelProvider {
        val callCount = AtomicInteger(0)
        override fun providerId() = "test-provider"
        override suspend fun complete(request: ModelRequest): ModelResponse {
            callCount.incrementAndGet()
            return ModelResponse(
                content = "",
                inputTokens = 10,
                outputTokens = 5,
                modelUsed = request.model,
                toolCalls = listOf(ToolCall("call-1", toolName, "{}")),
            )
        }
    }

    private fun streamingProvider(id: String = "stream-provider", content: String = "stream-ok") =
        object : ModelProvider, StreamCapable {
            val callCount = AtomicInteger(0)
            val emitCount = AtomicInteger(0)
            override fun providerId() = id
            override suspend fun complete(request: ModelRequest): ModelResponse {
                callCount.incrementAndGet()
                return ModelResponse(content = content, inputTokens = 10, outputTokens = 5, modelUsed = request.model)
            }
            override fun stream(request: ModelRequest): Flow<StreamChunk> {
                callCount.incrementAndGet()
                return flow {
                    emit(StreamChunk.Token("hello"))
                    emitCount.incrementAndGet()
                    emit(StreamChunk.Complete(fullText = content))
                }
            }
        }

    private val echoTool = object : ResolvedTool {
        override val name = "echo"
        override val description = "Echoes input"
        override val inputSchemaJson = "{}"
        override val idempotent = true
        override val sideEffectLevel = dev.tramai.core.model.SideEffectLevel.READ_ONLY
        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult =
            ToolResult.Success(input.toString())
    }

    private val countingEchoTool = object : ResolvedTool {
        val callCount = AtomicInteger(0)
        override val name = "echo"
        override val description = "Echoes input"
        override val inputSchemaJson = "{}"
        override val idempotent = true
        override val sideEffectLevel = dev.tramai.core.model.SideEffectLevel.READ_ONLY
        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            callCount.incrementAndGet()
            return ToolResult.Success(input.toString())
        }
    }

    // -- Test service interfaces ------------------------------------------------

    @AiService
    interface TestService {
        @Operation(prompt = "test", model = "test-model", tools = ["echo"])
        suspend fun analyze(input: String): String
    }

    @AiService
    interface StreamingTestService {
        @Operation(prompt = "test", model = "test-model", tools = ["echo"])
        suspend fun stream(input: String): Flow<StreamChunk>
    }

    @AiService
    interface CachedTestService {
        @Operation(prompt = "test", model = "test-model", cacheable = true, cacheTtlMillis = 60_000)
        suspend fun cachedCall(input: String): String
    }

    @AiService
    interface StructuredTestService {
        @Operation(prompt = "test", model = "test-model", maxRetries = 0)
        suspend fun analyze(input: String): TestPayload
    }

    @AiService
    interface CachedStructuredService {
        @Operation(prompt = "test", model = "test-model", cacheable = true, cacheTtlMillis = 60_000, maxRetries = 0)
        suspend fun analyze(input: String): TestPayload
    }

    data class TestPayload(val answer: String)

    // -- Helper ---------------------------------------------------------------

    private fun engine(
        policyEngine: PolicyEngine? = null,
        provider: ModelProvider = providerThatReturns("ok"),
    ) = TramaiEngine(
        provider = provider,
        toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
        policyEngine = policyEngine,
    )

    private fun engineWithCache(
        policyEngine: PolicyEngine? = null,
        provider: ModelProvider = providerThatReturns("ok"),
    ) = TramaiEngine(
        provider = provider,
        responseCache = InMemoryOperationResponseCache(),
        policyEngine = policyEngine,
    )

    // -- Core enforcement tests ------------------------------------------------

    @Test
    fun `provider invocation allowed`() = runBlocking {
        val provider = CountingProvider()
        val allowEngine = engine(
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    PolicyDecision.Allow
            },
            provider = provider,
        )
        val service = allowEngine.create<TestService>()
        val result = service.analyze("test")
        assertThat(result).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `provider invocation denied at correct hook`() = runBlocking {
        val provider = CountingProvider()
        val denyEngine = engine(
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_PROVIDER_INVOCATION) {
                        PolicyDecision.Deny("blocked", "TEST_DENY")
                    } else PolicyDecision.Allow
            },
            provider = provider,
        )
        val service = denyEngine.create<TestService>()

        assertThatThrownBy { runBlocking { service.analyze("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("blocked")
        assertThat(provider.callCount.get()).isEqualTo(0)
    }

    @Test
    fun `provider context has providerId and modelName`() = runBlocking {
        var capturedProviderId: String? = null
        var capturedModelName: String? = null
        val engine = engine(
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision {
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_PROVIDER_INVOCATION) {
                        capturedProviderId = context.providerId
                        capturedModelName = context.modelName
                    }
                    return PolicyDecision.Allow
                }
            },
        )
        val service = engine.create<TestService>()
        service.analyze("test")
        assertThat(capturedProviderId).isNotNull()
        assertThat(capturedModelName).isEqualTo("test-model")
    }

    // -- Fallback tests --------------------------------------------------------

    @Test
    fun `fallback denied propagates PolicyViolationException`() = runBlocking {
        val failOnceProvider = object : ModelProvider {
            var calls = 0
            override fun providerId() = "fail-provider"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                calls++
                if (calls == 1) throw ProviderException("fail", retryable = true)
                return ModelResponse(content = "ok", inputTokens = 10, outputTokens = 5, modelUsed = request.model)
            }
        }
        val fallbackProvider = CountingProvider("fallback-provider", "fallback-ok")
        val denyFallback = TramaiEngine(
            providerRegistry = ProviderRegistry.builder()
                .provider("fail-provider", failOnceProvider, default = true)
                .model("test-model", "fail-provider")
                .fallbackProvider("test-model", "fallback-provider")
                .provider("fallback-provider", fallbackProvider)
                .build(),
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_FALLBACK) {
                        PolicyDecision.Deny("fallback blocked", "FALLBACK_DENY")
                    } else PolicyDecision.Allow
            },
        )
        val service = denyFallback.create<TestService>()

        val thrown = org.assertj.core.api.Assertions.catchThrowableOfType({
            runBlocking { service.analyze("test") }
        }, PolicyViolationException::class.java)
        assertThat(thrown).isNotNull
        assertThat(thrown!!.suppressed).isNotEmpty
        assertThat(thrown.suppressed[0]).isInstanceOf(ProviderException::class.java)
        // Fallback provider should never have been called
        assertThat(fallbackProvider.callCount.get()).isEqualTo(0)
    }

    @Test
    fun `fallback allowed`() = runBlocking {
        val failOnceProvider = object : ModelProvider {
            var calls = 0
            override fun providerId() = "fail-provider"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                calls++
                if (calls == 1) throw ProviderException("fail", retryable = true)
                return ModelResponse(content = "ok", inputTokens = 10, outputTokens = 5, modelUsed = request.model)
            }
        }
        val fallbackProvider = CountingProvider("fallback-provider", "fallback-ok")
        val allowAll = TramaiEngine(
            providerRegistry = ProviderRegistry.builder()
                .provider("fail-provider", failOnceProvider, default = true)
                .model("test-model", "fail-provider")
                .fallbackProvider("test-model", "fallback-provider")
                .provider("fallback-provider", fallbackProvider)
                .build(),
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    PolicyDecision.Allow
            },
        )
        val service = allowAll.create<TestService>()
        val result = service.analyze("test")
        assertThat(result).isEqualTo("fallback-ok")
        assertThat(fallbackProvider.callCount.get()).isEqualTo(1)
    }

    // -- Tool tests ------------------------------------------------------------

    @Test
    fun `tool exposure denied per tool at correct hook`() = runBlocking {
        val provider = providerWithToolCall("echo")
        val deniedTool = AtomicInteger()
        val denyTools = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXPOSURE && context.toolName == "echo") {
                        deniedTool.incrementAndGet()
                        PolicyDecision.Deny("tools blocked", "TOOL_EXPOSURE_DENY")
                    } else PolicyDecision.Allow
            },
        )
        val service = denyTools.create<TestService>()

        assertThatThrownBy { runBlocking { service.analyze("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("tools blocked")
        assertThat(deniedTool.get()).isEqualTo(1)
    }

    @Test
    fun `tool execution denied at correct hook`() = runBlocking {
        val countingTool = countingEchoTool
        val denyExec = TramaiEngine(
            provider = providerWithToolCall("echo"),
            toolRegistry = ToolRegistry(mapOf("echo" to countingTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                        PolicyDecision.Deny("tool denied", "TOOL_EXEC_DENY")
                    } else PolicyDecision.Allow
            },
        )
        val service = denyExec.create<TestService>()

        assertThatThrownBy { runBlocking { service.analyze("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("tool denied")
        assertThat(countingTool.callCount.get()).isEqualTo(0)
    }

    @Test
    fun `tool result reinjection denied at correct hook`() = runBlocking {
        val denyReinject = TramaiEngine(
            provider = providerWithToolCall("echo"),
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION) {
                        PolicyDecision.Deny("result blocked", "REINJECT_DENY")
                    } else PolicyDecision.Allow
            },
        )
        val service = denyReinject.create<TestService>()

        assertThatThrownBy { runBlocking { service.analyze("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("result blocked")
    }

    // -- Response return tests -------------------------------------------------

    @Test
    fun `response return denied`() = runBlocking {
        val denyResp = engine(
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_RESPONSE_RETURN) {
                        PolicyDecision.Deny("response blocked", "RESPONSE_DENY")
                    } else PolicyDecision.Allow
            },
        )
        val service = denyResp.create<TestService>()

        assertThatThrownBy { runBlocking { service.analyze("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("response blocked")
    }

    // -- Approval tests --------------------------------------------------------

    @Test
    fun `require approval stops execution`() = runBlocking {
        val approvalEngine = engine(
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                        PolicyDecision.RequireApproval(
                            ApprovalRequirement("echo", "abc123", "needs approval", 30_000)
                        )
                    } else PolicyDecision.Allow
            },
            provider = providerWithToolCall("echo"),
        )
        val service = approvalEngine.create<TestService>()

        assertThatThrownBy { runBlocking { service.analyze("test") } }
            .isInstanceOf(ApprovalRequiredException::class.java)
            .hasMessageContaining("needs approval")
    }

    // -- Streaming tests -------------------------------------------------------

    @Test
    fun `streaming provider invocation denied`() = runBlocking {
        val sProvider = streamingProvider()
        val denyStream = TramaiEngine(
            provider = sProvider,
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_PROVIDER_INVOCATION) {
                        PolicyDecision.Deny("stream blocked", "STREAM_DENY")
                    } else PolicyDecision.Allow
            },
        )
        val service = denyStream.create<StreamingTestService>()

        assertThatThrownBy {
            runBlocking { service.stream("test").toList() }
        }.isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("stream blocked")
        assertThat(sProvider.callCount.get()).isEqualTo(0)
    }

    @Test
    fun `streaming response return denied before any token emitted`() = runBlocking {
        val sProvider = streamingProvider()
        val denyResp = TramaiEngine(
            provider = sProvider,
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_RESPONSE_RETURN) {
                        PolicyDecision.Deny("stream response blocked", "STREAM_RESP_DENY")
                    } else PolicyDecision.Allow
            },
        )
        val service = denyResp.create<StreamingTestService>()

        assertThatThrownBy {
            runBlocking { service.stream("test").toList() }
        }.isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("stream response blocked")
    }

    @Test
    fun `streaming tool exposure denied`() = runBlocking {
        val sProvider = streamingProvider()
        val denyStream = TramaiEngine(
            provider = sProvider,
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXPOSURE) {
                        PolicyDecision.Deny("stream tools blocked", "STREAM_TOOL_DENY")
                    } else PolicyDecision.Allow
            },
        )
        val service = denyStream.create<StreamingTestService>()

        assertThatThrownBy {
            runBlocking { service.stream("test").toList() }
        }.isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("stream tools blocked")
    }

    // -- Structured output tests -----------------------------------------------

    @Test
    fun `structured response return denied after parse success`() = runBlocking {
        val handler = object : StructuredOutputHandler {
            override fun analyze(rawResponse: String, targetType: kotlin.reflect.KType): StructuredOutputResult {
                return StructuredOutputResult.Success(TestPayload("parsed"), rawResponse)
            }
            override fun createContract(targetType: kotlin.reflect.KType) =
                StructuredOutputContract(targetType, "{ }")
            override fun generateSchema(type: kotlin.reflect.KType) = "{ }"
            override fun deserialize(input: Any, targetType: kotlin.reflect.KType) = TestPayload("parsed")
            override fun serialize(value: Any): Any = mapOf("answer" to "parsed")
        }
        val denyStruct = TramaiEngine(
            provider = providerThatReturns("ok"),
            structuredOutputHandler = handler,
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_RESPONSE_RETURN) {
                        PolicyDecision.Deny("struct response blocked", "STRUCT_DENY")
                    } else PolicyDecision.Allow
            },
        )
        val service = denyStruct.create<StructuredTestService>()

        assertThatThrownBy { runBlocking { service.analyze("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("struct response blocked")
    }

    // -- Cache tests -----------------------------------------------------------

    @Test
    fun `cached raw response denied after policy change`() = runBlocking {
        // Phase 1: allow + cache the result
        val provider = CountingProvider()
        val allowEngine = engineWithCache(
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    PolicyDecision.Allow
            },
            provider = provider,
        )
        val service1 = allowEngine.create<CachedTestService>()
        val result1 = service1.cachedCall("test")
        assertThat(result1).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(1)

        // Phase 2: deny BEFORE_RESPONSE_RETURN with a new engine instance
        val denyEngine = engineWithCache(
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_RESPONSE_RETURN) {
                        PolicyDecision.Deny("cache response blocked", "CACHE_DENY")
                    } else PolicyDecision.Allow
            },
            provider = provider,
        )
        val service2 = denyEngine.create<CachedTestService>()

        assertThatThrownBy { runBlocking { service2.cachedCall("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("cache response blocked")
    }

    @Test
    fun `cached structured response denied`() = runBlocking {
        val handler = object : StructuredOutputHandler {
            override fun analyze(rawResponse: String, targetType: kotlin.reflect.KType): StructuredOutputResult {
                return StructuredOutputResult.Success(TestPayload("parsed"), rawResponse)
            }
            override fun createContract(targetType: kotlin.reflect.KType) =
                StructuredOutputContract(targetType, "{ }")
            override fun generateSchema(type: kotlin.reflect.KType) = "{ }"
            override fun deserialize(input: Any, targetType: kotlin.reflect.KType) = TestPayload("parsed")
            override fun serialize(value: Any): Any = mapOf("answer" to "parsed")
        }

        // Phase 1: allow + cache
        val provider = CountingProvider()
        val allowEngine = TramaiEngine(
            provider = provider,
            structuredOutputHandler = handler,
            responseCache = InMemoryOperationResponseCache(),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    PolicyDecision.Allow
            },
        )
        val service1: CachedStructuredService = allowEngine.create()
        val result1 = service1.analyze("test")
        assertThat(result1.answer).isEqualTo("parsed")
        assertThat(provider.callCount.get()).isEqualTo(1)

        // Phase 2: deny
        val denyEngine = TramaiEngine(
            provider = provider,
            structuredOutputHandler = handler,
            responseCache = InMemoryOperationResponseCache(),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_RESPONSE_RETURN) {
                        PolicyDecision.Deny("cache struct blocked", "CACHE_STRUCT_DENY")
                    } else PolicyDecision.Allow
            },
        )
        val service2: CachedStructuredService = denyEngine.create()

        assertThatThrownBy { runBlocking { service2.analyze("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("cache struct blocked")
    }

    // -- Context semantics tests -----------------------------------------------

    @Test
    fun `context has distinct workflowId and workflowRunId`() = runBlocking {
        var captured = false
        val engineWithCapture = TramaiEngine(
            provider = providerThatReturns("ok"),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision {
                    assertThat(context.workflowId).isNull()
                    assertThat(context.workflowRunId).isNull()
                    assertThat(context.correlationId).isNotEmpty()
                    assertThat(context.actorId).isEqualTo(PolicyEnforcementHelper.ACTOR_ANONYMOUS)
                    captured = true
                    return PolicyDecision.Allow
                }
            },
        )
        val service = engineWithCapture.create<TestService>()
        service.analyze("test")
        assertThat(captured).isTrue()
    }

    @Test
    fun `correlationId is stable across one execution flow`() = runBlocking {
        val correlationIds = mutableListOf<String>()
        // Provider returns a tool call on the first call, then a normal response
        val provider = object : ModelProvider {
            var calls = 0
            override fun providerId() = "test-provider"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                calls++
                return if (calls == 1) {
                    ModelResponse(
                        content = "",
                        inputTokens = 10, outputTokens = 5, modelUsed = request.model,
                        toolCalls = listOf(ToolCall("call-1", "echo", "{}")),
                    )
                } else {
                    ModelResponse(content = "done", inputTokens = 10, outputTokens = 5, modelUsed = request.model)
                }
            }
        }
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision {
                    correlationIds.add(context.correlationId)
                    return PolicyDecision.Allow
                }
            },
        )
        val service = engine.create<TestService>()
        service.analyze("test")
        assertThat(correlationIds).isNotEmpty
        val firstId = correlationIds.first()
        correlationIds.forEach { assertThat(it).isEqualTo(firstId) }
    }

    @Test
    fun `tool enforcement has toolName and correlationId`() = runBlocking {
        var capturedToolName: String? = null
        val engine = TramaiEngine(
            provider = providerWithToolCall("echo"),
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision {
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                        capturedToolName = context.toolName
                    }
                    return PolicyDecision.Allow
                }
            },
        )
        val service = engine.create<TestService>()
        service.analyze("test")
        assertThat(capturedToolName).isEqualTo("echo")
    }

    private val insecureTool = object : ResolvedTool {
        override val name = "insecure"
        override val description = "Insecure Tool"
        override val inputSchemaJson = "{}"
        override val idempotent = false
        override val sideEffectLevel = dev.tramai.core.model.SideEffectLevel.WRITE
        override val security = dev.tramai.core.policy.ToolSecurityMetadata(
            permission = "insecure.execute",
            risk = dev.tramai.core.policy.RiskLevel.HIGH,
            approval = dev.tramai.core.policy.ApprovalMode.HUMAN_REQUIRED,
            managedNetworkEgress = dev.tramai.core.policy.ManagedNetworkEgress.DENY,
            audit = dev.tramai.core.policy.AuditDetail.FULL,
        )
        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult =
            ToolResult.Success("insecure result")
    }

    @AiService
    interface ServiceWithInsecureTool {
        @Operation(prompt = "test", model = "test-model", tools = ["insecure"])
        suspend fun analyze(input: String): String
    }

    @Test
    fun `tool execution enforcement has toolSecurity`() = runBlocking {
        var capturedSecurity: dev.tramai.core.policy.ToolSecurityMetadata? = null
        val engine = TramaiEngine(
            provider = providerWithToolCall("insecure"),
            toolRegistry = ToolRegistry(mapOf("insecure" to insecureTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision {
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                        capturedSecurity = context.toolSecurity
                    }
                    return PolicyDecision.Allow
                }
            },
        )
        val service = engine.create<ServiceWithInsecureTool>()
        service.analyze("test")
        assertThat(capturedSecurity).isNotNull
        assertThat(capturedSecurity?.risk).isEqualTo(dev.tramai.core.policy.RiskLevel.HIGH)
    }

    @Test
    fun `tool exposure enforcement has toolSecurity`() = runBlocking {
        var capturedSecurity: dev.tramai.core.policy.ToolSecurityMetadata? = null

        val engine = TramaiEngine(
            provider = providerThatReturns("ok"),
            toolRegistry = ToolRegistry(mapOf("insecure" to insecureTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision {
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXPOSURE) {
                        capturedSecurity = context.toolSecurity
                    }
                    return PolicyDecision.Allow
                }
            },
        )
        val service = engine.create<ServiceWithInsecureTool>()
        service.analyze("test")
        assertThat(capturedSecurity).isNotNull
        assertThat(capturedSecurity?.risk).isEqualTo(dev.tramai.core.policy.RiskLevel.HIGH)
    }

    // -- Legacy compatibility tests --------------------------------------------

    @Test
    fun `legacy compatibility allows execution without policy engine`() = runBlocking {
        val legacyEngine = TramaiEngine(provider = providerThatReturns("legacy-ok"))
        val service = legacyEngine.create<TestService>()
        val result = service.analyze("test")
        assertThat(result).isEqualTo("legacy-ok")
    }

    @Test
    fun `legacy compatibility allows streaming without policy engine`() = runBlocking {
        val sProvider = streamingProvider()
        val legacyEngine = TramaiEngine(provider = sProvider)
        val service = legacyEngine.create<StreamingTestService>()
        val chunks = service.stream("test").toList()
        assertThat(chunks).isNotEmpty
        assertThat(chunks.first()).isInstanceOf(StreamChunk.Token::class.java)
    }

    @Test
    fun `legacy mode does not break normal execution`() = runBlocking {
        // Multiple service proxies from the same engine should all work
        val legacyEngine = TramaiEngine(provider = providerThatReturns("legacy-ok"))
        val service1 = legacyEngine.create<TestService>()
        val service2 = legacyEngine.create<CachedTestService>()

        val r1 = service1.analyze("test")
        val r2 = service2.cachedCall("hello")
        assertThat(r1).isEqualTo("legacy-ok")
        assertThat(r2).isEqualTo("legacy-ok")
    }

    // -- Structured enforcement ordering (ITEM 2) ------------------------------

    @Test
    fun `structured BEFORE_RESPONSE_RETURN deny prevents persist and cache side effects`() = runBlocking {
        val handler = object : StructuredOutputHandler {
            override fun analyze(rawResponse: String, targetType: kotlin.reflect.KType): StructuredOutputResult =
                StructuredOutputResult.Success(TestPayload("parsed"), rawResponse)
            override fun createContract(targetType: kotlin.reflect.KType) =
                StructuredOutputContract(targetType, "{ }")
            override fun generateSchema(type: kotlin.reflect.KType) = "{ }"
            override fun deserialize(input: Any, targetType: kotlin.reflect.KType) = TestPayload("parsed")
            override fun serialize(value: Any): Any = mapOf("answer" to "parsed")
        }

        var cachePutCalled = false
        var memoryPersistCalled = false
        val provider = CountingProvider()

        val cache = object : OperationResponseCache {
            override fun get(key: OperationCacheKey): Any? = null
            override fun put(key: OperationCacheKey, value: Any, ttlMillis: Long) {
                cachePutCalled = true
            }
        }
        val memory = object : ChatMemory {
            override fun get(conversationId: String): List<Message> = emptyList()
            override fun add(conversationId: String, messages: List<Message>) {
                memoryPersistCalled = true
            }
            override fun add(conversationId: String, message: Message) {
                memoryPersistCalled = true
            }
            override fun clear(conversationId: String) {}
        }

        val engine = TramaiEngine(
            provider = provider,
            structuredOutputHandler = handler,
            responseCache = cache,
            chatMemory = memory,
            conversationIdProvider = dev.tramai.core.memory.UuidConversationIdProvider(),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_RESPONSE_RETURN) {
                        PolicyDecision.Deny("struct blocked", "STRUCT_DENY")
                    } else PolicyDecision.Allow
            },
        )
        val service = engine.create<CachedStructuredService>()

        assertThatThrownBy { runBlocking { service.analyze("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("struct blocked")

        // Provider was called (onCallCompleted with parseSuccess=true fires before enforcement)
        assertThat(provider.callCount.get()).isEqualTo(1)
        // Cache was never populated (cacheValue not called because enforcement fires first)
        assertThat(cachePutCalled).isFalse()
        // Memory was never updated (persistStructuredSuccess not called)
        assertThat(memoryPersistCalled).isFalse()
    }

    // -- Cold streaming Flow enforcement (ITEM 3) ------------------------------

    @Test
    fun `cold Flow — policy changed after Flow creation blocks collection`() = runBlocking {
        var currentPolicy: PolicyDecision = PolicyDecision.Allow
        val sProvider = streamingProvider()

        val engine = TramaiEngine(
            provider = sProvider,
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision = currentPolicy
            },
        )
        val service = engine.create<StreamingTestService>()

        // Create Flow while policy ALLOWS
        val flow = service.stream("test")

        // Switch policy to DENY BEFORE_RESPONSE_RETURN before collecting
        currentPolicy = PolicyDecision.Deny("late denial", "LATE_DENY")

        // Collect — should be denied because enforcements run inside flow{} at collection time
        assertThatThrownBy {
            runBlocking { flow.toList() }
        }.isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("late denial")

        // Provider was never invoked because policy denied at flow collection
        assertThat(sProvider.callCount.get()).isEqualTo(0)
    }

    @Test
    fun `cold Flow — each collection evaluates policy independently`() = runBlocking {
        val enforceCount = AtomicInteger(0)
        val sProvider = streamingProvider()

        val engine = TramaiEngine(
            provider = sProvider,
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision {
                    enforceCount.incrementAndGet()
                    return PolicyDecision.Allow
                }
            },
        )
        val service = engine.create<StreamingTestService>()
        val flow = service.stream("test")

        // Collect Flow twice — policy should evaluate independently each time
        runBlocking { flow.toList() }
        val countAfterFirst = enforceCount.get()
        assertThat(countAfterFirst).isGreaterThan(0)

        runBlocking { flow.toList() }
        val countAfterSecond = enforceCount.get()

        // Second collection triggered additional evaluations (per-collection semantic)
        assertThat(countAfterSecond).isGreaterThan(countAfterFirst)
    }

    // -- Fallback transition enforcement (ITEM 4) ------------------------------

    @Test
    fun `circuit breaker open — BEFORE_FALLBACK fires and deny blocks fallback`() = runBlocking {
        val fallbackCallCount = AtomicInteger(0)
        val primaryCallCount = AtomicInteger(0)
        val failOnceProvider = object : ModelProvider {
            override fun providerId() = "primary"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                primaryCallCount.incrementAndGet()
                throw ProviderException("primary fail", retryable = true)
            }
        }
        val fallbackProvider = object : ModelProvider {
            override fun providerId() = "fallback"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                fallbackCallCount.incrementAndGet()
                return ModelResponse(content = "fallback-ok", inputTokens = 10, outputTokens = 5, modelUsed = request.model)
            }
        }

        val registry = ProviderRegistry.builder()
            .provider("primary", failOnceProvider, default = true)
            .model("test-model", "primary")
            .fallbackProvider("test-model", "fallback")
            .provider("fallback", fallbackProvider)
            .build()

        val settings = CircuitBreakerSettings(
            failureThreshold = 1,
            openDurationMillis = 3_600_000, // long recovery — stays open
        )
        var allowFallback = true

        val engine = TramaiEngine(
            providerRegistry = registry,
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            circuitBreakerSettings = settings,
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_FALLBACK && !allowFallback) {
                        PolicyDecision.Deny("fallback denied", "FALLBACK_DENY")
                    } else PolicyDecision.Allow
            },
        )

        // Phase 1: primary fails, fallback allowed, circuit breaker trips
        val service1 = engine.create<TestService>()
        val result1 = service1.analyze("test")
        assertThat(result1).isEqualTo("fallback-ok")
        assertThat(primaryCallCount.get()).isEqualTo(1)
        assertThat(fallbackCallCount.get()).isEqualTo(1)

        // Phase 2: primary circuit breaker is now open. Deny fallback.
        allowFallback = false
        val service2 = engine.create<TestService>()

        assertThatThrownBy { runBlocking { service2.analyze("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("fallback denied")

        // Secondary was never invoked in phase 2 (denied before reaching it)
        assertThat(fallbackCallCount.get()).isEqualTo(1)
    }

    @Test
    fun `provider failure — BEFORE_FALLBACK fires and allow proceeds to secondary`() = runBlocking {
        val fallbackCalled = AtomicInteger(0)
        var fallbackEnforced = false
        val failOnceProvider = object : ModelProvider {
            val callCount = AtomicInteger(0)
            override fun providerId() = "primary"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                callCount.incrementAndGet()
                if (callCount.get() == 1) throw ProviderException("primary fail", retryable = true)
                return ModelResponse(content = "primary-ok", inputTokens = 10, outputTokens = 5, modelUsed = request.model)
            }
        }
        val fallbackProvider = object : ModelProvider {
            override fun providerId() = "fallback"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                fallbackCalled.incrementAndGet()
                return ModelResponse(content = "fallback-ok", inputTokens = 10, outputTokens = 5, modelUsed = request.model)
            }
        }

        val registry = ProviderRegistry.builder()
            .provider("primary", failOnceProvider, default = true)
            .model("test-model", "primary")
            .fallbackProvider("test-model", "fallback")
            .provider("fallback", fallbackProvider)
            .build()

        val engine = TramaiEngine(
            providerRegistry = registry,
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision {
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_FALLBACK) {
                        fallbackEnforced = true
                    }
                    return PolicyDecision.Allow
                }
            },
        )
        val service = engine.create<TestService>()
        val result = service.analyze("test")
        assertThat(result).isEqualTo("fallback-ok")
        assertThat(fallbackEnforced).isTrue()
        assertThat(fallbackCalled.get()).isEqualTo(1)
    }
}
