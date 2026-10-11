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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The evidence-integrity proofs: recording order, identity continuity, and the refusal payload.
 *
 * The identity case is a **negative** test. It does not assert that a correctly wired boundary produces
 * correct evidence — it asserts that the incorrect wiring cannot be built, and that an observer of the
 * defect would see the evidence stay absent rather than misplaced.
 */
class EvidenceIntegrityTest {
    // ── Identity continuity ─────────────────────────────────────────────────

    @Test
    fun `evidence is attributed to the context the engine was evaluated with`() {
        val tool = SideEffectfulToolCallback()
        val context = context(runId = "run-attribution", correlationId = "corr-attribution")
        val boundary = boundary(tool, context)

        val refusal = boundary.call("""{"value":"attempted"}""")

        val record = boundary.evidence().single()
        assertEquals("run-attribution", record.workflowRunId, "evidence belongs to the evaluated run")
        assertEquals("corr-attribution", record.correlationId)
        assertTrue(refusal.contains("\"runId\":\"run-attribution\""), "the refusal carries the same identity")
    }

    /**
     * The defect this exists to exclude: a boundary evaluated with context A whose evidence is attributed
     * to context B. Three assertions, because any one alone could pass vacuously:
     *
     * 1. the two identities genuinely differ (otherwise the test proves nothing);
     * 2. the boundary's own evidence is attributed to its own context;
     * 3. the foreign recorder recorded **nothing** — the mismatch cannot produce evidence attributed to
     *    the wrong run, it produces no evidence at all through that path.
     *
     * Plus the structural assertion: no constructor parameter accepts a recorder, which is *why* the
     * mismatch is unbuildable rather than rejected. The positive control on the same reflection call
     * (the audit engine parameter) fails if reflection ever stops resolving the constructor, so the
     * claim cannot pass by inspecting nothing.
     */
    @Test
    fun `an evidence recorder belonging to another run cannot be attached to this boundary`() {
        val tool = SideEffectfulToolCallback()
        val contextA = context(runId = "run-a", correlationId = "corr-a")
        val contextB = context(runId = "run-b", correlationId = "corr-b")
        assertNotEquals(contextA.runId, contextB.runId, "the two contexts must differ for this to mean anything")

        val foreignRecorder = BoundaryEvidenceRecorder(AuditEngine(InMemoryAuditStore()), contextB)
        val boundary = boundary(tool, contextA)

        boundary.call("""{"value":"attempted"}""")

        assertEquals("run-a", boundary.evidence().single().workflowRunId, "the boundary records its own context")
        assertTrue(
            foreignRecorder.events().isEmpty(),
            "a recorder for another run receives nothing from this boundary",
        )

        val constructor = GovernedToolCallback::class.java.declaredConstructors.single()
        val parameterTypes = constructor.parameterTypes.toList()
        assertTrue(
            parameterTypes.any { it == AuditEngine::class.java },
            "positive control: the single constructor was inspected and does take the audit engine",
        )
        assertFalse(
            parameterTypes.contains(BoundaryEvidenceRecorder::class.java),
            "no constructor parameter accepts a recorder, so a foreign identity cannot be supplied",
        )
    }

    // ── Recording order ─────────────────────────────────────────────────────

    /**
     * An authorization that cannot be recorded must not become an executed action. The protected ledger is
     * the assertion: an error surfacing while the tool ran anyway would be the failure this excludes.
     */
    @Test
    fun `a failure to record the authorization prevents the action`() {
        val tool = SideEffectfulToolCallback()
        val context = context(runId = "run-audit-failure", correlationId = "corr-audit-failure")
        val boundary =
            GovernedToolCallback(
                delegate = tool,
                engine = allowedEngine(),
                auditEngine = AuditEngine(FailingAuditStore()),
                context = context,
            )

        val threw = runCatching { boundary.call("""{"value":"must-not-run"}""") }.isFailure

        assertTrue(threw, "an unevidenced authorization must not be reported as success")
        assertTrue(tool.invocations().isEmpty(), "the action must not run without recorded authorization")
    }

    // ── Refusal payload ─────────────────────────────────────────────────────

    @Test
    fun `a refusal escapes control characters so the payload stays valid JSON`() {
        val controlCharacter = '\u0001'
        val refusal =
            BoundaryRefusal(
                outcome = BoundaryOutcome.REFUSED_DENY,
                toolName = "ledger_append",
                reasonCode = "reason${controlCharacter}code",
                reason = "denied${controlCharacter}with a control character\u0009and a tab",
                correlationId = "corr-escape",
                runId = "run-escape",
            ).toJson()

        assertTrue(refusal.contains("\\u0001"), "the control character is escaped, not emitted raw")
        assertTrue(refusal.contains("\\t"), "the tab is escaped, not emitted raw")
        assertFalse(
            refusal.any { char -> char < ' ' },
            "no raw control character survives into the payload",
        )
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun boundary(
        tool: SideEffectfulToolCallback,
        context: BoundaryContext,
    ): GovernedToolCallback =
        GovernedToolCallback(
            delegate = tool,
            engine = DefaultPolicyEngine(PolicyConfiguration()),
            auditEngine = AuditEngine(InMemoryAuditStore()),
            context = context,
        )

    private fun allowedEngine() =
        DefaultPolicyEngine(
            PolicyConfiguration(
                allowedTools = setOf(SideEffectfulToolCallback.TOOL_NAME),
                allowedPermissions = setOf("ledger.append"),
            ),
        )

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

    /** Stands in for an audit store that cannot accept the decision it is handed. */
    private class FailingAuditStore : AuditStore {
        override suspend fun appendNext(
            auditStreamId: String,
            eventFactory: (latest: AuditEvent?) -> AuditEvent,
        ): AuditEvent = error("audit store unavailable")

        override suspend fun readStream(auditStreamId: String): List<AuditEvent> = emptyList()

        override suspend fun latestEvent(auditStreamId: String): AuditEvent? = null
    }
}
