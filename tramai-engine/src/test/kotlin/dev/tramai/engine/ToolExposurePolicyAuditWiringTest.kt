package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.policy.*
import dev.tramai.core.provider.ModelProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Engine-level tests proving that tool exposure policy decisions
 * are audited with the expected enforcement point and safe tool metadata.
 *
 * These tests cover BEFORE_TOOL_EXPOSURE — the enforcement point where
 * TramAI decides whether a tool may be presented to the model. This is
 * NOT tool invocation: at this point the provider has not been called,
 * the model has not selected a tool, no tool arguments exist, and the
 * tool has not executed. An ALLOW audit event is evidence of exposure
 * permission, not evidence that the tool was subsequently called.
 *
 * Verified behaviors:
 * - BEFORE_TOOL_EXPOSURE emits one audit call per declared tool
 * - Audit context includes toolName and toolSecurity from the registry
 * - Denied tool exposure emits audit before PolicyViolationException
 * - Multiple declared tools each produce one audit call
 * - Audit failure at BEFORE_TOOL_EXPOSURE blocks provider invocation
 */
class ToolExposurePolicyAuditWiringTest {

    private val policyEngine = PolicyEngine { PolicyDecision.Allow }

    private val denyToolPolicyEngine = PolicyEngine { context ->
        if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXPOSURE) {
            PolicyDecision.Deny(reason = "tool-blocked", reasonCode = "tool_denied")
        } else {
            PolicyDecision.Allow
        }
    }

    private fun capturingEmitter() = object : PolicyDecisionAuditEmitter {
        private val _calls = mutableListOf<Triple<EnforcementPoint, PolicyContext, PolicyDecision>>()
        val calls: List<Triple<EnforcementPoint, PolicyContext, PolicyDecision>> get() = _calls.toList()

        override suspend fun emit(
            enforcementPoint: EnforcementPoint,
            context: PolicyContext,
            decision: PolicyDecision,
        ) {
            _calls.add(Triple(enforcementPoint, context, decision))
        }
    }

    @AiService
    interface SingleToolService {
        @Operation(prompt = "test", model = "test-model", tools = ["lookup"], providerRetries = 0)
        suspend fun respond(input: String): String
    }

    @AiService
    interface MultiToolService {
        @Operation(prompt = "test", model = "test-model", tools = ["lookup", "payment"], providerRetries = 0)
        suspend fun respond(input: String): String
    }

    private class DummyCountingProvider : ModelProvider {
        val callCount = AtomicInteger(0)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            callCount.incrementAndGet()
            return ModelResponse(
                content = "ok",
                inputTokens = 10,
                outputTokens = 5,
                modelUsed = request.model,
            )
        }

        override fun providerId(): String = "test-provider"
    }

    private class DummyTool(
        override val name: String,
        private val securityMeta: ToolSecurityMetadata? = ToolSecurityMetadata.legacyPermissive(),
    ) : ResolvedTool {
        val callCount = AtomicInteger(0)

        override val description: String = "Test tool"
        override val inputSchemaJson: String = "{}"
        override val idempotent: Boolean = true
        override val sideEffectLevel = SideEffectLevel.READ_ONLY
        override val security: ToolSecurityMetadata? get() = securityMeta

        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            callCount.incrementAndGet()
            return ToolResult.Success("{}")
        }
    }

    @Test
    fun `tool exposure audit emits tool name and enforcement point`() { runBlocking {
        val emitter = capturingEmitter()
        val engine = TramaiEngine(
            provider = DummyCountingProvider(),
            toolRegistry = ToolRegistry(mapOf("lookup" to DummyTool("lookup"))),
            policyDecisionAuditEmitter = emitter,
            policyEngine = policyEngine,
        )
        val service = engine.create<SingleToolService>()

        service.respond("find order 42")

        val toolExposureCalls = emitter.calls.filter {
            it.first == EnforcementPoint.BEFORE_TOOL_EXPOSURE
        }
        Assertions.assertEquals(1, toolExposureCalls.size)
        Assertions.assertEquals("lookup", toolExposureCalls[0].second.toolName)
        Assertions.assertNotNull(toolExposureCalls[0].second.toolSecurity)
    }
    }

    @Test
    fun `tool exposure deny emits audit before PolicyViolationException`() { runBlocking {
        val emitter = capturingEmitter()
        val engine = TramaiEngine(
            provider = DummyCountingProvider(),
            toolRegistry = ToolRegistry(mapOf("lookup" to DummyTool("lookup"))),
            policyDecisionAuditEmitter = emitter,
            policyEngine = denyToolPolicyEngine,
        )
        val service = engine.create<SingleToolService>()

        try {
            service.respond("restricted query")
            Assertions.fail("Expected PolicyViolationException")
        } catch (e: PolicyViolationException) {
            // Expected
        }

        val toolExposureCalls = emitter.calls.filter {
            it.first == EnforcementPoint.BEFORE_TOOL_EXPOSURE
        }
        Assertions.assertEquals(1, toolExposureCalls.size)
        Assertions.assertEquals("lookup", toolExposureCalls[0].second.toolName)
        Assertions.assertTrue(toolExposureCalls[0].third is PolicyDecision.Deny)
    }
    }

    @Test
    fun `multiple declared tools each emit one audit decision`() { runBlocking {
        val emitter = capturingEmitter()
        val engine = TramaiEngine(
            provider = DummyCountingProvider(),
            toolRegistry = ToolRegistry(
                mapOf("lookup" to DummyTool("lookup"), "payment" to DummyTool("payment"))
            ),
            policyDecisionAuditEmitter = emitter,
            policyEngine = policyEngine,
        )
        val service = engine.create<MultiToolService>()

        service.respond("process")

        val toolExposureCalls = emitter.calls.filter {
            it.first == EnforcementPoint.BEFORE_TOOL_EXPOSURE
        }
        Assertions.assertEquals(2, toolExposureCalls.size)
        Assertions.assertEquals(
            setOf("lookup", "payment"),
            toolExposureCalls.map { it.second.toolName }.toSet(),
        )
    }
    }

    @Test
    fun `tool exposure audit includes risk metadata when security is present`() { runBlocking {
        val emitter = capturingEmitter()
        val tool = DummyTool(
            "lookup",
            ToolSecurityMetadata(
                permission = "execute.payment",
                risk = RiskLevel.HIGH,
                approval = ApprovalMode.HUMAN_REQUIRED,
                managedNetworkEgress = ManagedNetworkEgress.ALLOWLIST_ONLY,
                audit = AuditDetail.FULL,
            ),
        )
        val engine = TramaiEngine(
            provider = DummyCountingProvider(),
            toolRegistry = ToolRegistry(mapOf("lookup" to tool)),
            policyDecisionAuditEmitter = emitter,
            policyEngine = policyEngine,
        )
        val service = engine.create<SingleToolService>()

        service.respond("pay invoice")

        val toolExposureCalls = emitter.calls.filter {
            it.first == EnforcementPoint.BEFORE_TOOL_EXPOSURE
        }
        Assertions.assertEquals(1, toolExposureCalls.size)
        Assertions.assertNotNull(toolExposureCalls[0].second.toolSecurity)
        Assertions.assertEquals(RiskLevel.HIGH, toolExposureCalls[0].second.toolSecurity!!.risk)
    }
    }

    @Test
    fun `audit failure at tool exposure blocks provider invocation`() { runBlocking {
        val provider = DummyCountingProvider()
        val tool = DummyTool("lookup")
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("lookup" to tool)),
            policyDecisionAuditEmitter = object : PolicyDecisionAuditEmitter {
                override suspend fun emit(
                    enforcementPoint: EnforcementPoint,
                    context: PolicyContext,
                    decision: PolicyDecision,
                ) {
                    if (enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXPOSURE) {
                        throw RuntimeException("Audit storage failure")
                    }
                }
            },
            policyEngine = policyEngine,
        )
        val service = engine.create<SingleToolService>()

        val failure = try {
            service.respond("query")
            null
        } catch (error: RuntimeException) {
            error
        }

        assertThat(failure)
            .isNotNull
            .hasMessage("Audit storage failure")
        assertThat(provider.callCount.get()).isZero()
    }
    }

    @Test
    fun `tool exposure ALLOW decision is auditable`() { runBlocking {
        val emitter = capturingEmitter()
        val engine = TramaiEngine(
            provider = DummyCountingProvider(),
            toolRegistry = ToolRegistry(mapOf("lookup" to DummyTool("lookup"))),
            policyDecisionAuditEmitter = emitter,
            policyEngine = policyEngine,
        )
        val service = engine.create<SingleToolService>()

        service.respond("test")

        val toolExposureCalls = emitter.calls.filter {
            it.first == EnforcementPoint.BEFORE_TOOL_EXPOSURE
        }
        Assertions.assertEquals(1, toolExposureCalls.size)
        Assertions.assertTrue(toolExposureCalls[0].third is PolicyDecision.Allow)
    }
    }
}
