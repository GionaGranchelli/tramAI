package dev.tramai.engine

import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * G1a (Option A): how an identity-validation refusal is audited at the general engine entry point.
 *
 * The evaluator now refuses an ambiguous governed identity by throwing, which happens *before* it can
 * produce a decision. [PolicyEnforcementHelper] emits its decision-audit event only after the engine
 * returns, so such a refusal audits nothing.
 *
 * That is the intended contract, not an oversight: [PolicyDecisionAuditEmitter] requires a
 * [PolicyDecision], and inventing one would make this helper a decision authority — which the
 * "one authoritative governance evaluator" invariant forbids. A refusal that is not a decision is
 * recorded by the caller's governance-unavailable path, exactly as the XR1 boundary does.
 *
 * These three cases are mutually distinguishable, so the suite cannot pass by accident: a helper that
 * fabricated a decision for the refusal fails the second, and one that suppressed the audit for real
 * decisions fails the first.
 */
class GovernedIdentityAuditContractTest {
    private val completeTuple =
        mapOf(
            "identity.workloadId" to "workload-audit",
            "identity.configurationId" to "configuration-audit",
            "identity.configurationVersion" to "v1",
            "identity.environmentId" to "environment-audit",
            "identity.deploymentId" to "deployment-audit-1",
        )

    private class RecordingEmitter : PolicyDecisionAuditEmitter {
        val emitted = mutableListOf<PolicyDecision>()

        override suspend fun emit(
            enforcementPoint: EnforcementPoint,
            context: PolicyContext,
            decision: PolicyDecision,
        ) {
            emitted += decision
        }
    }

    private fun helper(emitter: PolicyDecisionAuditEmitter) =
        PolicyEnforcementHelper(
            policyEngine = DefaultPolicyEngine(PolicyConfiguration()),
            migrationWarningGuard = AtomicBoolean(false),
            auditEmitter = emitter,
        )

    private fun context(
        workflowRunId: String?,
        attributes: Map<String, String>,
    ) = PolicyContext(
        enforcementPoint = EnforcementPoint.BEFORE_TOOL_EXECUTION,
        workflowRunId = workflowRunId,
        correlationId = "corr-audit-contract",
        actorId = "actor-audit-contract",
        policyVersion = "policy-audit-contract-v1",
        attributes = attributes,
    )

    // ── control: a real decision is still audited ───────────────────────────

    @Test
    fun `a real policy decision is audited`() {
        val emitter = RecordingEmitter()

        runBlocking { helper(emitter).evaluate(context(workflowRunId = null, attributes = emptyMap())) }

        assertEquals(1, emitter.emitted.size, "the decision audit fires for an actual decision")
    }

    // ── an identity refusal is not a decision, and audits none ──────────────

    @Test
    fun `an identity-validation refusal propagates and audits no decision`() {
        val emitter = RecordingEmitter()
        val partial = completeTuple - "identity.deploymentId"

        assertFailsWith<IllegalStateException> {
            runBlocking {
                helper(emitter).evaluate(context(workflowRunId = "run-audit-contract", attributes = partial))
            }
        }

        assertTrue(
            emitter.emitted.isEmpty(),
            "no decision may be fabricated for a refusal that is not a decision: the caller's " +
                "governance-unavailable path records it, and only the evaluator may decide",
        )
    }

    @Test
    fun `a validation refusal is distinguishable from a policy denial`() {
        val refusalEmitter = RecordingEmitter()
        val partial = completeTuple - "identity.deploymentId"
        assertFailsWith<IllegalStateException> {
            runBlocking {
                helper(refusalEmitter).evaluate(context(workflowRunId = "run-audit-contract", attributes = partial))
            }
        }

        // The control: an unknown tool is a real policy denial, and it IS audited.
        val denialEmitter = RecordingEmitter()
        runBlocking { helper(denialEmitter).evaluate(context(workflowRunId = null, attributes = emptyMap())) }

        assertEquals(1, denialEmitter.emitted.size, "a policy denial is audited")
        assertTrue(
            denialEmitter.emitted.single() is PolicyDecision.Deny,
            "and it is a Deny, not a stand-in for an unrelated failure",
        )
        assertTrue(refusalEmitter.emitted.isEmpty(), "the refusal emitted nothing, so the two are distinct")
    }
}
