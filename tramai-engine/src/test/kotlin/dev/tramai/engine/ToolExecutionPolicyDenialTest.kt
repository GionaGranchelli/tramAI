package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.policy.*
import dev.tramai.core.provider.ModelProvider
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Engine-level tests proving fail-closed behavior when policy denies
 * a model-requested tool at [EnforcementPoint.BEFORE_TOOL_EXECUTION].
 *
 * These tests verify that:
 * - Exposure can be allowed while execution is independently denied
 * - A denied tool never executes (callCount == 0)
 * - Denial is audited before the exception escapes
 * - No tool-result reinjection occurs after denial
 * - The provider is not called again after denial
 * - Audit failure at execution prevents tool execution
 * - Policy is reevaluated before every retry
 * - A later denied tool stops remaining processing deterministically
 *
 * Argument-level tool authorization is not covered: BEFORE_TOOL_EXECUTION
 * receives toolName and toolSecurity but not raw tool arguments. Decisions
 * are based on tool identity, metadata, and risk — not argument values.
 */
class ToolExecutionPolicyDenialTest {

    // --- Test infrastructure --------------------------------------------------

    @AiService
    interface SingleToolService {
        @Operation(prompt = "test", model = "test-model", tools = ["payment"], providerRetries = 0)
        suspend fun respond(input: String): String
    }

    @AiService
    interface MultiToolService {
        @Operation(prompt = "test", model = "test-model", tools = ["lookup", "payment"], providerRetries = 0)
        suspend fun respond(input: String): String
    }

    @AiService
    interface IdempotentToolService {
        @Operation(prompt = "test", model = "test-model", tools = ["retryable"], providerRetries = 0)
        suspend fun respond(input: String): String
    }

    private class ToolCallProvider(
        private val toolName: String,
        private val toolCallArgs: String = "{}",
        private val firstCallTool: Boolean = true,
        private val finalContent: String = "done",
    ) : ModelProvider {
        val callCount = AtomicInteger(0)

        override fun providerId() = "test-provider"

        override suspend fun complete(request: ModelRequest): ModelResponse {
            val count = callCount.incrementAndGet()
            return if (firstCallTool && count == 1) {
                ModelResponse(
                    content = "",
                    inputTokens = 10,
                    outputTokens = 5,
                    modelUsed = request.model,
                    toolCalls = listOf(ToolCall("call-1", toolName, toolCallArgs)),
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

    private class MultiToolCallProvider : ModelProvider {
        val callCount = AtomicInteger(0)

        override fun providerId() = "test-provider"

        override suspend fun complete(request: ModelRequest): ModelResponse {
            return if (callCount.incrementAndGet() == 1) {
                ModelResponse(
                    content = "",
                    inputTokens = 10,
                    outputTokens = 5,
                    modelUsed = request.model,
                    toolCalls = listOf(
                        ToolCall("call-1", "lookup", "{}"),
                        ToolCall("call-2", "payment", "{\"amount\":5000}"),
                    ),
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
    }

    private class CountingTool(
        override val name: String,
        private val failFirst: Boolean = false,
    ) : ResolvedTool {
        val callCount = AtomicInteger(0)
        var lastInput: Any? = null

        override val description: String = "Test tool"
        override val inputSchemaJson: String = "{}"
        override val idempotent: Boolean = true
        override val sideEffectLevel = SideEffectLevel.READ_ONLY
        override val security: ToolSecurityMetadata = ToolSecurityMetadata(
            permission = "$name.execute",
            risk = RiskLevel.HIGH,
            approval = ApprovalMode.HUMAN_REQUIRED,
            managedNetworkEgress = ManagedNetworkEgress.ALLOWLIST_ONLY,
            audit = AuditDetail.FULL,
        )

        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            callCount.incrementAndGet()
            lastInput = input
            return if (failFirst && callCount.get() == 1) {
                ToolResult.TransientFailure(RuntimeException("transient error"))
            } else {
                ToolResult.Success("{}")
            }
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

    private fun policyThatDeniesExecution(reasonCode: String = "tool-execution-denied") =
        PolicyEngine { context ->
            if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                PolicyDecision.Deny(reason = reasonCode, reasonCode = reasonCode)
            } else {
                PolicyDecision.Allow
            }
        }

    // --- Test 1: Exposure allowed, execution denied ---------------------------

    @Test
    fun `exposure allowed but execution denied`() = runTest {
        val tool = CountingTool("payment")
        val provider = ToolCallProvider("payment")
        val emitter = capturingEmitter()
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("payment" to tool)),
            policyDecisionAuditEmitter = emitter,
            policyEngine = policyThatDeniesExecution(),
        )
        val service = engine.create<SingleToolService>()

        try {
            service.respond("pay invoice")
        } catch (e: PolicyViolationException) {
            assertThat(e).hasMessageContaining("tool-execution-denied")
        }

        assertEquals(1, provider.callCount.get(), "Provider should be called once to request the tool")
        assertEquals(0, tool.callCount.get(), "Tool should never execute")
    }

    // --- Test 2: Policy context contains canonical tool metadata --------------

    @Test
    fun `execution policy context contains canonical tool metadata`() = runTest {
        val tool = CountingTool("payment")
        val provider = ToolCallProvider("payment")
        val emitter = capturingEmitter()
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("payment" to tool)),
            policyDecisionAuditEmitter = emitter,
            policyEngine = policyThatDeniesExecution(),
        )
        val service = engine.create<SingleToolService>()

        try { service.respond("pay") } catch (_: PolicyViolationException) {}

        val execCalls = emitter.calls.filter {
            it.first == EnforcementPoint.BEFORE_TOOL_EXECUTION
        }
        assertEquals(1, execCalls.size)

        val ctx = execCalls[0].second
        assertEquals(EnforcementPoint.BEFORE_TOOL_EXECUTION, ctx.enforcementPoint)
        assertEquals("payment", ctx.toolName)
        assertNotNull(ctx.toolSecurity)
        assertEquals(RiskLevel.HIGH, ctx.toolSecurity!!.risk)
        assertEquals("payment.execute", ctx.toolSecurity!!.permission)
    }

    // --- Test 3: Denial audit happens before exception propagation ------------

    @Test
    fun `denial audit emitted before PolicyViolationException reaches caller`() = runTest {
        val tool = CountingTool("payment")
        val provider = ToolCallProvider("payment")
        val emitter = capturingEmitter()
        var exceptionThrown = false
        var execDenialAudited = false

        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("payment" to tool)),
            policyDecisionAuditEmitter = emitter,
            policyEngine = policyThatDeniesExecution(),
        )
        val service = engine.create<SingleToolService>()

        try {
            service.respond("pay")
        } catch (e: PolicyViolationException) {
            exceptionThrown = true
            execDenialAudited = emitter.calls.any {
                it.first == EnforcementPoint.BEFORE_TOOL_EXECUTION && it.third is PolicyDecision.Deny
            }
        }

        assertTrue(exceptionThrown, "PolicyViolationException should be thrown")
        assertTrue(execDenialAudited, "Execution denial audit should be emitted before exception propagation")
        assertEquals(0, tool.callCount.get(), "Tool should never execute")
    }

    // --- Test 4: Denial prevents reinjection policy evaluation ----------------

    @Test
    fun `execution denial prevents tool result reinjection`() = runTest {
        val tool = CountingTool("payment")
        val provider = ToolCallProvider("payment")
        val emitter = capturingEmitter()
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("payment" to tool)),
            policyDecisionAuditEmitter = emitter,
            policyEngine = policyThatDeniesExecution(),
        )
        val service = engine.create<SingleToolService>()

        try { service.respond("pay") } catch (_: PolicyViolationException) {}

        val visitedPoints = emitter.calls.map { it.first }.toSet()

        // These should be present
        assertTrue(visitedPoints.contains(EnforcementPoint.BEFORE_TOOL_EXPOSURE))
        assertTrue(visitedPoints.contains(EnforcementPoint.BEFORE_TOOL_EXECUTION))

        // These must NOT be present after a denied execution
        assertTrue(
            !visitedPoints.contains(EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION),
            "BEFORE_TOOL_RESULT_REINJECTION must not fire after denied execution",
        )
        assertTrue(
            !visitedPoints.contains(EnforcementPoint.BEFORE_RESPONSE_RETURN),
            "BEFORE_RESPONSE_RETURN must not fire after denied execution",
        )
    }

    // --- Test 5: No provider continuation after denial ------------------------

    @Test
    fun `provider is not called again after execution denial`() = runTest {
        val tool = CountingTool("payment")
        val provider = ToolCallProvider("payment")
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("payment" to tool)),
            policyEngine = policyThatDeniesExecution(),
        )
        val service = engine.create<SingleToolService>()

        try { service.respond("pay") } catch (_: PolicyViolationException) {}

        assertEquals(1, provider.callCount.get(),
            "Provider should be called exactly once — no continuation after denial")
    }

    // --- Test 6: Audit failure at execution blocks the tool -------------------

    @Test
    fun `audit failure at execution prevents tool execution`() = runTest {
        val tool = CountingTool("payment")
        val provider = ToolCallProvider("payment")
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("payment" to tool)),
            policyEngine = PolicyEngine { PolicyDecision.Allow },
            policyDecisionAuditEmitter = object : PolicyDecisionAuditEmitter {
                override suspend fun emit(
                    enforcementPoint: EnforcementPoint,
                    context: PolicyContext,
                    decision: PolicyDecision,
                ) {
                    if (enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                        throw RuntimeException("Audit storage failure")
                    }
                }
            },
        )
        val service = engine.create<SingleToolService>()

        try {
            service.respond("pay")
        } catch (e: RuntimeException) {
            assertThat(e).hasMessage("Audit storage failure")
        }

        assertEquals(0, tool.callCount.get(), "Tool must not execute when audit fails")
        assertEquals(1, provider.callCount.get(), "Provider should be called once")
    }

    // --- Test 7: Policy is reevaluated before every retry ---------------------

    @Test
    fun `policy is reevaluated and denial respected between retries`() = runTest {
        val tool = CountingTool("payment", failFirst = true)
        val provider = ToolCallProvider("payment")
        val evalCount = AtomicInteger(0)
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("payment" to tool)),
            policyEngine = PolicyEngine { context ->
                if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                    val count = evalCount.incrementAndGet()
                    if (count == 2) {
                        PolicyDecision.Deny(reason = "revoked", reasonCode = "tool-permission-revoked")
                    } else {
                        PolicyDecision.Allow
                    }
                } else {
                    PolicyDecision.Allow
                }
            },
        )
        val service = engine.create<SingleToolService>()

        try { service.respond("pay") } catch (e: PolicyViolationException) {
            assertThat(e).hasMessageContaining("revoked")
        }

        assertEquals(2, evalCount.get(), "Policy should be evaluated twice (once per attempt)")
        assertEquals(1, tool.callCount.get(), "Tool should execute once (first attempt) before denial on retry")
    }

    // --- Test 8: Multiple tool calls stop deterministically at denial ---------

    @Test
    fun `later denied tool stops processing after earlier tools complete`() = runTest {
        val lookup = CountingTool("lookup")
        val payment = CountingTool("payment")
        val provider = MultiToolCallProvider()
        val emitter = capturingEmitter()
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("lookup" to lookup, "payment" to payment)),
            policyDecisionAuditEmitter = emitter,
            policyEngine = PolicyEngine { context ->
                if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                    if (context.toolName == "payment") {
                        PolicyDecision.Deny(reason = "payment denied", reasonCode = "payment-denied")
                    } else {
                        PolicyDecision.Allow
                    }
                } else {
                    PolicyDecision.Allow
                }
            },
        )
        val service = engine.create<MultiToolService>()

        try { service.respond("process") } catch (e: PolicyViolationException) {
            assertThat(e).hasMessageContaining("payment denied")
        }

        // lookup-tool executed successfully before payment was denied
        assertEquals(1, lookup.callCount.get(), "lookup tool should execute once")
        assertEquals(0, payment.callCount.get(), "payment tool should never execute")
        assertEquals(1, provider.callCount.get(), "Provider should not continue after denial")
    }
}
