package dev.tramai.examples.toolgovernance

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.policy.*
import dev.tramai.engine.TramaiEngine
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.create
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
import dev.tramai.security.approval.AllowAnyApprovalDecisionValidator
import dev.tramai.security.approval.DefaultApprovalGateCoordinator
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalStore
import dev.tramai.security.approval.SecureRandomApprovalTokenGenerator
import dev.tramai.security.approval.Sha256ApprovalTokenDigester
import dev.tramai.security.approval.UuidApprovalIdGenerator
import dev.tramai.security.audit.*
import dev.tramai.security.evidence.PolicyDecisionRuntimeEvidenceExporter
import dev.tramai.security.evidence.RuntimeEvidenceJsonlWriter
import dev.tramai.security.evidence.ToolPermissionRuntimeEvidenceExporter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Deterministic tests for the tool governance example module.
 *
 * Each test exercises one permission outcome (ALLOW, DENY, REQUIRE_APPROVAL)
 * and verifies that the correct enforcement point decisions are emitted
 * to the dedicated tool.permission evidence family.
 *
 * No real models, no Docker, no PostgreSQL, no network — fully deterministic.
 */
class ToolGovernanceExampleTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneId.of("UTC"))

    // ── Service interfaces ──────────────────────────────────────────────

    @AiService
    interface SingleToolService {
        @Operation(prompt = "test", model = "test-model", tools = ["customer_lookup"], providerRetries = 0)
        suspend fun lookup(input: String): String
    }

    @AiService
    interface DeleteToolService {
        @Operation(prompt = "test", model = "test-model", tools = ["account_delete"], providerRetries = 0)
        suspend fun delete(input: String): String
    }

    @AiService
    interface PaymentToolService {
        @Operation(prompt = "test", model = "test-model", tools = ["payment"], providerRetries = 0)
        suspend fun pay(input: String): String
    }

    // ── Fixture builders ─────────────────────────────────────────────────

    private fun allowFixture(): Fixture {
        val store = InMemoryAuditStore()
        val auditEngine = AuditEngine(store, clock = fixedClock)
        val streamId = "test-customer-lookup"
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine, streamIdResolver = AuditStreamIdResolver(streamId))
        val tool = CustomerLookupTool()
        val engine = TramaiEngine(
            provider = DeterministicToolProvider("customer_lookup"),
            toolRegistry = ToolRegistry(mapOf("customer_lookup" to tool)),
            policyDecisionAuditEmitter = emitter,
            policyEngine = DefaultPolicyEngine(PolicyConfiguration.preview()),
        )
        return Fixture(engine, store, tool, streamId)
    }

    private fun denyFixture(): Fixture {
        val store = InMemoryAuditStore()
        val auditEngine = AuditEngine(store, clock = fixedClock)
        val streamId = "test-account-delete"
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine, streamIdResolver = AuditStreamIdResolver(streamId))
        val tool = AccountDeleteTool()
        val baselinePolicy = DefaultPolicyEngine(PolicyConfiguration.preview())
        val engine = TramaiEngine(
            provider = DeterministicToolProvider("account_delete"),
            toolRegistry = ToolRegistry(mapOf("account_delete" to tool)),
            policyDecisionAuditEmitter = emitter,
            policyEngine = PolicyEngine { context ->
                if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION && context.toolName == "account_delete") {
                    PolicyDecision.Deny(reason = "Account deletion is disabled in this environment", reasonCode = "account-delete-disabled")
                } else {
                    baselinePolicy.evaluate(context)
                }
            },
        )
        return Fixture(engine, store, tool, streamId)
    }

    private fun requireApprovalFixture(): Fixture {
        val store = InMemoryAuditStore()
        val auditEngine = AuditEngine(store, clock = fixedClock)
        val streamId = "test-payment"
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine, streamIdResolver = AuditStreamIdResolver(streamId))
        val tool = PaymentTool()
        val approvalGateCoordinator = DefaultApprovalGateCoordinator(
            store = InMemoryApprovalStore(clock = fixedClock),
            approvalIdGenerator = UuidApprovalIdGenerator(),
            approvalTokenGenerator = SecureRandomApprovalTokenGenerator(),
            approvalTokenDigester = Sha256ApprovalTokenDigester(),
            decisionValidator = AllowAnyApprovalDecisionValidator,
            clock = fixedClock,
        )
        val engine = TramaiEngine(
            provider = DeterministicToolProvider("payment"),
            toolRegistry = ToolRegistry(mapOf("payment" to tool)),
            policyDecisionAuditEmitter = emitter,
            policyEngine = DefaultPolicyEngine(PolicyConfiguration.preview()),
            toolArgumentsDigester = ToolArgumentsDigester {
                val hex = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(it.reveal().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                Sha256Digest.of("sha256:$hex")
            },
            approvalGateCoordinator = approvalGateCoordinator,
            approvalContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock),
            clock = fixedClock,
        )
        return Fixture(engine, store, tool, streamId)
    }

    private data class Fixture(
        val engine: TramaiEngine,
        val store: InMemoryAuditStore,
        val tool: Any,
        val streamId: String,
    )

    // ── Test 1: ALLOW scenario (customer_lookup) ─────────────────────────

    @Test
    fun `customer lookup tool is allowed at all enforcement points`() = runTest {
        val (engine, store, tool, streamId) = allowFixture()
        val service = engine.create<SingleToolService>()
        val lookupTool = tool as CustomerLookupTool

        val result = service.lookup("CUST-001")
        assertEquals("done", result)
        assertEquals(1, lookupTool.callCount.get(), "Tool must execute exactly once")

        val events = store.readStream(streamId)

        // Check exposure was allowed
        val exposureEvents = events.filter { it.enforcementPoint == "BEFORE_TOOL_EXPOSURE" }
        assertTrue(exposureEvents.isNotEmpty(), "Must have BEFORE_TOOL_EXPOSURE events")
        assertEquals("ALLOW", exposureEvents.first().decision)

        // Check execution was allowed
        val execEvents = events.filter { it.enforcementPoint == "BEFORE_TOOL_EXECUTION" }
        assertTrue(execEvents.isNotEmpty(), "Must have BEFORE_TOOL_EXECUTION events")
        assertEquals("ALLOW", execEvents.first().decision)

        // Check reinjection was allowed
        val reinjectEvents = events.filter { it.enforcementPoint == "BEFORE_TOOL_RESULT_REINJECTION" }
        assertTrue(reinjectEvents.isNotEmpty(), "Must have BEFORE_TOOL_RESULT_REINJECTION events")
        assertEquals("ALLOW", reinjectEvents.first().decision)

        // Verify tool.permission evidence family
        val toolExporter = ToolPermissionRuntimeEvidenceExporter()
        val toolRecords = toolExporter.export(events)
        val allowRecords = toolRecords.filter { it.metadata["toolName"] == "customer_lookup" }
        assertTrue(allowRecords.isNotEmpty(), "Must have tool.permission records for customer_lookup")
        assertEquals("tool.permission", allowRecords.first().eventType)
    }

    // ── Test 2: DENY scenario (account_delete) ───────────────────────────

    @Test
    fun `account delete tool is denied at execution`() = runTest {
        val (engine, store, tool, streamId) = denyFixture()
        val service = engine.create<DeleteToolService>()
        val deleteTool = tool as AccountDeleteTool

        try {
            service.delete("ACC-001")
            fail("Expected PolicyViolationException")
        } catch (_: PolicyViolationException) {
            // expected
        }
        assertEquals(0, deleteTool.callCount.get(), "Tool must never execute")

        val events = store.readStream(streamId)

        // Exposure should be ALLOW
        val exposureEvents = events.filter { it.enforcementPoint == "BEFORE_TOOL_EXPOSURE" }
        assertTrue(exposureEvents.isNotEmpty(), "Must have BEFORE_TOOL_EXPOSURE events")

        // Execution should be DENY
        val execEvents = events.filter { it.enforcementPoint == "BEFORE_TOOL_EXECUTION" }
        assertTrue(execEvents.isNotEmpty(), "Must have BEFORE_TOOL_EXECUTION events")
        assertEquals("DENY", execEvents.first().decision)
        assertEquals("account-delete-disabled", execEvents.first().reasonCode)

        // No reinjection after denial
        val reinjectEvents = events.filter { it.enforcementPoint == "BEFORE_TOOL_RESULT_REINJECTION" }
        assertTrue(reinjectEvents.isEmpty(), "No reinjection after denied execution")

        // Verify tool.permission evidence
        val toolExporter = ToolPermissionRuntimeEvidenceExporter()
        val toolRecords = toolExporter.export(events)
        val denyRecords = toolRecords.filter { it.decision.kind == "DENY" && it.metadata["toolName"] == "account_delete" }
        assertEquals(1, denyRecords.size, "Must have exactly one DENY record for account_delete")
        assertEquals("tool.permission", denyRecords.first().eventType)
        assertEquals("BEFORE_TOOL_EXECUTION", denyRecords.first().metadata["enforcementPoint"])
    }

    // ── Test 3: REQUIRE_APPROVAL scenario (payment) ──────────────────────

    @Test
    fun `payment tool requires approval at execution`() = runTest {
        val (engine, store, tool, streamId) = requireApprovalFixture()
        val service = engine.create<PaymentToolService>()
        val paymentTool = tool as PaymentTool

        try {
            service.pay("5000 EUR")
            fail("Expected ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            assertEquals("payment", e.toolName)
        }
        assertEquals(0, paymentTool.callCount.get(), "Tool must never execute")

        val events = store.readStream(streamId)

        // Exposure should be ALLOW
        val exposureEvents = events.filter { it.enforcementPoint == "BEFORE_TOOL_EXPOSURE" }
        assertTrue(exposureEvents.isNotEmpty(), "Must have BEFORE_TOOL_EXPOSURE events")

        // Execution should be REQUIRE_APPROVAL
        val execEvents = events.filter { it.enforcementPoint == "BEFORE_TOOL_EXECUTION" }
        assertTrue(execEvents.isNotEmpty(), "Must have BEFORE_TOOL_EXECUTION events")
        assertEquals("REQUIRE_APPROVAL", execEvents.first().decision)

        // No reinjection after suspension
        val reinjectEvents = events.filter { it.enforcementPoint == "BEFORE_TOOL_RESULT_REINJECTION" }
        assertTrue(reinjectEvents.isEmpty(), "No reinjection after suspended execution")

        // Verify tool.permission evidence
        val toolExporter = ToolPermissionRuntimeEvidenceExporter()
        val toolRecords = toolExporter.export(events)
        val requireApprovalRecords = toolRecords.filter {
            it.decision.kind == "REQUIRE_APPROVAL" && it.metadata["toolName"] == "payment"
        }
        assertEquals(1, requireApprovalRecords.size, "Must have exactly one REQUIRE_APPROVAL record for payment")
        assertEquals("tool.permission", requireApprovalRecords.first().eventType)
        assertEquals("BEFORE_TOOL_EXECUTION", requireApprovalRecords.first().metadata["enforcementPoint"])
    }

    // ── Test 4: Tool events excluded from policy.decision evidence ───────

    @Test
    fun `tool enforcement events are excluded from policy decision evidence`() = runTest {
        val (engine, store, tool, streamId) = allowFixture()
        val service = engine.create<SingleToolService>()

        service.lookup("CUST-001")

        val events = store.readStream(streamId)
        val policyExporter = PolicyDecisionRuntimeEvidenceExporter()
        val policyRecords = policyExporter.export(events)
        val toolExporter = ToolPermissionRuntimeEvidenceExporter()
        val toolRecords = toolExporter.export(events)

        // Event-ID based partition check: tool audit events must not appear in policy.decision evidence
        val toolEnforcementPoints = setOf("BEFORE_TOOL_EXPOSURE", "BEFORE_TOOL_EXECUTION", "BEFORE_TOOL_RESULT_REINJECTION")
        val toolAuditEventIds = events
            .filter { it.enforcementPoint in toolEnforcementPoints }
            .map { it.eventId }
            .toSet()

        assertTrue(
            policyRecords.none { it.eventId in toolAuditEventIds },
            "Tool audit events must not appear in policy.decision evidence"
        )

        assertEquals(
            toolAuditEventIds,
            toolRecords.map { it.eventId }.toSet(),
            "Every tool audit event must appear in tool.permission evidence"
        )
    }

    // ── Test 5: Evidence isolation across three tools ────────────────────

    @Test
    fun `three tools produce distinct audit streams with correct evidence`() = runTest {
        // Run all three scenarios
        val (engine1, store1, _, streamId1) = allowFixture()
        val service1 = engine1.create<SingleToolService>()
        service1.lookup("CUST-001")

        val (engine2, store2, _, streamId2) = denyFixture()
        val service2 = engine2.create<DeleteToolService>()
        try { service2.delete("ACC-001") } catch (_: PolicyViolationException) {}

        val (engine3, store3, _, streamId3) = requireApprovalFixture()
        val service3 = engine3.create<PaymentToolService>()
        try { service3.pay("5000 EUR") } catch (_: ApprovalSuspendedException) {}

        // Verify each stream has the correct evidence
        val toolExporter = ToolPermissionRuntimeEvidenceExporter()

        // Stream 1: ALLOW for customer_lookup
        val events1 = store1.readStream(streamId1)
        val toolRecords1 = toolExporter.export(events1)
        assertTrue(toolRecords1.any { it.decision.kind == "ALLOW" && it.metadata["toolName"] == "customer_lookup" })

        // Stream 2: DENY for account_delete
        val events2 = store2.readStream(streamId2)
        val toolRecords2 = toolExporter.export(events2)
        assertTrue(toolRecords2.any { it.decision.kind == "DENY" && it.metadata["toolName"] == "account_delete" })

        // Stream 3: REQUIRE_APPROVAL for payment
        val events3 = store3.readStream(streamId3)
        val toolRecords3 = toolExporter.export(events3)
        assertTrue(toolRecords3.any { it.decision.kind == "REQUIRE_APPROVAL" && it.metadata["toolName"] == "payment" })
    }

    // ── Test 6: Provider not called again after denial ───────────────────

    @Test
    fun `provider is not called again after execution denial`() = runTest {
        val store = InMemoryAuditStore()
        val auditEngine = AuditEngine(store, clock = fixedClock)
        val tool = AccountDeleteTool()
        val provider = DeterministicToolProvider("account_delete")
        val baselinePolicy = DefaultPolicyEngine(PolicyConfiguration.preview())
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("account_delete" to tool)),
            policyDecisionAuditEmitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine, AuditStreamIdResolver("test-no-provider-continuation")),
            policyEngine = PolicyEngine { context ->
                if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION && context.toolName == "account_delete") {
                    PolicyDecision.Deny(reason = "denied", reasonCode = "test-denied")
                } else {
                    baselinePolicy.evaluate(context)
                }
            },
        )
        val service = engine.create<DeleteToolService>()

        try {
            service.delete("ACC-001")
            fail("Expected PolicyViolationException")
        } catch (_: PolicyViolationException) {
            // expected
        }
        assertEquals(1, provider.callCount.get(), "Provider must be called exactly once")
    }

    // ── Test 7: Customer lookup tool security metadata is correct ────────

    @Test
    fun `customer lookup tool has correct security metadata`() {
        val tool = CustomerLookupTool()
        assertNotNull(tool.security)
        assertEquals("customer.read", tool.security!!.permission)
        assertEquals(RiskLevel.LOW, tool.security!!.risk)
        assertEquals(ApprovalMode.AUTO, tool.security!!.approval)
    }

    // ── Test 8: Account delete tool security metadata is correct ────────

    @Test
    fun `account delete tool has correct security metadata`() {
        val tool = AccountDeleteTool()
        assertNotNull(tool.security)
        assertEquals("account.delete", tool.security!!.permission)
        assertEquals(RiskLevel.CRITICAL, tool.security!!.risk)
        assertEquals(ApprovalMode.HUMAN_REQUIRED, tool.security!!.approval)
    }

    // ── Test 9: Payment tool security metadata is correct ────────────────

    @Test
    fun `payment tool has correct security metadata`() {
        val tool = PaymentTool()
        assertNotNull(tool.security)
        assertEquals("payment.execute", tool.security!!.permission)
        assertEquals(RiskLevel.HIGH, tool.security!!.risk)
        assertEquals(ApprovalMode.HUMAN_REQUIRED, tool.security!!.approval)
    }

    // ── Test 10: Security metadata tests are above. Deterministic tool ──
    //     provider is exercised by the scenario tests; no isolated test needed.

    // ── Test 11: Sensitive arguments never appear in serialized evidence ──

    @Test
    fun `sensitive tool arguments never appear in serialized evidence`() = runTest {
        // Configure a fixture with deliberately sensitive arguments
        val store = InMemoryAuditStore()
        val auditEngine = AuditEngine(store, clock = fixedClock)
        val streamId = "test-privacy"
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine, streamIdResolver = AuditStreamIdResolver(streamId))
        val tool = PaymentTool()
        val approvalGateCoordinator = DefaultApprovalGateCoordinator(
            store = InMemoryApprovalStore(clock = fixedClock),
            approvalIdGenerator = UuidApprovalIdGenerator(),
            approvalTokenGenerator = SecureRandomApprovalTokenGenerator(),
            approvalTokenDigester = Sha256ApprovalTokenDigester(),
            decisionValidator = AllowAnyApprovalDecisionValidator,
            clock = fixedClock,
        )
        val engine = TramaiEngine(
            provider = DeterministicToolProvider("payment"),
            toolRegistry = ToolRegistry(mapOf("payment" to tool)),
            policyDecisionAuditEmitter = emitter,
            policyEngine = DefaultPolicyEngine(PolicyConfiguration.preview()),
            toolArgumentsDigester = ToolArgumentsDigester {
                val hex = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(it.reveal().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                Sha256Digest.of("sha256:$hex")
            },
            approvalGateCoordinator = approvalGateCoordinator,
            approvalContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock),
            clock = fixedClock,
        )
        val service = engine.create<PaymentToolService>()

        // Sensitive arguments that must never appear in evidence
        try {
            service.pay("""{"account":"NL00BANK0123456789","apiKey":"secret-value","amount":5000}""")
        } catch (_: ApprovalSuspendedException) {
            // expected
        }

        val events = store.readStream(streamId)
        val toolExporter = ToolPermissionRuntimeEvidenceExporter()
        val toolRecords = toolExporter.export(events)

        // Serialize to JSONL
        val jsonlLines = RuntimeEvidenceJsonlWriter.write(toolRecords)

        // Assert sensitive values never appear in evidence
        assertFalse(jsonlLines.contains("NL00BANK0123456789"), "Evidence must not contain raw account number")
        assertFalse(jsonlLines.contains("secret-value"), "Evidence must not contain raw API key")
        assertFalse(jsonlLines.contains("5000"), "Evidence must not contain raw amount")
        assertFalse(jsonlLines.contains("\"account\""), "Evidence must not contain the substring 'account' in raw form")
    }
}