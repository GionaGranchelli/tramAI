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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * G1a (Option A): the canonical governed identity reaches the authoritative evaluator.
 *
 * These tests capture the **actual `PolicyContext` the evaluator received**, by wrapping the real
 * [DefaultPolicyEngine] rather than reimplementing it. A test that inspected the boundary's inputs, or
 * that built its own context, would pass while the carriage was broken — which is the failure this suite
 * exists to detect. Each assertion here fails if the transport is removed.
 *
 * The identity travels as reserved `identity.*` keys plus the canonical `workflowRunId`, because the
 * typed field cannot be added to the `stable` `PolicyContext` without breaking compiled Kotlin consumers
 * (measured, with a control run: docs/roadmap/0.7.4/0.7.4b1-G1A-ABI-FINDINGS.md).
 *
 * Discriminator: `a governed invocation is evaluated as governed, not as legacy` fails if the keys are
 * absent (the context would look ungoverned) and also fails if the tuple is wrong (the evaluator refuses
 * it as ambiguous), so it cannot pass by accident in either direction.
 */
class GovernedIdentityCarriageTest {
    private val runId = "run-g1a-carriage"
    private val correlationId = "corr-g1a-carriage"

    /**
     * Wraps the real engine, so the captured context is exactly the one the authoritative evaluator
     * received and validated. Nothing is stubbed out of the decision path.
     */
    private class CapturingPolicyEngine(private val delegate: PolicyEngine) : PolicyEngine {
        var captured: PolicyContext? = null

        override suspend fun evaluate(context: PolicyContext): PolicyDecision {
            captured = context
            return delegate.evaluate(context)
        }
    }

    // ── carriage ────────────────────────────────────────────────────────────

    @Test
    fun `the evaluator receives the canonical governed identity`() {
        val capturing = CapturingPolicyEngine(DefaultPolicyEngine(PolicyConfiguration()))
        val boundary = boundary(SideEffectfulToolCallback(), capturing)

        boundary.call("""{"value":"carriage"}""")

        val received = capturing.captured ?: error("the evaluator was never consulted")
        assertEquals(runId, received.workflowRunId, "the canonical run id reaches the evaluator")
        assertEquals(
            mapOf(
                "identity.workloadId" to "workload-g1a",
                "identity.configurationId" to "configuration-g1a",
                "identity.configurationVersion" to "v7",
                "identity.environmentId" to "environment-g1a",
                "identity.deploymentId" to "deployment-g1a-1",
            ),
            received.attributes,
            "the complete deployment tuple reaches the evaluator, key for key and value for value",
        )
    }

    @Test
    fun `a governed invocation is evaluated as governed, not as legacy`() {
        val tool = SideEffectfulToolCallback()
        val capturing = CapturingPolicyEngine(DefaultPolicyEngine(PolicyConfiguration()))
        val boundary = boundary(tool, capturing)

        val result = boundary.call("""{"value":"governed"}""")

        // Not merely "no keys present": the tuple must have passed the evaluator's own validation.
        // A missing or partial tuple produces REFUSED_GOVERNANCE_UNAVAILABLE instead.
        assertTrue(
            result.contains("\"governanceOutcome\":\"REFUSED_DENY\""),
            "the identity was accepted by the evaluator, which then decided on the tool: $result",
        )
        assertNotEquals(
            "governance-unavailable",
            result.substringAfter("\"reasonCode\":\"", "").substringBefore('"'),
            "the refusal must come from policy, not from an identity the evaluator could not trust",
        )
        assertTrue(capturing.captured!!.attributes.isNotEmpty(), "the keys were carried, not dropped")
    }

    @Test
    fun `the boundary re-mints nothing it was given`() {
        val capturing = CapturingPolicyEngine(DefaultPolicyEngine(PolicyConfiguration()))
        val boundary = boundary(SideEffectfulToolCallback(), capturing)

        boundary.call("""{"value":"identity"}""")

        val received = capturing.captured!!
        assertEquals(runId, received.workflowRunId, "the run id is the canonical one, not a new one")
        assertEquals(correlationId, received.correlationId, "the caller's correlation id is preserved")
        assertEquals("spring-ai-workload", received.actorId, "the actor is the caller's, not invented")
        assertEquals("policy-g1a-v1", received.policyVersion, "the policy version is the boundary's own")
    }

    @Test
    fun `both callback overloads carry the same identity`() {
        val tool = SideEffectfulToolCallback()
        val capturing = CapturingPolicyEngine(DefaultPolicyEngine(PolicyConfiguration()))
        val boundary = boundary(tool, capturing)

        boundary.call("""{"value":"one-arg"}""")
        val fromSingle = capturing.captured
        boundary.call("""{"value":"two-arg"}""", ToolContext(mapOf("k" to "v")))
        val fromBoth = capturing.captured

        assertEquals(fromSingle!!.attributes, fromBoth!!.attributes, "neither overload is a partial carriage")
        assertEquals(fromSingle.workflowRunId, fromBoth.workflowRunId)
    }

    @Test
    fun `a denial still prevents execution when the identity is carried`() {
        val tool = SideEffectfulToolCallback()
        val capturing = CapturingPolicyEngine(DefaultPolicyEngine(PolicyConfiguration()))
        val boundary = boundary(tool, capturing)

        boundary.call("""{"value":"denied"}""")

        assertTrue(tool.invocations().isEmpty(), "carrying identity must not weaken prevention")
    }

    // ── fixtures ────────────────────────────────────────────────────────────

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

    private fun boundaryContext(): BoundaryContext =
        BoundaryContext(
            identity =
                GovernedRunIdentity(
                    deployment =
                        WorkloadDeploymentIdentity(
                            workloadId = WorkloadId("workload-g1a"),
                            configuration =
                                WorkloadConfigurationIdentity(
                                    id = ConfigurationId("configuration-g1a"),
                                    version = ConfigurationVersion("v7"),
                                ),
                            environmentId = EnvironmentId("environment-g1a"),
                            deploymentId = DeploymentId("deployment-g1a-1"),
                        ),
                    runId = RunId(runId),
                ),
            security =
                ToolSecurityMetadata(
                    permission = TOOL_PERMISSION,
                    risk = RiskLevel.LOW,
                    approval = ApprovalMode.AUTO,
                    managedNetworkEgress = ManagedNetworkEgress.ALLOW,
                    audit = AuditDetail.DECISION_ONLY,
                ),
            policyVersion = "policy-g1a-v1",
            actorId = "spring-ai-workload",
            correlationId = correlationId,
        )

    private companion object {
        const val TOOL_PERMISSION = "ledger.append"
    }
}
