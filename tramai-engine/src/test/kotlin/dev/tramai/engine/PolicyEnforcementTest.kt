package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.ConversationId
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.User as UserMessage
import dev.tramai.core.exception.ApprovalRequiredException
import dev.tramai.core.exception.ModelNotRegisteredException
import dev.tramai.core.exception.ModelRegistryUnavailableException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.model.ClassifiedDocument
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolResult
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
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
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
import dev.tramai.security.ProviderRoutingConfiguration
import dev.tramai.security.ProviderTrustZone
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

    private fun providerWithToolCallThenReturn(toolName: String, finalContent: String = "done") = object : ModelProvider {
        val callCount = AtomicInteger(0)
        override fun providerId() = "test-provider"
        override suspend fun complete(request: ModelRequest): ModelResponse {
            return if (callCount.incrementAndGet() == 1) {
                ModelResponse(
                    content = "",
                    inputTokens = 10,
                    outputTokens = 5,
                    modelUsed = request.model,
                    toolCalls = listOf(ToolCall("call-1", toolName, "{}")),
                )
            } else {
                ModelResponse(
                    content = finalContent,
                    inputTokens = 10,
                    outputTokens = 5,
                    modelUsed = request.model,
                )
            }
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

    private val highRiskPaymentTool = object : ResolvedTool {
        val callCount = AtomicInteger(0)
        override val name = "payment-tool"
        override val description = "Executes payments"
        override val inputSchemaJson = "{}"
        override val idempotent = false
        override val sideEffectLevel = dev.tramai.core.model.SideEffectLevel.WRITE
        override val security = dev.tramai.core.policy.ToolSecurityMetadata(
            permission = "payment.execute",
            risk = dev.tramai.core.policy.RiskLevel.HIGH,
            approval = dev.tramai.core.policy.ApprovalMode.HUMAN_REQUIRED,
            managedNetworkEgress = dev.tramai.core.policy.ManagedNetworkEgress.DENY,
            audit = dev.tramai.core.policy.AuditDetail.FULL,
        )
        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            callCount.incrementAndGet()
            return ToolResult.Success("payment result")
        }
    }

    // -- Test service interfaces ------------------------------------------------

    @AiService
    interface TestService {
        @Operation(prompt = "test", model = "test-model", tools = ["echo"], providerRetries = 0)
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
    interface RetryTestService {
        @Operation(prompt = "test", model = "test-model", providerRetries = 1)
        suspend fun analyze(input: String): String
    }

    @AiService
    interface CachedToolTestService {
        @Operation(prompt = "test", model = "test-model", tools = ["echo"], cacheable = true, cacheTtlMillis = 60_000)
        suspend fun cachedCall(input: String): String
    }

    @AiService
    interface StructuredTestService {
        @Operation(prompt = "test", model = "test-model", maxRetries = 0)
        suspend fun analyze(input: String): TestPayload
    }

    @AiService
    interface ClassifiedTestService {
        @Operation(prompt = "test", model = "test-model", providerRetries = 0)
        suspend fun analyze(input: ClassifiedDocument<String>): String
    }

    @AiService
    interface ClassifiedStreamingTestService {
        @Operation(prompt = "test", model = "test-model")
        suspend fun stream(input: ClassifiedDocument<String>): Flow<StreamChunk>
    }

    @AiService
    interface ClassifiedStructuredTestService {
        @Operation(prompt = "test", model = "test-model", maxRetries = 0)
        suspend fun analyze(input: ClassifiedDocument<String>): TestPayload
    }

    @AiService
    interface CachedStructuredService {
        @Operation(prompt = "test", model = "test-model", cacheable = true, cacheTtlMillis = 60_000, maxRetries = 0)
        suspend fun analyze(input: String): TestPayload
    }

    @AiService
    interface CachedClassifiedTestService {
        @Operation(
            prompt = "test",
            model = "test-model",
            cacheable = true,
            cacheTtlMillis = 60_000,
            providerRetries = 0,
        )
        suspend fun analyze(input: ClassifiedDocument<String>): String
    }

    @AiService
    interface CachedClassifiedStructuredService {
        @Operation(
            prompt = "test",
            model = "test-model",
            cacheable = true,
            cacheTtlMillis = 60_000,
            maxRetries = 0,
        )
        suspend fun analyze(input: ClassifiedDocument<String>): TestPayload
    }

    @AiService
    interface CachedConversationService {
        @UserMessage("Analyze {input}")
        @Operation(
            prompt = "",
            model = "test-model",
            cacheable = true,
            cacheTtlMillis = 60_000,
            providerRetries = 0,
        )
        suspend fun analyze(
            @ConversationId sessionId: String,
            input: String,
        ): String
    }

    @AiService
    interface FingerprintWithoutToolsService {
        @Operation(prompt = "test", model = "test-model", cacheable = true, cacheTtlMillis = 60_000)
        suspend fun cachedCall(input: String): String
    }

    @AiService
    interface FingerprintWithToolsService {
        @Operation(prompt = "test", model = "test-model", tools = ["echo"], cacheable = true, cacheTtlMillis = 60_000)
        suspend fun cachedCall(input: String): String
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
        responseCache: OperationResponseCache = InMemoryOperationResponseCache(),
    ) = TramaiEngine(
        provider = provider,
        responseCache = responseCache,
        policyEngine = policyEngine,
    )

    private fun parsedStructuredOutputHandler(answer: String = "parsed") = object : StructuredOutputHandler {
        override fun analyze(rawResponse: String, targetType: kotlin.reflect.KType): StructuredOutputResult =
            StructuredOutputResult.Success(TestPayload(answer), rawResponse)

        override fun createContract(targetType: kotlin.reflect.KType) =
            StructuredOutputContract(targetType, "{ }")

        override fun generateSchema(type: kotlin.reflect.KType) = "{ }"

        override fun deserialize(input: Any, targetType: kotlin.reflect.KType) = TestPayload(answer)

        override fun serialize(value: Any): Any = mapOf("answer" to answer)
    }

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
    fun `registry enabled fails closed when model is not registered`() = runBlocking {
        val engine = TramaiEngine(
            provider = CountingProvider(),
            modelRegistry = object : ModelRegistry {
                override suspend fun findApprovedModel(providerId: String, modelName: String) = null
            },
            modelRegistrySettings = ModelRegistrySettings(enabled = true),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision = PolicyDecision.Allow
            },
        )
        val service = engine.create<TestService>()

        assertThatThrownBy { runBlocking { service.analyze("test") } }
            .isInstanceOf(ModelNotRegisteredException::class.java)
    }

    @Test
    fun `cache provenance mismatch is treated as cache miss and invalidates stale entry`() = runBlocking {
        val provider = CountingProvider(content = "fresh")
        var cached: CachedOperationResult? = CachedOperationResult(
            value = "stale",
            provenance = CachedResponseProvenance(
                providerId = "test-provider",
                modelName = "test-model",
                dataClassification = null,
                classificationSource = null,
                modelRegistryEntryId = null,
                modelRevision = null,
                modelArtifactDigest = null,
            ),
        )
        var invalidated = false
        val cache = object : OperationResponseCache {
            override fun get(key: OperationCacheKey): CachedOperationResult? = cached

            override fun invalidate(key: OperationCacheKey) {
                invalidated = true
                cached = null
            }

            override fun put(key: OperationCacheKey, value: CachedOperationResult, ttlMillis: Long) = Unit
        }
        val engine = TramaiEngine(
            provider = provider,
            responseCache = cache,
            modelRegistry = object : ModelRegistry {
                override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? =
                    RegisteredModel(
                        registryEntryId = "entry-1",
                        providerId = providerId,
                        modelName = modelName,
                        revision = "rev-1",
                    )
            },
            modelRegistrySettings = ModelRegistrySettings(enabled = true),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision = PolicyDecision.Allow
            },
        )
        val service = engine.create<CachedTestService>()

        assertThat(service.cachedCall("test")).isEqualTo("fresh")
        assertThat(invalidated).isTrue()
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

    @Test
    fun `RESTRICTED request to trusted local provider is allowed`() = runBlocking {
        val provider = CountingProvider(id = "local-provider")
        val engine = TramaiEngine(
            provider = provider,
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("local-provider"),
                    trustedLocalProviders = setOf("local-provider"),
                ),
            ),
        )
        val service = engine.create<ClassifiedTestService>()

        val result = service.analyze(
            ClassifiedDocument(
                payload = "secret",
                classification = DataClassification.RESTRICTED,
                source = ClassificationSource.DECLARED,
            ),
        )

        assertThat(result).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `RESTRICTED request to approved untrusted cloud provider is denied before invocation`() = runBlocking {
        val provider = CountingProvider(id = "cloud-provider")
        val engine = TramaiEngine(
            provider = provider,
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("cloud-provider"),
                ),
            ),
        )
        val service = engine.create<ClassifiedTestService>()

        assertThatThrownBy {
            runBlocking {
                service.analyze(
                    ClassifiedDocument(
                        payload = "secret",
                        classification = DataClassification.RESTRICTED,
                        source = ClassificationSource.DECLARED,
                    ),
                )
            }
        }.isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("RESTRICTED data may not be sent to provider 'cloud-provider'")
        assertThat(provider.callCount.get()).isEqualTo(0)
    }

    @Test
    fun `RESTRICTED request does not silently fall back from local provider to cloud`() = runBlocking {
        val localProvider = object : ModelProvider {
            val callCount = AtomicInteger(0)
            override fun providerId() = "local-provider"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                callCount.incrementAndGet()
                throw ProviderException("local failed", retryable = true)
            }
        }
        val cloudProvider = CountingProvider(id = "cloud-provider")
        val engine = TramaiEngine(
            providerRegistry = ProviderRegistry.builder()
                .provider("local-provider", localProvider, default = true)
                .model("test-model", "local-provider")
                .fallbackProvider("test-model", "cloud-provider")
                .provider("cloud-provider", cloudProvider)
                .build(),
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("local-provider", "cloud-provider"),
                    allowedFallbackProviders = setOf("cloud-provider"),
                    trustedLocalProviders = setOf("local-provider"),
                ),
            ),
        )
        val service = engine.create<ClassifiedTestService>()

        assertThatThrownBy {
            runBlocking {
                service.analyze(
                    ClassifiedDocument(
                        payload = "secret",
                        classification = DataClassification.RESTRICTED,
                        source = ClassificationSource.DECLARED,
                    ),
                )
            }
        }.isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("RESTRICTED data may not be sent to provider 'cloud-provider'")
        assertThat(localProvider.callCount.get()).isEqualTo(1)
        assertThat(cloudProvider.callCount.get()).isEqualTo(0)
    }

    @Test
    fun `INTERNAL request to cloud provider without permission is denied`() = runBlocking {
        val provider = CountingProvider(id = "cloud-provider")
        val engine = TramaiEngine(
            provider = provider,
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("cloud-provider"),
                ),
            ),
        )
        val service = engine.create<ClassifiedTestService>()

        assertThatThrownBy {
            runBlocking {
                service.analyze(
                    ClassifiedDocument(
                        payload = "internal",
                        classification = DataClassification.INTERNAL,
                        source = ClassificationSource.DECLARED,
                    ),
                )
            }
        }.isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("Data classification 'INTERNAL' is not allowed for provider 'cloud-provider'")
        assertThat(provider.callCount.get()).isEqualTo(0)
    }

    @Test
    fun `INTERNAL request to cloud provider with permission is allowed`() = runBlocking {
        val provider = CountingProvider(id = "cloud-provider")
        val engine = TramaiEngine(
            provider = provider,
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("cloud-provider"),
                    allowCloudForClassifications = setOf(DataClassification.PUBLIC, DataClassification.INTERNAL),
                ),
            ),
        )
        val service = engine.create<ClassifiedTestService>()

        val result = service.analyze(
            ClassifiedDocument(
                payload = "internal",
                classification = DataClassification.INTERNAL,
                source = ClassificationSource.DECLARED,
            ),
        )

        assertThat(result).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `PUBLIC request to approved cloud provider is allowed`() = runBlocking {
        val provider = CountingProvider(id = "cloud-provider")
        val engine = TramaiEngine(
            provider = provider,
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("cloud-provider"),
                ),
            ),
        )
        val service = engine.create<ClassifiedTestService>()

        val result = service.analyze(
            ClassifiedDocument(
                payload = "public",
                classification = DataClassification.PUBLIC,
                source = ClassificationSource.DECLARED,
            ),
        )

        assertThat(result).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    // -- Tool tests ------------------------------------------------------------

    @Test
    fun `tool exposure denied per tool at correct hook`() = runBlocking {
        val provider = providerWithToolCallThenReturn("echo")
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
            provider = providerWithToolCallThenReturn("echo"),
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
            provider = providerWithToolCallThenReturn("echo"),
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
            provider = providerWithToolCallThenReturn("echo"),
        )
        val service = approvalEngine.create<TestService>()

        assertThatThrownBy { runBlocking { service.analyze("test") } }
            .isInstanceOf(ApprovalRequiredException::class.java)
            .hasMessageContaining("needs approval")
    }

    @Test
    fun `HIGH risk tool exposure then execution requires approval`() = runBlocking {
        highRiskPaymentTool.callCount.set(0)
        val provider = object : ModelProvider {
            var callCount = 0
            override fun providerId() = "test-provider"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                callCount++
                return if (callCount == 1) {
                    ModelResponse(
                        content = "",
                        inputTokens = 10,
                        outputTokens = 5,
                        modelUsed = request.model,
                        toolCalls = listOf(ToolCall("call-1", "payment-tool", "{}")),
                    )
                } else {
                    ModelResponse(content = "done", inputTokens = 10, outputTokens = 5, modelUsed = request.model)
                }
            }
        }
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("payment-tool" to highRiskPaymentTool)),
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedTools = setOf("payment-tool"),
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("test-provider"),
                    allowedPermissions = setOf("payment.execute"),
                )
            ),
        )
        val service = engine.create<PaymentToolService>()

        assertThatThrownBy { runBlocking { service.analyze("test") } }
            .isInstanceOf(ApprovalRequiredException::class.java)
            .hasMessageContaining("requires human approval")
        assertThat(highRiskPaymentTool.callCount.get()).isEqualTo(0)
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

    @Test
    fun `streaming RESTRICTED request to untrusted provider is denied before stream starts`() = runBlocking {
        val provider = streamingProvider(id = "cloud-provider")
        val engine = TramaiEngine(
            provider = provider,
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("cloud-provider"),
                ),
            ),
        )
        val service = engine.create<ClassifiedStreamingTestService>()

        assertThatThrownBy {
            runBlocking {
                service.stream(
                    ClassifiedDocument(
                        payload = "secret",
                        classification = DataClassification.RESTRICTED,
                        source = ClassificationSource.DECLARED,
                    ),
                ).toList()
            }
        }.isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("RESTRICTED data may not be sent to provider 'cloud-provider'")
        assertThat(provider.callCount.get()).isEqualTo(0)
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

    @Test
    fun `structured RESTRICTED request to untrusted provider is denied before invocation`() = runBlocking {
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
        val provider = CountingProvider(id = "cloud-provider")
        val engine = TramaiEngine(
            provider = provider,
            structuredOutputHandler = handler,
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("cloud-provider"),
                ),
            ),
        )
        val service = engine.create<ClassifiedStructuredTestService>()

        assertThatThrownBy {
            runBlocking {
                service.analyze(
                    ClassifiedDocument(
                        payload = "secret",
                        classification = DataClassification.RESTRICTED,
                        source = ClassificationSource.DECLARED,
                    ),
                )
            }
        }.isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("RESTRICTED data may not be sent to provider 'cloud-provider'")
        assertThat(provider.callCount.get()).isEqualTo(0)
    }

    // -- Cache tests -----------------------------------------------------------

    @Test
    fun `unclassified raw cache hit does not invoke provider twice`() = runBlocking {
        val provider = CountingProvider()
        val service = engineWithCache(
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    PolicyDecision.Allow
            },
            provider = provider,
        ).create<CachedTestService>()

        assertThat(service.cachedCall("test")).isEqualTo("ok")
        assertThat(service.cachedCall("test")).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `classified RESTRICTED local raw cache hit does not invoke provider twice`() = runBlocking {
        val provider = CountingProvider(id = "local-provider")
        val service = engineWithCache(
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("local-provider"),
                    trustedLocalProviders = setOf("local-provider"),
                ),
            ),
            provider = provider,
        ).create<CachedClassifiedTestService>()
        val input = ClassifiedDocument(
            payload = "secret",
            classification = DataClassification.RESTRICTED,
            source = ClassificationSource.DECLARED,
        )

        assertThat(service.analyze(input)).isEqualTo("ok")
        assertThat(service.analyze(input)).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `classified RESTRICTED local structured cache hit does not invoke provider twice`() = runBlocking {
        val provider = CountingProvider(id = "local-provider")
        val engine = TramaiEngine(
            provider = provider,
            structuredOutputHandler = parsedStructuredOutputHandler(),
            responseCache = InMemoryOperationResponseCache(),
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("local-provider"),
                    trustedLocalProviders = setOf("local-provider"),
                ),
            ),
        )
        val service: CachedClassifiedStructuredService = engine.create()
        val input = ClassifiedDocument(
            payload = "secret",
            classification = DataClassification.RESTRICTED,
            source = ClassificationSource.DECLARED,
        )

        assertThat(service.analyze(input).answer).isEqualTo("parsed")
        assertThat(service.analyze(input).answer).isEqualTo("parsed")
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `same payload under PUBLIC then RESTRICTED produces separate cache entries`() = runBlocking {
        val provider = CountingProvider(id = "local-provider")
        val engine = TramaiEngine(
            provider = provider,
            responseCache = InMemoryOperationResponseCache(),
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("local-provider"),
                    trustedLocalProviders = setOf("local-provider"),
                ),
            ),
        )
        val service: CachedClassifiedTestService = engine.create()
        val publicInput = ClassifiedDocument(
            payload = "same-payload",
            classification = DataClassification.PUBLIC,
            source = ClassificationSource.DECLARED,
        )
        val restrictedInput = publicInput.copy(classification = DataClassification.RESTRICTED)

        val first = service.analyze(publicInput)
        val second = service.analyze(restrictedInput)

        assertThat(first).isEqualTo("ok")
        assertThat(second).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(2)
    }

    @Test
    fun `cache hit is re-authorized against current policy`() = runBlocking {
        val cache = InMemoryOperationResponseCache()
        val provider = CountingProvider(id = "cloud-provider")
        val allowEngine = engineWithCache(
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision = PolicyDecision.Allow
            },
            provider = provider,
            responseCache = cache,
        )
        val allowService = allowEngine.create<CachedClassifiedTestService>()
        val restrictedInput = ClassifiedDocument(
            payload = "secret",
            classification = DataClassification.RESTRICTED,
            source = ClassificationSource.DECLARED,
        )

        assertThat(allowService.analyze(restrictedInput)).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(1)

        val denyEngine = engineWithCache(
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("cloud-provider"),
                ),
            ),
            provider = provider,
            responseCache = cache,
        )
        val denyService = denyEngine.create<CachedClassifiedTestService>()

        assertThatThrownBy { runBlocking { denyService.analyze(restrictedInput) } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("RESTRICTED data may not be sent to provider 'cloud-provider'")
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `cacheable operation with active chatMemory bypasses cache`() = runBlocking {
        val provider = CountingProvider()
        val memory = object : ChatMemory {
            override fun get(conversationId: String): List<Message> = emptyList()

            override fun add(conversationId: String, messages: List<Message>) = Unit

            override fun add(conversationId: String, message: Message) = Unit

            override fun clear(conversationId: String) = Unit
        }
        val engine = TramaiEngine(
            provider = provider,
            responseCache = InMemoryOperationResponseCache(),
            chatMemory = memory,
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision = PolicyDecision.Allow
            },
        )
        val service: CachedConversationService = engine.create()

        assertThat(service.analyze("session-a", "same-turn")).isEqualTo("ok")
        assertThat(service.analyze("session-a", "same-turn")).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(2)
    }

    @Test
    fun `policy change after cache write denies subsequent cache hit`() = runBlocking {
        val provider = CountingProvider()
        val cache = InMemoryOperationResponseCache()
        val allowEngine = engineWithCache(
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision = PolicyDecision.Allow
            },
            provider = provider,
            responseCache = cache,
        )
        val allowService = allowEngine.create<CachedTestService>()
        assertThat(allowService.cachedCall("test")).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(1)

        val denyEngine = engineWithCache(
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_RESPONSE_RETURN) {
                        PolicyDecision.Deny("cache response blocked", "CACHE_DENY")
                    } else {
                        PolicyDecision.Allow
                    }
            },
            provider = provider,
            responseCache = cache,
        )
        val denyService = denyEngine.create<CachedTestService>()

        assertThatThrownBy { runBlocking { denyService.cachedCall("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("cache response blocked")
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `cached provider removed from allowlist denies cache hit and skips provider`() = runBlocking {
        val cache = InMemoryOperationResponseCache()
        val provider = CountingProvider(id = "cloud-provider")
        val allowEngine = engineWithCache(
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("cloud-provider"),
                    allowCloudForClassifications = setOf(DataClassification.PUBLIC),
                ),
            ),
            provider = provider,
            responseCache = cache,
        )
        val allowService = allowEngine.create<CachedTestService>()
        assertThat(allowService.cachedCall("test")).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(1)

        val denyEngine = engineWithCache(
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = emptySet(),
                    allowCloudForClassifications = setOf(DataClassification.PUBLIC),
                ),
            ),
            provider = provider,
            responseCache = cache,
        )
        val denyService = denyEngine.create<CachedTestService>()

        assertThatThrownBy { runBlocking { denyService.cachedCall("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("Provider 'cloud-provider' is not in the allowed-providers registry")
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `cached model removed from allowlist denies cache hit and skips provider`() = runBlocking {
        val cache = InMemoryOperationResponseCache()
        val provider = CountingProvider(id = "cloud-provider")
        val allowEngine = engineWithCache(
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("cloud-provider"),
                    allowCloudForClassifications = setOf(DataClassification.PUBLIC),
                ),
            ),
            provider = provider,
            responseCache = cache,
        )
        val allowService = allowEngine.create<CachedTestService>()
        assertThat(allowService.cachedCall("test")).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(1)

        val denyEngine = engineWithCache(
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = emptySet(),
                    allowedProviders = setOf("cloud-provider"),
                    allowCloudForClassifications = setOf(DataClassification.PUBLIC),
                ),
            ),
            provider = provider,
            responseCache = cache,
        )
        val denyService = denyEngine.create<CachedTestService>()

        assertThatThrownBy { runBlocking { denyService.cachedCall("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("Model 'test-model' is not in the allowed-models registry")
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `cacheable operation with tools bypasses cache`() = runBlocking {
        val provider = CountingProvider()
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            responseCache = InMemoryOperationResponseCache(),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision = PolicyDecision.Allow
            },
        )
        val service: CachedToolTestService = engine.create()

        assertThat(service.cachedCall("test")).isEqualTo("ok")
        assertThat(service.cachedCall("test")).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(2)
    }

    @Test
    fun `cacheable operation with custom interceptor bypasses cache`() = runBlocking {
        val provider = CountingProvider()
        // A non-singleton interceptor — the engine checks reference-equality
        // against NoOpOperationInterceptor, so a fresh anonymous instance
        // always forces a cache bypass.
        val customInterceptor = object : OperationInterceptor {}
        val engine = TramaiEngine(
            provider = provider,
            responseCache = InMemoryOperationResponseCache(),
            operationInterceptor = customInterceptor,
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision = PolicyDecision.Allow
            },
        )
        val service: CachedTestService = engine.create()

        assertThat(service.cachedCall("test")).isEqualTo("ok")
        assertThat(service.cachedCall("test")).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(2)
    }

    @Test
    fun `cache key contains digest not raw prompt`() {
        val firstKey = buildOperationCacheKeyForTesting(
            serviceType = CachedTestService::class,
            methodName = "cachedCall",
            arguments = listOf("secret"),
        )
        val secondKey = buildOperationCacheKeyForTesting(
            serviceType = CachedTestService::class,
            methodName = "cachedCall",
            arguments = listOf("secret"),
        )
        val differentKey = buildOperationCacheKeyForTesting(
            serviceType = CachedTestService::class,
            methodName = "cachedCall",
            arguments = listOf("different"),
        )

        assertThat(firstKey.requestDigest.matches(Regex("^[a-f0-9]{64}$"))).isTrue()
        assertThat(firstKey.requestDigest).isEqualTo(secondKey.requestDigest)
        assertThat(firstKey.requestDigest).isNotEqualTo(differentKey.requestDigest)
        assertThat(firstKey.requestDigest).doesNotContain("secret")
    }

    @Test
    fun `text part containing delimiter characters does not collide with multiple parts`() {
        val singlePartDigest = buildRequestDigest(
            listOf(
                Message(
                    role = MessageRole.USER,
                    content = "",
                    contentParts = listOf(ContentPart.TextPart("a|T:b")),
                ),
            ),
        )
        val splitPartsDigest = buildRequestDigest(
            listOf(
                Message(
                    role = MessageRole.USER,
                    content = "",
                    contentParts = listOf(
                        ContentPart.TextPart("a"),
                        ContentPart.TextPart("b"),
                    ),
                ),
            ),
        )

        assertThat(singlePartDigest).isNotEqualTo(splitPartsDigest)
    }

    @Test
    fun `image url and mime containing special characters produce distinct digests`() {
        val firstDigest = buildRequestDigest(
            listOf(
                Message(
                    role = MessageRole.USER,
                    content = "",
                    contentParts = listOf(
                        ContentPart.ImageUrlContent(
                            url = "https://example.com/a|b?x=1\ny=2",
                            mimeType = "image/png|variant",
                        ),
                    ),
                ),
            ),
        )
        val secondDigest = buildRequestDigest(
            listOf(
                Message(
                    role = MessageRole.USER,
                    content = "",
                    contentParts = listOf(
                        ContentPart.ImageUrlContent(
                            url = "https://example.com/a:b?x=1\ny=2",
                            mimeType = "image/png:variant",
                        ),
                    ),
                ),
            ),
        )

        assertThat(firstDigest).isNotEqualTo(secondDigest)
    }

    @Test
    fun `same text with different toolCallId produces different digest`() {
        val firstDigest = buildRequestDigest(
            listOf(Message(role = MessageRole.TOOL, content = "same", toolCallId = "call-1")),
        )
        val secondDigest = buildRequestDigest(
            listOf(Message(role = MessageRole.TOOL, content = "same", toolCallId = "call-2")),
        )

        assertThat(firstDigest).isNotEqualTo(secondDigest)
    }

    @Test
    fun `same text with different assistant toolCalls produces different digest`() {
        val firstDigest = buildRequestDigest(
            listOf(
                Message(
                    role = MessageRole.ASSISTANT,
                    content = "same",
                    toolCalls = listOf(ToolCall("1", "refund-order", "{}")),
                ),
            ),
        )
        val secondDigest = buildRequestDigest(
            listOf(
                Message(
                    role = MessageRole.ASSISTANT,
                    content = "same",
                    toolCalls = listOf(ToolCall("1", "lookup-order", "{}")),
                ),
            ),
        )

        assertThat(firstDigest).isNotEqualTo(secondDigest)
    }

    @Test
    fun `operationFingerprint changes when tools change`() {
        val withoutTools = buildOperationCacheKeyForTesting(
            serviceType = FingerprintWithoutToolsService::class,
            methodName = "cachedCall",
            arguments = listOf("test"),
        )
        val withTools = buildOperationCacheKeyForTesting(
            serviceType = FingerprintWithToolsService::class,
            methodName = "cachedCall",
            arguments = listOf("test"),
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
        )

        assertThat(withoutTools.operationFingerprint).isNotEqualTo(withTools.operationFingerprint)
    }

    @Test
    fun `corrupted cached envelope with mismatched partition is rejected without policy call`() = runBlocking {
        val provider = CountingProvider()
        val policyCalls = AtomicInteger(0)
        val cache = object : OperationResponseCache {
            override fun get(key: OperationCacheKey): CachedOperationResult = CachedOperationResult(
                value = "ok",
                provenance = CachedResponseProvenance(
                    providerId = "cloud-provider",
                    modelName = "test-model",
                    dataClassification = DataClassification.RESTRICTED,
                    classificationSource = ClassificationSource.DECLARED,
                ),
            )

            override fun invalidate(key: OperationCacheKey) = Unit

            override fun put(key: OperationCacheKey, value: CachedOperationResult, ttlMillis: Long) = Unit
        }
        val engine = TramaiEngine(
            provider = provider,
            responseCache = cache,
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision {
                    policyCalls.incrementAndGet()
                    return PolicyDecision.Allow
                }
            },
        )
        val service: CachedTestService = engine.create()

        assertThatThrownBy { runBlocking { service.cachedCall("test") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cached entry envelope mismatch")
        assertThat(policyCalls.get()).isEqualTo(0)
        assertThat(provider.callCount.get()).isEqualTo(0)
    }

    @Test
    fun `provenance on cached raw and structured results includes providerId and modelName`() = runBlocking {
        val rawCache = InMemoryOperationResponseCache()
        val rawProvider = CountingProvider(id = "raw-provider")
        val rawService = engineWithCache(
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision = PolicyDecision.Allow
            },
            provider = rawProvider,
            responseCache = rawCache,
        ).create<CachedTestService>()
        assertThat(rawService.cachedCall("test")).isEqualTo("ok")

        val rawKey = rawCache.snapshotKeys().single()
        val rawEntry = rawCache.peek(rawKey)
        assertThat(rawEntry).isNotNull()
        assertThat(rawEntry!!.provenance.providerId).isEqualTo("raw-provider")
        assertThat(rawEntry.provenance.modelName).isEqualTo("test-model")

        val structuredCache = InMemoryOperationResponseCache()
        val structuredProvider = CountingProvider(id = "structured-provider")
        val structuredEngine = TramaiEngine(
            provider = structuredProvider,
            structuredOutputHandler = parsedStructuredOutputHandler(),
            responseCache = structuredCache,
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision = PolicyDecision.Allow
            },
        )
        val structuredService: CachedStructuredService = structuredEngine.create()
        assertThat(structuredService.analyze("test").answer).isEqualTo("parsed")

        val structuredKey = structuredCache.snapshotKeys().single()
        val structuredEntry = structuredCache.peek(structuredKey)
        assertThat(structuredEntry).isNotNull()
        assertThat(structuredEntry!!.provenance.providerId).isEqualTo("structured-provider")
        assertThat(structuredEntry.provenance.modelName).isEqualTo("test-model")
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
            provider = providerWithToolCallThenReturn("echo"),
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

    @AiService
    interface PaymentToolService {
        @Operation(prompt = "test", model = "test-model", tools = ["payment-tool"])
        suspend fun analyze(input: String): String
    }

    @Test
    fun `tool execution enforcement has toolSecurity`() = runBlocking {
        var capturedSecurity: dev.tramai.core.policy.ToolSecurityMetadata? = null
        val engine = TramaiEngine(
            provider = providerWithToolCallThenReturn("insecure"),
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
            override fun get(key: OperationCacheKey): CachedOperationResult? = null
            override fun invalidate(key: OperationCacheKey) = Unit
            override fun put(key: OperationCacheKey, value: CachedOperationResult, ttlMillis: Long) {
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

        // Provider was called (enforcement at BEFORE_RESPONSE_RETURN, not BEFORE_PROVIDER_INVOCATION)
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

    @Test
    fun `streaming fallback — BEFORE_RESPONSE_RETURN denies fallback route with providerId and modelName`() = runBlocking {
        val primaryStreamProvider = object : ModelProvider, StreamCapable {
            val callCount = AtomicInteger(0)
            override fun providerId() = "primary-stream"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                callCount.incrementAndGet()
                throw ProviderException("primary fail", retryable = true)
            }
            override fun stream(request: ModelRequest): Flow<StreamChunk> {
                callCount.incrementAndGet()
                throw ProviderException("primary stream fail", retryable = true)
            }
        }
        val fallbackStreamProvider = object : ModelProvider, StreamCapable {
            val callCount = AtomicInteger(0)
            override fun providerId() = "fallback-stream"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                callCount.incrementAndGet()
                return ModelResponse(content = "fallback-ok", inputTokens = 10, outputTokens = 5, modelUsed = request.model)
            }
            override fun stream(request: ModelRequest): Flow<StreamChunk> {
                callCount.incrementAndGet()
                return flow {
                    emit(StreamChunk.Token("fallback"))
                    emit(StreamChunk.Complete(fullText = "fallback-ok"))
                }
            }
        }

        val registry = ProviderRegistry.builder()
            .provider("primary-stream", primaryStreamProvider, default = true)
            .model("test-model", "primary-stream")
            .fallbackProvider("test-model", "fallback-stream")
            .provider("fallback-stream", fallbackStreamProvider)
            .build()

        val settings = CircuitBreakerSettings(
            failureThreshold = 1,
            openDurationMillis = 3_600_000, // long recovery — stays open
        )

        // Phase 1: trip the circuit breaker on primary
        val allowAll = TramaiEngine(
            providerRegistry = registry,
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            circuitBreakerSettings = settings,
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    PolicyDecision.Allow
            },
        )
        val service1 = allowAll.create<StreamingTestService>()
        runBlocking { service1.stream("test").toList() }
        // Circuit breaker is now open for primary, fallback was used in phase 1

        // Phase 2: BEFORE_FALLBACK allows, but BEFORE_RESPONSE_RETURN denies for fallback route
        var capturedFallbackProviderId: String? = null
        var capturedFallbackModelName: String? = null
        val fallbackStreamCallCountBefore = fallbackStreamProvider.callCount.get()

        val engine = TramaiEngine(
            providerRegistry = registry,
            toolRegistry = ToolRegistry(mapOf("echo" to echoTool)),
            circuitBreakerSettings = CircuitBreakerSettings(
                failureThreshold = 1,
                openDurationMillis = 3_600_000, // still open
            ),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision =
                    if (context.enforcementPoint == EnforcementPoint.BEFORE_RESPONSE_RETURN) {
                        capturedFallbackProviderId = context.providerId
                        capturedFallbackModelName = context.modelName
                        PolicyDecision.Deny("fallback stream response blocked", "FALLBACK_STREAM_DENY")
                    } else PolicyDecision.Allow
            },
        )
        val service2 = engine.create<StreamingTestService>()

        assertThatThrownBy {
            runBlocking { service2.stream("test").toList() }
        }.isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("fallback stream response blocked")

        // Fallback stream was evaluated with providerId and modelName populated
        assertThat(capturedFallbackProviderId).isEqualTo("fallback-stream")
        assertThat(capturedFallbackModelName).isEqualTo("test-model")

        // Fallback stream was never invoked (denied at BEFORE_RESPONSE_RETURN)
        assertThat(fallbackStreamProvider.callCount.get()).isEqualTo(fallbackStreamCallCountBefore)
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

    // -- Null toolSecurity tests ------------------------------------------------

    private val legacyTool = object : ResolvedTool {
        override val name = "legacy"
        override val description = "Legacy Tool"
        override val inputSchemaJson = "{}"
        override val idempotent = false
        override val sideEffectLevel = dev.tramai.core.model.SideEffectLevel.WRITE
        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult =
            ToolResult.Success("legacy result")
    }

    @AiService
    interface LegacyToolService {
        @Operation(prompt = "test", model = "test-model", tools = ["legacy"])
        suspend fun analyze(input: String): String
    }

    @Test
    fun `legacy tool without security metadata is rejected in secure mode`() = runBlocking {
        val provider = providerWithToolCallThenReturn("legacy")
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("legacy" to legacyTool)),
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedTools = setOf("legacy"),
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("test-provider"),
                )
            ),
        )
        val service = engine.create<LegacyToolService>()

        assertThatThrownBy { runBlocking { service.analyze("test") } }
            .isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("has no security metadata")
    }

    @Test
    fun `legacy tool without security metadata is allowed in preview mode`() = runBlocking {
        val provider = providerWithToolCallThenReturn("legacy")
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("legacy" to legacyTool)),
            policyEngine = DefaultPolicyEngine(PolicyConfiguration.preview()),
        )
        val service = engine.create<LegacyToolService>()

        val result = service.analyze("test")
        assertThat(result).isEqualTo("done")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Epic 2.3 / 2.4 — Engine-level classification routing matrix tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `RESTRICTED local failure to GLOBAL_CLOUD fallback is denied via routing matrix`() = runBlocking {
        val localProvider = object : ModelProvider {
            val callCount = AtomicInteger(0)
            override fun providerId() = "local-provider"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                callCount.incrementAndGet()
                throw ProviderException("local failed", retryable = true)
            }
        }
        val cloudProvider = CountingProvider(id = "cloud-provider")

        val engine = TramaiEngine(
            providerRegistry = ProviderRegistry.builder()
                .provider("local-provider", localProvider, default = true)
                .model("test-model", "local-provider")
                .fallbackProvider("test-model", "cloud-provider")
                .provider("cloud-provider", cloudProvider)
                .build(),
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("local-provider", "cloud-provider"),
                    allowedFallbackProviders = setOf("cloud-provider"),
                    providerRouting = ProviderRoutingConfiguration(
                        providerZones = mapOf(
                            "local-provider" to ProviderTrustZone.LOCAL,
                            "cloud-provider" to ProviderTrustZone.GLOBAL_CLOUD,
                        ),
                        enabled = true,
                    ),
                ),
            ),
        )
        val service = engine.create<ClassifiedTestService>()

        assertThatThrownBy {
            runBlocking {
                service.analyze(
                    ClassifiedDocument(
                        payload = "secret",
                        classification = DataClassification.RESTRICTED,
                        source = ClassificationSource.DECLARED,
                    ),
                )
            }
        }.isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("not allowed for fallback")
        assertThat(localProvider.callCount.get()).isEqualTo(1)
        assertThat(cloudProvider.callCount.get()).isEqualTo(0)
    }

    @Test
    fun `CONFIDENTIAL local failure to EU_CLOUD fallback is allowed via routing matrix`() = runBlocking {
        val localProvider = object : ModelProvider {
            val callCount = AtomicInteger(0)
            override fun providerId() = "local-provider"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                callCount.incrementAndGet()
                throw ProviderException("local failed", retryable = true)
            }
        }
        val euProvider = CountingProvider(id = "eu-provider")

        val engine = TramaiEngine(
            providerRegistry = ProviderRegistry.builder()
                .provider("local-provider", localProvider, default = true)
                .model("test-model", "local-provider")
                .fallbackProvider("test-model", "eu-provider")
                .provider("eu-provider", euProvider)
                .build(),
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("local-provider", "eu-provider"),
                    allowedFallbackProviders = setOf("eu-provider"),
                    providerRouting = ProviderRoutingConfiguration(
                        providerZones = mapOf(
                            "local-provider" to ProviderTrustZone.LOCAL,
                            "eu-provider" to ProviderTrustZone.EU_CLOUD,
                        ),
                        enabled = true,
                    ),
                ),
            ),
        )
        val service = engine.create<ClassifiedTestService>()

        val result = service.analyze(
            ClassifiedDocument(
                payload = "confidential",
                classification = DataClassification.CONFIDENTIAL,
                source = ClassificationSource.DECLARED,
            ),
        )

        assertThat(result).isEqualTo("ok")
        assertThat(localProvider.callCount.get()).isEqualTo(1)
        assertThat(euProvider.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `cache hit is invalidated when provider zone changes to restricted classification`() = runBlocking {
        val cache = InMemoryOperationResponseCache()
        val provider = CountingProvider(id = "ollama")

        // Engine 1: provider=ollama, LOCAL zone, RESTRICTED → allowed
        val engine1 = TramaiEngine(
            provider = provider,
            responseCache = cache,
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("ollama"),
                    trustedLocalProviders = setOf("ollama"),
                    providerRouting = ProviderRoutingConfiguration(
                        providerZones = mapOf("ollama" to ProviderTrustZone.LOCAL),
                        enabled = true,
                    ),
                ),
            ),
        )
        val service1 = engine1.create<CachedClassifiedTestService>()
        val input = ClassifiedDocument(
            payload = "cache-test",
            classification = DataClassification.RESTRICTED,
            source = ClassificationSource.DECLARED,
        )

        assertThat(service1.analyze(input)).isEqualTo("ok")
        assertThat(provider.callCount.get()).isEqualTo(1)

        // Engine 2: same provider now mapped to GLOBAL_CLOUD, RESTRICTED → denied
        val engine2 = TramaiEngine(
            provider = provider,
            responseCache = cache,
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("ollama"),
                    trustedLocalProviders = setOf("ollama"),
                    providerRouting = ProviderRoutingConfiguration(
                        providerZones = mapOf("ollama" to ProviderTrustZone.GLOBAL_CLOUD),
                        enabled = true,
                    ),
                ),
            ),
        )
        val service2 = engine2.create<CachedClassifiedTestService>()

        assertThatThrownBy {
            runBlocking { service2.analyze(input) }
        }.isInstanceOf(PolicyViolationException::class.java)
            .hasMessageContaining("not allowed for invocation")

        // Provider should NOT have been invoked again (cache hit denied by policy)
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `registry revocation between retries blocks second attempt`() = runBlocking {
        val provider = object : ModelProvider {
            val callCount = AtomicInteger(0)
            override fun providerId() = "test-provider"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                callCount.incrementAndGet()
                throw ProviderException("transient failure", retryable = true)
            }
        }
        val registry = object : ModelRegistry {
            private var firstCall = true
            override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? {
                return if (firstCall) {
                    firstCall = false
                    RegisteredModel(
                        registryEntryId = "entry-1",
                        providerId = "test-provider",
                        modelName = "test-model",
                        revision = "rev-1",
                    )
                } else {
                    null
                }
            }
        }
        val engine = TramaiEngine(
            providerRegistry = ProviderRegistry.builder()
                .provider("test-provider", provider, default = true)
                .model("test-model", "test-provider")
                .build(),
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.preview().copy(
                    allowedModels = setOf("*"),
                    allowedProviders = setOf("*"),
                ),
            ),
            modelRegistry = registry,
            modelRegistrySettings = ModelRegistrySettings(enabled = true),
        )
        val service = engine.create<RetryTestService>()

        assertThatThrownBy {
            runBlocking { service.analyze("test") }
        }.isInstanceOf(ModelNotRegisteredException::class.java)
        assertThat(provider.callCount.get()).isEqualTo(0)
    }

    @Test
    fun `cache outage fails closed without invalidating entry`() = runBlocking {
        val provider = CountingProvider(id = "test-provider")
        val cache = InMemoryOperationResponseCache(maxEntries = 10)
        val registry = object : ModelRegistry {
            override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? =
                RegisteredModel(
                    registryEntryId = "entry-1",
                    providerId = "test-provider",
                    modelName = "test-model",
                    revision = "rev-1",
                )
        }

        val engine = TramaiEngine(
            providerRegistry = ProviderRegistry.builder()
                .provider("test-provider", provider, default = true)
                .model("test-model", "test-provider")
                .build(),
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.preview().copy(
                    allowedModels = setOf("*"),
                    allowedProviders = setOf("*"),
                ),
            ),
            modelRegistry = registry,
            modelRegistrySettings = ModelRegistrySettings(enabled = true),
            responseCache = cache,
        )
        val service = engine.create<CachedTestService>()
        runBlocking { service.cachedCall("test") }
        assertThat(provider.callCount.get()).isEqualTo(1)

        val failingRegistry = object : ModelRegistry {
            override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? {
                throw IllegalStateException("registry-down")
            }
        }
        val engine2 = TramaiEngine(
            providerRegistry = ProviderRegistry.builder()
                .provider("test-provider", provider, default = true)
                .model("test-model", "test-provider")
                .build(),
            policyEngine = DefaultPolicyEngine(
                PolicyConfiguration.preview().copy(
                    allowedModels = setOf("*"),
                    allowedProviders = setOf("*"),
                ),
            ),
            modelRegistry = failingRegistry,
            modelRegistrySettings = ModelRegistrySettings(enabled = true),
            responseCache = cache,
        )
        val service2 = engine2.create<CachedTestService>()

        assertThatThrownBy {
            runBlocking { service2.cachedCall("test") }
        }.isInstanceOf(ModelRegistryUnavailableException::class.java)
        assertThat(provider.callCount.get()).isEqualTo(1)
        assertThat(cache.snapshotKeys()).isNotEmpty
    }
}
