package dev.tramai.examples.toolgovernance

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
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
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import dev.tramai.security.approval.UuidApprovalIdGenerator
import dev.tramai.security.audit.*
import dev.tramai.security.evidence.PolicyDecisionRuntimeEvidenceExporter
import dev.tramai.security.evidence.ToolPermissionRuntimeEvidenceExporter
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Main runner for the tool governance example.
 *
 * Demonstrates three deterministic, credential-free scenarios:
 *
 * 1. **customer-lookup (ALLOW)** — Read-only tool with LOW risk, AUTO approval.
 *    All three enforcement points ALLOW. Tool executes once.
 *
 * 2. **account-delete (DENY)** — CRITICAL risk tool denied at BEFORE_TOOL_EXECUTION
 *    by a policy wrapper. Tool never executes.
 *
 * 3. **payment (REQUIRE_APPROVAL)** — HIGH risk tool with HUMAN_REQUIRED approval.
 *    Suspended at BEFORE_TOOL_EXECUTION. Tool never executes.
 *
 * After each scenario, tool.permission and policy.decision evidence is printed
 * to show the dedicated evidence family.
 */
object ToolGovernanceMain {

    private val fixedClock = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneId.of("UTC"))

    @AiService
    interface CustomerLookupService {
        @Operation(prompt = "Look up customer by ID", model = "test-model", tools = ["customer_lookup"], providerRetries = 0)
        suspend fun lookup(input: String): String
    }

    @AiService
    interface AccountDeleteService {
        @Operation(prompt = "Delete the account", model = "test-model", tools = ["account_delete"], providerRetries = 0)
        suspend fun delete(input: String): String
    }

    @AiService
    interface PaymentService {
        @Operation(prompt = "Process the payment", model = "test-model", tools = ["payment"], providerRetries = 0)
        suspend fun pay(input: String): String
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Tool Governance Example ===\n")
        runBlocking {
            scenarioCustomerLookup()
            scenarioAccountDelete()
            scenarioPayment()
        }
        println("\n=== All scenarios complete ===")
    }

    private suspend fun scenarioCustomerLookup() {
        println("--- Scenario 1: customer-lookup (ALLOW) ---")

        val tool = CustomerLookupTool()
        val provider = DeterministicToolProvider("customer_lookup")
        val store = InMemoryAuditStore()
        val auditEngine = AuditEngine(store, clock = fixedClock)
        val streamId = "tool-governance-customer-lookup"
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine, streamIdResolver = FixedAuditStreamIdResolver(streamId))
        val baselinePolicy = DefaultPolicyEngine(PolicyConfiguration.preview())
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("customer_lookup" to tool)),
            policyDecisionAuditEmitter = emitter,
            policyEngine = baselinePolicy,
        )
        val service = engine.create<CustomerLookupService>()

        try {
            val result = service.lookup("CUST-001")
            println("  Result: $result")
        } catch (e: Exception) {
            println("  Exception: ${e::class.simpleName}: ${e.message}")
        }

        println("  Tool executed: ${tool.callCount.get() > 0}")
        println("  Provider calls: ${provider.callCount.get()}")
        printEvidence(store.readStream(streamId))
        println()
    }

    private suspend fun scenarioAccountDelete() {
        println("--- Scenario 2: account-delete (DENY) ---")

        val tool = AccountDeleteTool()
        val provider = DeterministicToolProvider("account_delete")
        val store = InMemoryAuditStore()
        val auditEngine = AuditEngine(store, clock = fixedClock)
        val streamId = "tool-governance-account-delete"
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine, streamIdResolver = FixedAuditStreamIdResolver(streamId))
        val baselinePolicy = DefaultPolicyEngine(PolicyConfiguration.preview())

        // Policy wrapper that denies account_delete at BEFORE_TOOL_EXECUTION
        val denyingPolicy = PolicyEngine { context ->
            if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION && context.toolName == "account_delete") {
                PolicyDecision.Deny(reason = "Account deletion is disabled in this environment", reasonCode = "account-delete-disabled")
            } else {
                baselinePolicy.evaluate(context)
            }
        }

        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("account_delete" to tool)),
            policyDecisionAuditEmitter = emitter,
            policyEngine = denyingPolicy,
        )
        val service = engine.create<AccountDeleteService>()

        try {
            service.delete("ACC-001")
            println("  Result: unexpected success (policy should have denied)")
        } catch (e: PolicyViolationException) {
            println("  Expected exception: PolicyViolationException")
            println("  Reason: ${e.decision.reason}")
            println("  Code: ${e.decision.reasonCode}")
        } catch (e: Exception) {
            println("  Unexpected exception: ${e::class.simpleName}: ${e.message}")
        }

        println("  Tool executed: ${tool.callCount.get() > 0}")
        println("  Provider calls: ${provider.callCount.get()}")
        printEvidence(store.readStream(streamId))
        println()
    }

    private suspend fun scenarioPayment() {
        println("--- Scenario 3: payment (REQUIRE_APPROVAL) ---")

        val tool = PaymentTool()
        val provider = DeterministicToolProvider("payment")
        val store = InMemoryAuditStore()
        val auditEngine = AuditEngine(store, clock = fixedClock)
        val streamId = "tool-governance-payment"
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine, streamIdResolver = FixedAuditStreamIdResolver(streamId))
        val baselinePolicy = DefaultPolicyEngine(PolicyConfiguration.preview())

        val approvalGateCoordinator = DefaultApprovalGateCoordinator(
            store = InMemoryApprovalStore(clock = fixedClock),
            approvalIdGenerator = UuidApprovalIdGenerator(),
            approvalTokenGenerator = SecureRandomApprovalTokenGenerator(),
            approvalTokenDigester = Sha256ApprovalTokenDigester(),
            decisionValidator = AllowAnyApprovalDecisionValidator,
            clock = fixedClock,
        )

        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf("payment" to tool)),
            policyDecisionAuditEmitter = emitter,
            policyEngine = baselinePolicy,
            toolArgumentsDigester = Sha256ToolArgumentsDigester(),
            approvalGateCoordinator = approvalGateCoordinator,
            approvalContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock),
            clock = fixedClock,
        )
        val service = engine.create<PaymentService>()

        try {
            service.pay("5000 EUR")
            println("  Result: unexpected success (should have been suspended)")
        } catch (e: ApprovalSuspendedException) {
            println("  Expected exception: ApprovalSuspendedException")
            println("  Tool: ${e.toolName}")
            println("  Workflow: ${e.workflowRunId}")
        } catch (e: Exception) {
            println("  Exception: ${e::class.simpleName}: ${e.message}")
        }

        println("  Tool executed: ${tool.callCount.get() > 0}")
        println("  Provider calls: ${provider.callCount.get()}")
        printEvidence(store.readStream(streamId))
        println()
    }

    /**
     * Prints tool.permission and policy.decision evidence for the given audit events.
     * Shows that tool enforcement events appear in tool.permission (not policy.decision),
     * and that policy.decision records never include tool enforcement events.
     */
    private suspend fun printEvidence(events: List<AuditEvent>) {
        val toolExporter = ToolPermissionRuntimeEvidenceExporter()
        val policyExporter = PolicyDecisionRuntimeEvidenceExporter()

        val toolRecords = toolExporter.export(events)
        val policyRecords = policyExporter.export(events)

        println("  ── tool.permission evidence ──")
        if (toolRecords.isEmpty()) {
            println("    (none)")
        } else {
            for (record in toolRecords) {
                println("    ${record.metadata["enforcementPoint"]?.padEnd(35)} ${record.decision.kind.padEnd(18)} ${record.metadata["toolName"] ?: "—"}")
            }
            println("    evidence family: tool.permission")
            println("    event type: ${toolRecords.first().eventType}")
        }

        println("  ── policy.decision evidence ──")
        val nonToolPolicyRecords = policyRecords.filter { it.eventType == "policy.decision" }
        if (nonToolPolicyRecords.isEmpty()) {
            println("    (none — no non-tool policy events)")
        } else {
            for (record in nonToolPolicyRecords) {
                println("    ${record.eventType} ${record.decision.kind} ${record.source.component}")
            }
        }

        // Verify: no tool events appear in policy.decision evidence (event-ID based partition check)
        val toolEnforcementPoints = setOf("BEFORE_TOOL_EXPOSURE", "BEFORE_TOOL_EXECUTION", "BEFORE_TOOL_RESULT_REINJECTION")
        val toolAuditEventIds = events
            .filter { it.enforcementPoint in toolEnforcementPoints }
            .map { it.eventId }
            .toSet()
        val toolEventsInPolicy = policyRecords.filter { it.eventId in toolAuditEventIds }
        println("  ── cross-check: tool events in policy.decision evidence: ${toolEventsInPolicy.size} ──")
    }
}

/**
 * Simple AuditStreamIdResolver that returns a fixed string.
 */
internal class FixedAuditStreamIdResolver(private val streamId: String) : dev.tramai.security.audit.AuditStreamIdResolver {
    override fun resolve(context: PolicyContext): String = streamId
}
