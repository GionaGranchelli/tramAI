package dev.tramai.examples.springaiboundary

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.InMemoryAuditStore
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XR1-0001 proofs at the declared external boundary.
 *
 * These are the discriminators for ALLOW, DENY and governance-unavailable. Each one fails if the
 * boundary stops preventing the operation: the side-effect ledger is inspected in every case, so an
 * implementation that merely observed the call would not fill it and a test cannot pass by accident.
 *
 * Evidence is read from the boundary under test, never from a recorder the test built for itself. A
 * separately built recorder would let this suite pass while the production wiring attributed evidence to
 * a different context — which is precisely the defect these proofs have to be able to detect.
 */
class GovernanceBoundaryTest {
    private val runId = "run-xr1-0001"
    private val correlationId = "corr-xr1-0001"
    private val policyVersion = "policy-xr1-v1"

    // ── ALLOW ───────────────────────────────────────────────────────────────

    @Test
    fun `an allowed action executes exactly once and correlates to the authoritative decision`() {
        val tool = SideEffectfulToolCallback()
        val boundary = boundary(tool, allowedEngine())

        val result = boundary.call("""{"value":"first"}""")

        assertEquals(listOf("""{"value":"first"}"""), tool.invocations(), "the action ran exactly once")
        assertTrue(result.contains("\"applied\":true"), "the tool's own result is returned")
        val evidence = boundary.evidence()
        assertEquals(1, evidence.size, "one decision was recorded")
        assertEquals("ALLOW", evidence.single().decision.kind, "the recorded decision is the authoritative one")
    }

    // ── DENY ────────────────────────────────────────────────────────────────

    @Test
    fun `a denied action is never invoked and returns a structured refusal`() {
        val tool = SideEffectfulToolCallback()
        // Deny-by-default: the tool is simply not in the allowed set.
        val boundary = boundary(tool, DefaultPolicyEngine(PolicyConfiguration()))

        val result = boundary.call("""{"value":"forbidden"}""")

        assertTrue(tool.invocations().isEmpty(), "the protected action was never invoked")
        assertTrue(result.contains("\"governanceOutcome\":\"REFUSED_DENY\""), "a structured refusal is returned")
        assertTrue(result.contains("\"executed\":false"), "the refusal does not claim execution")
        assertTrue(result.contains("\"reasonCode\":\"unknown-tool\""), "the engine's own reason code is carried")
        val evidence = boundary.evidence()
        assertEquals("DENY", evidence.single().decision.kind, "the refusal is recorded as a denial")
        assertEquals("unknown-tool", evidence.single().decision.reasonCode)
    }

    // ── Governance unavailable ──────────────────────────────────────────────

    @Test
    fun `an unavailable authority prevents execution and is never read as permission`() {
        val tool = SideEffectfulToolCallback()
        val boundary = boundary(tool, UnavailablePolicyEngine())

        val result = boundary.call("""{"value":"unreachable"}""")

        assertTrue(tool.invocations().isEmpty(), "no execution without an authoritative decision")
        assertTrue(
            result.contains("\"governanceOutcome\":\"REFUSED_GOVERNANCE_UNAVAILABLE\""),
            "the caller can distinguish 'not permitted' from 'not decided'",
        )
        assertEquals(
            "governance-unavailable",
            boundary
                .evidence()
                .single()
                .decision.reasonCode,
            "the availability failure is recorded with its own reason code",
        )
    }

    // ── Bypass ──────────────────────────────────────────────────────────────

    @Test
    fun `both tool callback overloads are governed so neither is a bypass`() {
        val boundary = boundary(SideEffectfulToolCallback(), allowedEngine())

        val single = GovernedToolCallback::class.java.getMethod("call", String::class.java)
        val withContext =
            GovernedToolCallback::class.java.getMethod("call", String::class.java, ToolContext::class.java)

        assertEquals(GovernedToolCallback::class.java, single.declaringClass, "the single-argument path is governed")
        assertEquals(
            GovernedToolCallback::class.java,
            withContext.declaringClass,
            "the context-carrying path is governed by this class, not inherited from the framework",
        )
    }

    // ── Identity and evidence provenance ────────────────────────────────────

    @Test
    fun `the governed run identity and the caller correlation survive the boundary`() {
        val tool = SideEffectfulToolCallback()
        val boundary = boundary(tool, DefaultPolicyEngine(PolicyConfiguration()))

        val result = boundary.call("""{"value":"denied"}""")

        assertTrue(result.contains("\"runId\":\"$runId\""), "the refusal carries the governed run identity")
        assertTrue(
            result.contains("\"correlationId\":\"$correlationId\""),
            "the refusal carries the caller's correlation id, not a generated one",
        )
        val record = boundary.evidence().single()
        assertEquals(runId, record.workflowRunId, "evidence is attributed to the governed run")
        assertEquals(correlationId, record.correlationId, "evidence carries the caller's correlation id")
        assertEquals(
            BOUNDARY_ENFORCEMENT_POINT,
            record.metadata["enforcementPoint"],
            "the evidence records the enforcement point; this fails if that metadata is absent or wrong",
        )
        assertFalse(record.eventId.isBlank(), "the decision has a stable identity")
    }

    @Test
    fun `evidence identifies this boundary and is exported through the repository schema`() {
        val boundary = boundary(SideEffectfulToolCallback(), allowedEngine())
        boundary.call("""{"value":"ok"}""")

        val record = boundary.evidence().single()

        assertEquals("ledger_append", record.metadata["toolName"], "the governed operation is named")
        assertTrue(record.source.component.isNotBlank(), "provenance names the emitting component")
        assertFalse(
            record.eventType.isBlank(),
            "the record is projected through the repository's own evidence schema",
        )
    }

    // ── REQUIRE_APPROVAL ────────────────────────────────────────────────────

    @Test
    fun `an action requiring approval cannot execute without one`() {
        val tool = SideEffectfulToolCallback()
        // This configuration requires human approval at the tool's risk level, and no approval exists.
        val engine =
            DefaultPolicyEngine(
                PolicyConfiguration(
                    allowedTools = setOf(SideEffectfulToolCallback.TOOL_NAME),
                    allowedPermissions = setOf(TOOL_PERMISSION),
                    requireApprovalForRiskLevel = setOf(RiskLevel.LOW),
                ),
            )
        val boundary = boundary(tool, engine)

        val result = boundary.call("""{"value":"needs-approval"}""")

        assertTrue(tool.invocations().isEmpty(), "no execution before an authoritative approval exists")
        assertTrue(
            result.contains("\"governanceOutcome\":\"REFUSED_REQUIRE_APPROVAL\""),
            "the caller can distinguish approval-required from denial",
        )
        assertEquals(
            "REQUIRE_APPROVAL",
            boundary.events().single().decision,
            "the suspension requirement is on the evidence",
        )
    }

    // ── Enforcement-strength agreement ──────────────────────────────────────

    @Test
    fun `every refusal declares the strength the framework resolves in the same call's evidence`() {
        val cases =
            listOf(
                "DENY" to DefaultPolicyEngine(PolicyConfiguration()),
                "REQUIRE_APPROVAL" to
                    DefaultPolicyEngine(
                        PolicyConfiguration(
                            allowedTools = setOf(SideEffectfulToolCallback.TOOL_NAME),
                            allowedPermissions = setOf(TOOL_PERMISSION),
                            requireApprovalForRiskLevel = setOf(RiskLevel.LOW),
                        ),
                    ),
                "GOVERNANCE_UNAVAILABLE" to UnavailablePolicyEngine(),
            )

        cases.forEach { (label, engine) ->
            val boundary = boundary(SideEffectfulToolCallback(), engine)
            val refusal = boundary.call("""{"value":"probe"}""")
            val exported = boundary.evidence().single()

            val declared =
                Regex("\"enforcementStrength\":\"([A-Z_]+)\"")
                    .find(refusal)
                    ?.groupValues
                    ?.get(1)
                    ?: error("the $label refusal must declare a strength: $refusal")

            assertEquals(
                declared,
                exported.metadata["boundary.enforcementStrength"],
                "the $label refusal's declared strength must agree with the framework-resolved evidence strength",
            )
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /**
     * The production wiring shape: the boundary is handed its audit engine and *one* context, and builds
     * its own recorder from that context. The test cannot give it a recorder, so the test cannot mask a
     * divergence between the identity evaluated and the identity recorded.
     */
    private fun boundary(
        tool: SideEffectfulToolCallback,
        engine: PolicyEngine,
    ): GovernedToolCallback =
        GovernedToolCallback(
            delegate = tool,
            engine = engine,
            auditEngine = AuditEngine(InMemoryAuditStore()),
            context = boundaryContext(),
        )

    /** The single governance identity used by this wiring. */
    private fun boundaryContext(): BoundaryContext =
        BoundaryContext(
            identity =
                GovernedRunIdentity(
                    deployment =
                        WorkloadDeploymentIdentity(
                            workloadId = WorkloadId("workload-xr1"),
                            configuration =
                                WorkloadConfigurationIdentity(
                                    id = ConfigurationId("configuration-xr1"),
                                    version = ConfigurationVersion("v1"),
                                ),
                            environmentId = EnvironmentId("environment-local"),
                            deploymentId = DeploymentId("deployment-xr1-1"),
                        ),
                    runId = RunId(runId),
                ),
            security = toolSecurity(),
            policyVersion = policyVersion,
            actorId = "spring-ai-workload",
            correlationId = correlationId,
        )

    /** A real engine whose configuration admits exactly the governed tool. */
    private fun allowedEngine(): PolicyEngine =
        DefaultPolicyEngine(
            PolicyConfiguration(
                allowedTools = setOf(SideEffectfulToolCallback.TOOL_NAME),
                allowedPermissions = setOf(TOOL_PERMISSION),
                requireApprovalForRiskLevel = setOf(RiskLevel.HIGH, RiskLevel.CRITICAL),
            ),
        )

    private fun toolSecurity(): ToolSecurityMetadata =
        ToolSecurityMetadata(
            permission = TOOL_PERMISSION,
            risk = RiskLevel.LOW,
            approval = ApprovalMode.AUTO,
            managedNetworkEgress = ManagedNetworkEgress.ALLOW,
            audit = AuditDetail.DECISION_ONLY,
        )

    /** Stands in for "the authority cannot be consulted". The boundary under test is still the real one. */
    private class UnavailablePolicyEngine : PolicyEngine {
        override suspend fun evaluate(context: PolicyContext): PolicyDecision = error("governance unavailable")
    }

    private companion object {
        const val TOOL_PERMISSION = "ledger.append"
    }
}
