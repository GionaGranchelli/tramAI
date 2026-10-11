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
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.InMemoryAuditStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 5 — adversarial discriminators.
 *
 * A passing ALLOW/DENY pair demonstrates behaviour; it does not prove the invariants have teeth. Each test
 * here weakens the boundary in one specific, plausible way and asserts that the invariant the proofs rely
 * on **fails**. If a weakened boundary still satisfied the invariant, the corresponding proof in
 * `GovernanceBoundaryTest` would be decorative.
 *
 * The weakened variants are test-only. No defective behaviour exists in production code, and the defect
 * lives in a separate type rather than a flag on the real boundary, so no weakened path can be enabled.
 *
 * Every case runs against the real `DefaultPolicyEngine` in a deny configuration under one governed
 * invocation, and then checks the same shape the DENY proof asserts: the protected action did not run,
 * exactly one authoritative decision was recorded as DENY, the caller received a structured refusal
 * carrying the governed run identity and the caller's correlation id, and no exception was thrown.
 */
class AdversarialDiscriminatorTest {
    private val runId = "run-xr1-0005"
    private val correlationId = "corr-xr1-0005"

    @Test
    fun `the real boundary satisfies every invariant the discriminators attack`() {
        val outcome = invoke(defect = null)
        assertTrue(outcome.invariantsHold, "the real boundary must satisfy the invariants: ${outcome.detail}")
    }

    @Test
    fun `executing before requesting authorization is caught`() {
        val outcome = invoke(Defect.EXECUTE_BEFORE_AUTHORIZING)
        assertFalse(outcome.invariantsHold, "an action performed before authorization must fail the DENY proof")
        assertTrue(outcome.ledgerSize > 0, "the weakened boundary really did perform the action")
    }

    @Test
    fun `failing open when the authority is unavailable is caught`() {
        val outcome = invoke(Defect.FAIL_OPEN_ON_UNAVAILABLE, engineThrows = true)
        assertFalse(outcome.invariantsHold, "an unavailable authority treated as permission must fail the proof")
        assertTrue(outcome.ledgerSize > 0, "the weakened boundary really did perform the action")
    }

    @Test
    fun `shaping a refusal as a retryable failure is caught`() {
        val outcome = invoke(Defect.THROW_RETRYABLE_REFUSAL)
        assertFalse(
            outcome.invariantsHold,
            "a refusal delivered as an exception can be retried or mishandled as a provider failure",
        )
    }

    @Test
    fun `authorizing from a second policy model is caught`() {
        val outcome = invoke(Defect.SECOND_POLICY_MODEL)
        assertFalse(
            outcome.invariantsHold,
            "a boundary that authorizes from its own rule set while the engine denies must fail the proof",
        )
        assertTrue(outcome.ledgerSize > 0, "the second policy model really did authorize the action")
    }

    @Test
    fun `re-minting the run identity at the boundary is caught`() {
        val outcome = invoke(Defect.RE_MINT_IDENTITY)
        assertFalse(outcome.invariantsHold, "a refusal carrying a fresh run identity breaks identity continuity")
    }

    @Test
    fun `recording a decision the boundary did not enforce is caught`() {
        val outcome = invoke(Defect.RECORD_WITHOUT_ENFORCING)
        assertFalse(
            outcome.invariantsHold,
            "evidence claiming a denial while the action ran contradicts the observed side effect",
        )
        assertTrue(outcome.ledgerSize > 0, "the action ran while the evidence claimed it did not")
    }

    // ── harness ─────────────────────────────────────────────────────────────

    /** One governed invocation against a deny configuration, with the invariants evaluated afterwards. */
    private fun invoke(
        defect: Defect?,
        engineThrows: Boolean = false,
    ): Outcome {
        val tool = SideEffectfulToolCallback()
        val context = context()
        // The real boundary builds its own recorder from this context; a weakened variant borrows one built
        // from the same context, because each variant simulates a specific defect, not an identity split.
        val auditEngine = AuditEngine(InMemoryAuditStore())
        val recorder = recorder()
        val engine: PolicyEngine =
            if (engineThrows) UnavailablePolicyEngine() else DefaultPolicyEngine(PolicyConfiguration())
        val boundary: ToolCallback
        val recordedEvents: () -> List<AuditEvent>
        if (defect == null) {
            val governed = GovernedToolCallback(tool, engine, auditEngine, context)
            boundary = governed
            recordedEvents = { governed.events() }
        } else {
            boundary = WeakenedBoundary(tool, engine, recorder, context, defect)
            recordedEvents = { recorder.events() }
        }

        val attempt = attempt(boundary)
        val events = recordedEvents()
        val refusal = attempt.refusal
        val invariantsHold =
            !attempt.threw &&
                tool.invocations().isEmpty() &&
                events.size == 1 &&
                events.single().decision == "DENY" &&
                refusal != null &&
                refusal.contains("\"executed\":false") &&
                refusal.contains("\"runId\":\"$runId\"") &&
                refusal.contains("\"correlationId\":\"$correlationId\"")
        val detail =
            "ledger=${tool.invocations().size} events=${events.size} threw=${attempt.threw} " +
                "refusal=${refusal?.take(90)}"
        return Outcome(invariantsHold, tool.invocations().size, detail)
    }

    private data class Outcome(
        val invariantsHold: Boolean,
        val ledgerSize: Int,
        val detail: String,
    )

    private data class Attempt(
        val threw: Boolean,
        val refusal: String?,
    )

    private fun attempt(boundary: ToolCallback): Attempt =
        try {
            Attempt(threw = false, refusal = boundary.call("""{"value":"attempted"}"""))
        } catch (expected: Exception) {
            Attempt(threw = true, refusal = null)
        }

    private fun recorder(): BoundaryEvidenceRecorder {
        // Block body on purpose: a single-expression body is collapsed back onto one line by
        // spotlessApply, and the collapsed line exceeds the static-analysis ceiling.
        return BoundaryEvidenceRecorder(AuditEngine(InMemoryAuditStore()), context())
    }

    private fun context(): BoundaryContext =
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
            security =
                ToolSecurityMetadata(
                    permission = "ledger.append",
                    risk = RiskLevel.LOW,
                    approval = ApprovalMode.AUTO,
                    managedNetworkEgress = ManagedNetworkEgress.ALLOW,
                    audit = AuditDetail.DECISION_ONLY,
                ),
            policyVersion = POLICY_VERSION,
            actorId = ACTOR,
            correlationId = correlationId,
        )

    private class UnavailablePolicyEngine : PolicyEngine {
        override suspend fun evaluate(context: PolicyContext): PolicyDecision = error("governance unavailable")
    }

    /** The plausible weakenings under test. */
    private enum class Defect {
        EXECUTE_BEFORE_AUTHORIZING,
        FAIL_OPEN_ON_UNAVAILABLE,
        THROW_RETRYABLE_REFUSAL,
        SECOND_POLICY_MODEL,
        RE_MINT_IDENTITY,
        RECORD_WITHOUT_ENFORCING,
    }

    /** Test-only: the real boundary with exactly one defect injected. */
    private class WeakenedBoundary(
        private val delegate: SideEffectfulToolCallback,
        private val engine: PolicyEngine,
        private val recorder: BoundaryEvidenceRecorder,
        private val context: BoundaryContext,
        private val defect: Defect,
    ) : ToolCallback {
        override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

        override fun call(toolInput: String): String {
            val toolName = delegate.toolDefinition.name()
            return when (defect) {
                Defect.EXECUTE_BEFORE_AUTHORIZING -> {
                    val result = delegate.call(toolInput)
                    recorder.recordRefusal(toolName, "unknown-tool")
                    result
                }

                Defect.FAIL_OPEN_ON_UNAVAILABLE -> {
                    // The defect: the authority's failure is discarded and the action proceeds anyway.
                    runCatching { runBlocking { engine.evaluate(contextFor(toolName)) } }
                    delegate.call(toolInput)
                }

                Defect.THROW_RETRYABLE_REFUSAL -> {
                    runBlocking { engine.evaluate(contextFor(toolName)) }
                    recorder.recordRefusal(toolName, "unknown-tool")
                    // The defect: the refusal looks like a transient provider failure.
                    error("tool execution failed, retry")
                }

                Defect.SECOND_POLICY_MODEL -> {
                    // The defect: its own rule set authorizes; the engine is never asked.
                    recorder.recordAllow(toolName)
                    delegate.call(toolInput)
                }

                Defect.RE_MINT_IDENTITY -> {
                    recorder.recordRefusal(toolName, "unknown-tool")
                    BoundaryRefusal(
                        outcome = BoundaryOutcome.REFUSED_DENY,
                        toolName = toolName,
                        reasonCode = "unknown-tool",
                        reason = "denied",
                        correlationId = context.correlationId,
                        runId = "run-generated-at-the-boundary",
                    ).toJson()
                }

                Defect.RECORD_WITHOUT_ENFORCING -> {
                    // The defect: the evidence claims a denial while the action runs anyway.
                    recorder.recordRefusal(toolName, "unknown-tool")
                    delegate.call(toolInput)
                }
            }
        }

        private fun contextFor(toolName: String): PolicyContext =
            PolicyContext(
                enforcementPoint = EnforcementPoint.BEFORE_TOOL_EXECUTION,
                workflowRunId = context.runId,
                correlationId = context.correlationId,
                actorId = context.actorId,
                toolName = toolName,
                toolSecurity = context.security,
                policyVersion = context.policyVersion,
            )
    }

    private companion object {
        const val ACTOR = "spring-ai-workload"

        const val POLICY_VERSION = "policy-xr1-v1"
    }
}
