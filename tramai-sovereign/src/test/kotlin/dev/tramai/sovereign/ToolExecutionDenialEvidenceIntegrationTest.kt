package dev.tramai.sovereign

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
import dev.tramai.engine.TramaiEngine
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.create
import dev.tramai.security.audit.AuditChainVerifier
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter
import dev.tramai.security.audit.AuditStreamIdResolver
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.evidence.PolicyDecisionRuntimeEvidenceExporter
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * Composed integration tests proving that tool execution denials produce
 * durable audit records and safe generic runtime evidence through the full
 * end-to-end chain: TramaiEngine → PolicyEnforcementHelper →
 * AuditEnginePolicyDecisionAuditEmitter → AuditEngine → InMemoryAuditStore →
 * PolicyDecisionRuntimeEvidenceExporter.
 *
 * These tests exercise the *existing* generic policy.decision evidence path.
 * A dedicated tool.permission event type and tool-permissions.jsonl are not
 * yet implemented.
 */
class ToolExecutionDenialEvidenceIntegrationTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneId.of("UTC"))

    // --- Test infrastructure --------------------------------------------------

    @AiService
    interface PaymentService {
        @Operation(prompt = "test", model = "test-model", tools = ["payment"], providerRetries = 0)
        suspend fun respond(input: String): String
    }

    private class ToolCallProvider : ModelProvider {
        val callCount = AtomicInteger(0)

        override fun providerId() = "test-provider"

        override suspend fun complete(request: ModelRequest): ModelResponse {
            return if (callCount.incrementAndGet() == 1) {
                ModelResponse(
                    content = "",
                    inputTokens = 10,
                    outputTokens = 5,
                    modelUsed = request.model,
                    toolCalls = listOf(ToolCall(
                        "call-1",
                        "payment",
                        """{"account":"NL00BANK0123456789","apiKey":"secret-value","amount":5000}""",
                    )),
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

    private class PaymentTool : ResolvedTool {
        val callCount = AtomicInteger(0)

        override val name: String = "payment"
        override val description: String = "Executes a payment"
        override val inputSchemaJson: String = "{}"
        override val idempotent: Boolean = false
        override val sideEffectLevel = SideEffectLevel.WRITE
        override val security: ToolSecurityMetadata = ToolSecurityMetadata(
            permission = "payment.execute",
            risk = RiskLevel.HIGH,
            approval = ApprovalMode.HUMAN_REQUIRED,
            managedNetworkEgress = ManagedNetworkEgress.ALLOWLIST_ONLY,
            audit = AuditDetail.FULL,
        )

        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            callCount.incrementAndGet()
            return ToolResult.Success("{}")
        }
    }

    private val fixedStreamId = "tool-execution-denial-test"

    private fun buildEngineChain(): Triple<TramaiEngine, InMemoryAuditStore, AuditEngine> {
        val store = InMemoryAuditStore()
        val auditEngine = AuditEngine(store, clock = fixedClock)
        val resolver = object : AuditStreamIdResolver {
            override fun resolve(context: PolicyContext): String = fixedStreamId
        }
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine, streamIdResolver = resolver)
        val engine = TramaiEngine(
            provider = ToolCallProvider(),
            toolRegistry = ToolRegistry(mapOf("payment" to PaymentTool())),
            policyDecisionAuditEmitter = emitter,
            policyEngine = PolicyEngine { context ->
                if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                    PolicyDecision.Deny(reason = "tool execution denied", reasonCode = "tool-execution-denied")
                } else {
                    PolicyDecision.Allow
                }
            },
        )
        return Triple(engine, store, auditEngine)
    }

    // --- Test 9: Execution denial produces durable audit record ----------------

    @Test
    fun `execution denial produces durable audit record with safe metadata`() = runTest {
        val (engine, store, _) = buildEngineChain()
        val service = engine.create<PaymentService>()

        try { service.respond("pay invoice") } catch (_: PolicyViolationException) {}

        val events = store.readStream(fixedStreamId)

        val execDenialEvents = events.filter { it.enforcementPoint == "BEFORE_TOOL_EXECUTION" }
        assertEquals(1, execDenialEvents.size, "Expected exactly one BEFORE_TOOL_EXECUTION audit event")

        val event = execDenialEvents[0]
        assertEquals("DENY", event.decision)
        assertEquals("tool-execution-denied", event.reasonCode)
        assertEquals("payment", event.metadata["toolName"],
            "toolName must be present in safe audit metadata")
        assertEquals("HIGH", event.metadata["riskLevel"],
            "riskLevel must be present in safe audit metadata")
    }

    // --- Test 10: Denied execution exports as safe generic evidence ------------

    @Test
    fun `denied execution exports as safe generic policy decision evidence`() = runTest {
        val (engine, store, _) = buildEngineChain()
        val service = engine.create<PaymentService>()

        try { service.respond("pay invoice") } catch (_: PolicyViolationException) {}

        val events = store.readStream(fixedStreamId)
        val exporter = PolicyDecisionRuntimeEvidenceExporter()
        val records = exporter.export(events)

        val execDenialRecords = records.filter {
            it.decision.kind == "DENY" && it.metadata["toolName"] == "payment"
        }
        assertEquals(1, execDenialRecords.size, "Expected one exported DENY record for payment tool")

        val record = execDenialRecords[0]
        assertEquals("policy.decision", record.eventType)
        assertEquals("DENY", record.decision.kind)
        assertEquals("tool-execution-denied", record.decision.reasonCode)
        assertEquals("policy-engine", record.source.component)
        assertEquals("payment", record.metadata["toolName"])
        assertEquals("HIGH", record.metadata["riskLevel"])
        assertNotNull(record.digests.subjectDigest)
        assertNotNull(record.digests.payloadDigest)
    }

    // --- Test 11: Raw tool arguments absent from audit and evidence ------------

    @Test
    fun `raw tool arguments and secrets are absent from audit and evidence`() = runTest {
        val (engine, store, _) = buildEngineChain()
        val service = engine.create<PaymentService>()

        try { service.respond("pay invoice") } catch (_: PolicyViolationException) {}

        val events = store.readStream(fixedStreamId)
        val exporter = PolicyDecisionRuntimeEvidenceExporter()
        val records = exporter.export(events)

        val sensitiveValues = setOf(
            "NL00BANK0123456789", "secret-value", "apiKey", "amount",
            "5000", "toolArguments", "account",
        )

        for (event in events) {
            for (key in event.metadata.keys) {
                for (sensitive in sensitiveValues) {
                    assertFalse(
                        event.metadata[key]?.contains(sensitive) ?: false,
                        "Audit event metadata must not contain '$sensitive'",
                    )
                }
            }
        }

        for (record in records) {
            for (key in record.metadata.keys) {
                for (sensitive in sensitiveValues) {
                    assertFalse(
                        record.metadata[key]?.contains(sensitive) ?: false,
                        "Evidence record metadata must not contain '$sensitive'",
                    )
                }
            }
        }

        // Only safe tool metadata should be durable
        val execDenialEvent = events.first { it.enforcementPoint == "BEFORE_TOOL_EXECUTION" }
        assertNotNull(execDenialEvent.metadata["toolName"])
        assertNotNull(execDenialEvent.metadata["riskLevel"])
        assertNull(execDenialEvent.metadata["attr_apiKey"])
        assertNull(execDenialEvent.metadata["attr_toolArguments"])
    }

    // --- Test 12: Denial evidence chain remains verifiable ---------------------

    @Test
    fun `denial evidence chain passes AuditChainVerifier`() = runTest {
        val (engine, store, _) = buildEngineChain()
        val service = engine.create<PaymentService>()

        try { service.respond("pay invoice") } catch (_: PolicyViolationException) {}

        val events = store.readStream(fixedStreamId)

        val execEvents = events.filter { it.enforcementPoint == "BEFORE_TOOL_EXECUTION" }
        assertTrue(execEvents.isNotEmpty(), "Expected at least one execution enforcement event")

        val result = AuditChainVerifier.verify(events)
        assertTrue(result.isValid, "Audit chain verification failed: ${result.errors}")

        // Verify that the denial event participates in the chain
        val denialEvent = execEvents.first { it.decision == "DENY" }
        assertNotNull(denialEvent.eventId)
        assertNotNull(denialEvent.previousEventHash, "Denied event must have previous hash in chain")
        assertNotNull(denialEvent.eventHash, "Denied event must have its own hash in chain")
    }
}
