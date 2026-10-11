package dev.tramai.security

import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.security.evidence.RuntimeEvidenceAttribution
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * G1a (Option A): the authoritative evaluator validates a governed identity before it decides.
 *
 * Why the identity travels as reserved keys rather than a typed field: `PolicyContext` is a `stable`
 * public API and adding a property breaks compiled Kotlin consumers through the synthetic
 * default-arguments constructor and `copy` (measured, with a control run:
 * docs/roadmap/0.7.4/0.7.4b1-G1A-ABI-FINDINGS.md). These tests pin the rule that makes reserved keys
 * safe to use — the same rule 0.7.1d established for evidence.
 *
 * The discriminating property is the pair of legacy/partial cases: an implementation that validates
 * nothing passes the legacy case and fails the partial case, and an implementation that rejects
 * everything passes the partial case and fails the legacy case. Neither can pass both by accident.
 */
class GovernedIdentityValidationTest {
    private val runId = "run-g1a-0001"

    private val completeTuple =
        mapOf(
            "identity.workloadId" to "checkout-agent",
            "identity.configurationId" to "cfg-checkout",
            "identity.configurationVersion" to "cfg-v7",
            "identity.environmentId" to "prod-eu",
            "identity.deploymentId" to "eu-west-amsterdam-01",
        )

    private fun evaluate(
        workflowRunId: String?,
        attributes: Map<String, String>,
    ) = runBlocking {
        DefaultPolicyEngine(PolicyConfiguration()).evaluate(
            PolicyContext(
                enforcementPoint = EnforcementPoint.BEFORE_TOOL_EXECUTION,
                workflowRunId = workflowRunId,
                correlationId = "corr-g1a",
                actorId = "actor-g1a",
                policyVersion = "policy-g1a-v1",
                attributes = attributes,
            ),
        )
    }

    // ── legacy and complete tuples are accepted ──────────────────────────────

    @Test
    fun `a context without identity keys is legacy and still evaluates`() {
        // Zero keys must stay valid: every existing ungoverned caller keeps working unchanged. Reaching
        // any real decision proves it: a rejected identity would throw instead of deciding, so this
        // asserts the property rather than a guessed reason code.
        val decision = evaluate(workflowRunId = null, attributes = emptyMap())

        assertTrue(decision is dev.tramai.core.policy.PolicyDecision.Deny, "legacy contexts still decide")
    }

    @Test
    fun `a complete tuple with the canonical run id is accepted`() {
        val decision = evaluate(workflowRunId = runId, attributes = completeTuple)

        assertTrue(decision is dev.tramai.core.policy.PolicyDecision.Deny, "it reaches a real decision")
    }

    @Test
    fun `the evaluator receives the identity it was handed`() {
        // Carriage, not just validity: the exact tuple must survive into the evaluator unchanged.
        val received = RuntimeEvidenceAttribution.metadataKeys.associateWith { completeTuple.getValue(it) }
        val decision = evaluate(workflowRunId = runId, attributes = received)

        assertTrue(decision is dev.tramai.core.policy.PolicyDecision.Deny)
        assertEquals(completeTuple, received, "no key was dropped, renamed or re-minted on the way in")
    }

    // ── partial or run-less tuples are refused, never normalised ─────────────

    @Test
    fun `a partial identity tuple is refused rather than treated as ungoverned`() {
        val partial = completeTuple - "identity.deploymentId"

        val failure = assertFailsWith<IllegalStateException> { evaluate(workflowRunId = runId, attributes = partial) }

        assertTrue(
            failure.message!!.contains("partial governed attribution"),
            "the refusal names the cause: ${failure.message}",
        )
    }

    @Test
    fun `a complete tuple without the canonical run id is refused`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                evaluate(workflowRunId = null, attributes = completeTuple)
            }

        assertTrue(
            failure.message!!.contains("without the canonical workflowRunId"),
            "the refusal names the cause: ${failure.message}",
        )
    }

    @Test
    fun `a blank run id does not satisfy a complete tuple`() {
        assertFailsWith<IllegalStateException> { evaluate(workflowRunId = "   ", attributes = completeTuple) }
    }

    @Test
    fun `an unknown identity key is neither validated nor silently accepted as identity`() {
        // A near-miss key must not be read as the identity: the tuple is then incomplete.
        val nearMiss = (completeTuple - "identity.workloadId") + ("identity.workload" to "checkout-agent")

        val failure = assertFailsWith<IllegalStateException> { evaluate(workflowRunId = runId, attributes = nearMiss) }

        assertTrue(failure.message!!.contains("partial governed attribution"))
    }
}
