package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolResult
import dev.tramai.core.policy.*
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PolicyAuditWiringTest {

    private val policyEngine = PolicyEngine { PolicyDecision.Allow }

    private val denyPolicyEngine = PolicyEngine { context ->
        PolicyDecision.Deny(reason = "test-block", reasonCode = "test-blocked")
    }

    private fun failingEmitter(failingPoint: EnforcementPoint) = PolicyDecisionAuditEmitter { point, _, _ ->
        if (point == failingPoint) {
            throw RuntimeException("Audit storage failure")
        }
    }

    @Test
    fun `ALLOW with configured emitter emits exactly one event before enforcement`() = runBlocking {
        val callCount = AtomicInteger(0)
        val emitter = PolicyDecisionAuditEmitter { _, _, _ -> callCount.incrementAndGet() }

        val helper = PolicyEnforcementHelper(
            policyEngine = policyEngine,
            migrationWarningGuard = AtomicBoolean(true),
            auditEmitter = emitter,
        )

        val context = buildTestContext(EnforcementPoint.BEFORE_PROVIDER_INVOCATION)
        helper.enforce(context)

        Assertions.assertEquals(1, callCount.get(), "Emitter should have been called exactly once")
    }

    @Test
    fun `DENY with configured emitter emits exactly one event before exception`() = runBlocking {
        val callCount = AtomicInteger(0)
        val emitter = PolicyDecisionAuditEmitter { _, _, _ -> callCount.incrementAndGet() }

        val helper = PolicyEnforcementHelper(
            policyEngine = denyPolicyEngine,
            migrationWarningGuard = AtomicBoolean(true),
            auditEmitter = emitter,
        )

        val context = buildTestContext(EnforcementPoint.BEFORE_PROVIDER_INVOCATION)

        try {
            helper.enforce(context)
            Assertions.fail("Expected PolicyViolationException")
        } catch (e: PolicyViolationException) {
            // Expected
        }

        Assertions.assertEquals(1, callCount.get(), "Emitter should have been called exactly once before exception")
    }

    @Test
    fun `NoOp emitter preserves existing behavior`() = runBlocking {
        val helper = PolicyEnforcementHelper(
            policyEngine = policyEngine,
            migrationWarningGuard = AtomicBoolean(true),
            auditEmitter = NoOpPolicyDecisionAuditEmitter,
        )

        val context = buildTestContext(EnforcementPoint.BEFORE_PROVIDER_INVOCATION)
        helper.enforce(context)
        Assertions.assertTrue(true)
    }

    @Test
    fun `NoOp emitter preserves DENY behavior`() = runBlocking {
        val helper = PolicyEnforcementHelper(
            policyEngine = denyPolicyEngine,
            migrationWarningGuard = AtomicBoolean(true),
            auditEmitter = NoOpPolicyDecisionAuditEmitter,
        )

        val context = buildTestContext(EnforcementPoint.BEFORE_PROVIDER_INVOCATION)

        try {
            helper.enforce(context)
            Assertions.fail("Expected PolicyViolationException")
        } catch (e: PolicyViolationException) {
            Assertions.assertEquals("test-blocked", e.decision.reasonCode)
        }
    }

    @Test
    fun `audit failure propagation`() = runBlocking {
        val throwingStore = object : dev.tramai.security.audit.AuditStore {
            override suspend fun appendNext(
                auditStreamId: String,
                eventFactory: (latest: dev.tramai.security.audit.AuditEvent?) -> dev.tramai.security.audit.AuditEvent,
            ): dev.tramai.security.audit.AuditEvent {
                throw RuntimeException("Audit store unavailable")
            }

            override suspend fun readStream(auditStreamId: String): List<dev.tramai.security.audit.AuditEvent> = emptyList()
            override suspend fun latestEvent(auditStreamId: String): dev.tramai.security.audit.AuditEvent? = null
        }

        val auditEngine = dev.tramai.security.audit.AuditEngine(throwingStore)
        val emitter = dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter(auditEngine)

        val helper = PolicyEnforcementHelper(
            policyEngine = policyEngine,
            migrationWarningGuard = AtomicBoolean(true),
            auditEmitter = emitter,
        )

        val context = buildTestContext(EnforcementPoint.BEFORE_PROVIDER_INVOCATION)

        try {
            helper.enforce(context)
            Assertions.fail("Expected RuntimeException from audit store failure")
        } catch (e: RuntimeException) {
            Assertions.assertEquals("Audit store unavailable", e.message)
        }
    }

    @Test
    fun `audit failure blocks provider invocation`() {
        runBlocking {
            val provider = CountingProvider()
            val engine = TramaiEngine(
                provider = provider,
                policyDecisionAuditEmitter = failingEmitter(EnforcementPoint.BEFORE_PROVIDER_INVOCATION),
            )
            val service = engine.create<BasicService>()

            assertThatThrownBy { runBlocking { service.respond("test") } }
                .isInstanceOf(RuntimeException::class.java)
                .hasMessage("Audit storage failure")
            assertThat(provider.callCount.get()).isZero()
        }
    }

    @Test
    fun `audit failure blocks tool execution`() {
        runBlocking {
            val provider = ToolLoopProvider()
            val tool = CountingTool()
            val engine = TramaiEngine(
                provider = provider,
                toolRegistry = ToolRegistry(mapOf("lookup" to tool)),
                policyDecisionAuditEmitter = failingEmitter(EnforcementPoint.BEFORE_TOOL_EXECUTION),
            )
            val service = engine.create<ToolLoopService>()

            assertThatThrownBy { runBlocking { service.respond("test") } }
                .isInstanceOf(RuntimeException::class.java)
                .hasMessage("Audit storage failure")
            assertThat(provider.callCount.get()).isEqualTo(1)
            assertThat(tool.callCount.get()).isZero()
        }
    }

    @Test
    fun `audit failure blocks fallback transition`() {
        runBlocking {
            val primary = FailingProvider()
            val fallback = CountingProvider(id = "fallback-provider", content = "fallback-ok")
            val engine = TramaiEngine(
                providerRegistry = ProviderRegistry.builder()
                    .provider("primary-provider", primary, default = true)
                    .provider("fallback-provider", fallback)
                    .model("test-model", "primary-provider")
                    .fallbackProvider("test-model", "fallback-provider")
                    .build(),
                policyDecisionAuditEmitter = failingEmitter(EnforcementPoint.BEFORE_FALLBACK),
            )
            val service = engine.create<BasicService>()

            assertThatThrownBy { runBlocking { service.respond("test") } }
                .isInstanceOf(RuntimeException::class.java)
                .hasMessage("Audit storage failure")
            assertThat(primary.callCount.get()).isEqualTo(1)
            assertThat(fallback.callCount.get()).isZero()
        }
    }

    @Test
    fun `audit failure blocks tool-result reinjection`() {
        runBlocking {
            val provider = ToolLoopProvider()
            val tool = CountingTool()
            val engine = TramaiEngine(
                provider = provider,
                toolRegistry = ToolRegistry(mapOf("lookup" to tool)),
                policyDecisionAuditEmitter = failingEmitter(EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION),
            )
            val service = engine.create<ToolLoopService>()

            assertThatThrownBy { runBlocking { service.respond("test") } }
                .isInstanceOf(RuntimeException::class.java)
                .hasMessage("Audit storage failure")
            assertThat(provider.callCount.get()).isEqualTo(1)
            assertThat(tool.callCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `audit failure blocks response return`() {
        runBlocking {
            val provider = CountingProvider(content = "ok")
            val engine = TramaiEngine(
                provider = provider,
                policyDecisionAuditEmitter = failingEmitter(EnforcementPoint.BEFORE_RESPONSE_RETURN),
            )
            val service = engine.create<BasicService>()

            assertThatThrownBy { runBlocking { service.respond("test") } }
                .isInstanceOf(RuntimeException::class.java)
                .hasMessage("Audit storage failure")
            assertThat(provider.callCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `audit failure blocks cache reuse`() {
        runBlocking {
            val provider = CountingProvider(content = "cached")
            val engine = TramaiEngine(
                provider = provider,
                responseCache = InMemoryOperationResponseCache(),
                policyDecisionAuditEmitter = failingEmitter(EnforcementPoint.BEFORE_RESPONSE_RETURN),
            )
            val service = engine.create<CachedBasicService>()

            assertThatThrownBy { runBlocking { service.respond("same") } }
                .isInstanceOf(RuntimeException::class.java)
                .hasMessage("Audit storage failure")
            assertThatThrownBy { runBlocking { service.respond("same") } }
                .isInstanceOf(RuntimeException::class.java)
                .hasMessage("Audit storage failure")
            assertThat(provider.callCount.get()).isEqualTo(2)
        }
    }

    private fun buildTestContext(enforcementPoint: EnforcementPoint): PolicyContext = PolicyContext(
        enforcementPoint = enforcementPoint,
        correlationId = "audit-wiring-test",
        actorId = "system.test",
        policyVersion = "test",
    )

    @AiService
    interface BasicService {
        @Operation(prompt = "test", model = "test-model", providerRetries = 0)
        suspend fun respond(input: String): String
    }

    @AiService
    interface CachedBasicService {
        @Operation(prompt = "test", model = "test-model", cacheable = true, cacheTtlMillis = 60_000, providerRetries = 0)
        suspend fun respond(input: String): String
    }

    @AiService
    interface ToolLoopService {
        @Operation(prompt = "test", model = "test-model", tools = ["lookup"], providerRetries = 0)
        suspend fun respond(input: String): String
    }

    private class CountingProvider(
        private val id: String = "test-provider",
        private val content: String = "ok",
    ) : ModelProvider {
        val callCount = AtomicInteger(0)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            callCount.incrementAndGet()
            return ModelResponse(
                content = content,
                inputTokens = 10,
                outputTokens = 5,
                modelUsed = request.model,
            )
        }

        override fun providerId(): String = id
    }

    private class FailingProvider : ModelProvider {
        val callCount = AtomicInteger(0)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            callCount.incrementAndGet()
            throw ProviderException("primary failed", retryable = true)
        }

        override fun providerId(): String = "primary-provider"
    }

    private class ToolLoopProvider : ModelProvider {
        val callCount = AtomicInteger(0)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            return if (callCount.incrementAndGet() == 1) {
                ModelResponse(
                    content = "",
                    inputTokens = 10,
                    outputTokens = 5,
                    modelUsed = request.model,
                    toolCalls = listOf(ToolCall("call-1", "lookup", "{}")),
                )
            } else {
                ModelResponse(
                    content = "done",
                    inputTokens = 10,
                    outputTokens = 5,
                    modelUsed = request.model,
                )
            }
        }

        override fun providerId(): String = "test-provider"
    }

    private class CountingTool : ResolvedTool {
        val callCount = AtomicInteger(0)

        override val name: String = "lookup"
        override val description: String = "Looks up an order"
        override val inputSchemaJson: String = "{}"
        override val idempotent: Boolean = true
        override val sideEffectLevel = dev.tramai.core.model.SideEffectLevel.READ_ONLY

        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            callCount.incrementAndGet()
            return ToolResult.Success("""{"resolved":"ok"}""")
        }
    }
}
