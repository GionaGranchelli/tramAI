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
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.InMemoryAuditStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Evidence integrity at the boundary — the discriminating negative tests for the two High findings in the
 * review of head `d7d10d05`.
 *
 * The first finding was that recorder and evaluator identities were supplied independently, so evidence
 * could be attributed to a different run than the one evaluated. The recorder now takes the same
 * [BoundaryContext] the engine is evaluated with, which makes divergence structurally impossible; these
 * tests assert that attribution actually follows that context in both directions, and that a differently
 * identified boundary cannot produce another context's evidence.
 *
 * The second finding was that an authorization was recorded *after* the action ran, so a failed audit
 * write could leave a completed side effect with no decision evidence. These tests assert the opposite
 * ordering: if the authorization cannot be recorded, the action does not run.
 */
class EvidenceIntegrityTest {
    @Test
    fun `evidence is attributed to the exact context the engine was evaluated with`() {
        val tool = SideEffectfulToolCallback()
        val context = context(runId = "run-attribution", correlationId = "corr-attribution")
        val recorder = BoundaryEvidenceRecorder(AuditEngine(InMemoryAuditStore()), context)
        val boundary =
            GovernedToolCallback(tool, DefaultPolicyEngine(PolicyConfiguration()), recorder, context)

        val refusal = boundary.call("""{"value":"attempted"}""")

        val record = recorder.evidence().single()
        assertEquals("run-attribution", record.workflowRunId, "evidence belongs to the evaluated run")
        assertEquals("corr-attribution", record.correlationId, "evidence carries the evaluated correlation id")
        assertTrue(
            refusal.contains("\"runId\":\"run-attribution\""),
            "the refusal carries the evaluated run identity",
        )
        assertTrue(
            refusal.contains("\"correlationId\":\"corr-attribution\""),
            "the refusal carries the evaluated correlation id",
        )
    }

    @Test
    fun `a differently identified boundary cannot produce another context's evidence`() {
        val firstContext = context(runId = "run-first", correlationId = "corr-first")
        val secondContext = context(runId = "run-second", correlationId = "corr-second")

        val firstRecorder = BoundaryEvidenceRecorder(AuditEngine(InMemoryAuditStore()), firstContext)
        val secondRecorder = BoundaryEvidenceRecorder(AuditEngine(InMemoryAuditStore()), secondContext)
        GovernedToolCallback(
            SideEffectfulToolCallback(),
            DefaultPolicyEngine(PolicyConfiguration()),
            firstRecorder,
            firstContext,
        ).call("""{"value":"one"}""")
        GovernedToolCallback(
            SideEffectfulToolCallback(),
            DefaultPolicyEngine(PolicyConfiguration()),
            secondRecorder,
            secondContext,
        ).call("""{"value":"two"}""")

        assertEquals("run-first", firstRecorder.evidence().single().workflowRunId)
        assertEquals("run-second", secondRecorder.evidence().single().workflowRunId)
        assertFalse(
            secondRecorder.evidence().single().workflowRunId == "run-first",
            "a boundary cannot record evidence against a run it was not evaluated for",
        )
    }

    @Test
    fun `a failure to record the authorization prevents the action`() {
        val tool = SideEffectfulToolCallback()
        val context = context(runId = "run-audit-failure", correlationId = "corr-audit-failure")
        val recorder = BoundaryEvidenceRecorder(AuditEngine(FailingAuditStore()), context)
        val boundary =
            GovernedToolCallback(
                tool,
                // This configuration admits the tool, so the boundary reaches the ALLOW path.
                DefaultPolicyEngine(
                    PolicyConfiguration(
                        allowedTools = setOf(SideEffectfulToolCallback.TOOL_NAME),
                        allowedPermissions = setOf("ledger.append"),
                    ),
                ),
                recorder,
                context,
            )

        val failed = runCatching { boundary.call("""{"value":"must-not-run"}""") }.isFailure

        assertTrue(failed, "an authorization that cannot be recorded must not be reported as a success")
        assertTrue(
            tool.invocations().isEmpty(),
            "the action must not run when its authorization was not recorded",
        )
    }

    @Test
    fun `a refusal escapes control characters so the payload stays valid JSON`() {
        val refusal =
            BoundaryRefusal(
                outcome = BoundaryOutcome.REFUSED_DENY,
                toolName = "ledger_append",
                reasonCode = "unknown-tool",
                reason = "denied\u0001and\u001fescaped",
                correlationId = "corr-escape",
                runId = "run-escape",
            ).toJson()

        assertTrue(refusal.contains("""\u0001"""), "a control character is emitted as its escape sequence")
        assertTrue(refusal.contains("""\u001f"""))
        assertFalse(refusal.contains('\u0001'), "no raw control character survives into the payload")
        assertFalse(refusal.contains('\u001f'))
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun context(
        runId: String,
        correlationId: String,
    ): BoundaryContext =
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
            policyVersion = "policy-xr1-v1",
            actorId = "spring-ai-workload",
            correlationId = correlationId,
        )

    /** Test-only: an audit store that cannot accept a write. */
    private class FailingAuditStore : AuditStore {
        override suspend fun appendNext(
            auditStreamId: String,
            eventFactory: (latest: AuditEvent?) -> AuditEvent,
        ): AuditEvent = error("audit store unavailable")

        override suspend fun readStream(auditStreamId: String): List<AuditEvent> = emptyList()

        override suspend fun latestEvent(auditStreamId: String): AuditEvent? = null
    }
}
