package dev.tramai.security.evidence

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.InMemoryAuditStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * G1b: the governed identity reaches `tool.permission` evidence and the canonical digest binds it.
 *
 * The chain under test is the repository's own, not a reimplementation of it: the authoritative
 * evaluator is the real [DefaultPolicyEngine], the audit event comes from the real
 * [AuditEnginePolicyDecisionAuditEmitter] through the real [AuditEngine], and the record comes from
 * the production [ToolPermissionRuntimeEvidenceExporter]. Nothing is stubbed out of the path whose
 * behaviour these tests claim.
 *
 * The identity is not hand-built here: it is a typed [GovernedRunIdentity] translated by
 * [GovernedIdentityAttribution], the same production translation the governance-decision evidence
 * uses. A test that pasted its own literal tuple into a record would pass while the transport was
 * broken — which is the failure this suite exists to detect, and the reason the adversarial
 * discriminator can remove the production transfer and make these tests fail.
 *
 * The pair of legacy/attributed assertions is the discriminating property: an implementation that
 * carries nothing passes the legacy case and fails the attributed case, and one that attributes
 * everything passes the attributed case and fails the legacy case.
 */
class ToolPermissionEvidenceAttributionTest {
    private val exporter = ToolPermissionRuntimeEvidenceExporter()

    // ── attribution reaches the exported record ──────────────────────────────

    @Test
    fun `a governed tool decision reaches exported evidence with its complete identity`() {
        val identity = governedIdentity()
        val context = policyContext(identity)

        val decision = evaluate(context)
        // Reaching a decision is the proof that the evaluator accepted the tuple as governed: a
        // partial or run-less tuple is refused before any enforcement-point branch is entered.
        assertTrue(decision is PolicyDecision.Deny, "the real evaluator decided on the tool")

        val record = exporter.export(listOf(emit(context, decision))).single()

        assertEquals(identity.runId.value, record.workflowRunId, "the canonical run id reaches the record")
        assertEquals(
            GovernedIdentityAttribution.of(identity),
            record.metadata.filterKeys { it in RuntimeEvidenceAttribution.metadataKeys },
            "the complete deployment tuple reaches the record, key for key and value for value",
        )
        assertEquals(
            FAMILY_ALLOWLIST + RuntimeEvidenceAttribution.metadataKeys,
            record.metadata.keys,
            "the record carries the family allowlist plus exactly the reserved identity keys",
        )
    }

    @Test
    fun `the canonical digest binds the attributed identity`() {
        val identity = governedIdentity()
        val context = policyContext(identity)
        val event = emit(context, evaluate(context))

        val record = exporter.export(listOf(event)).single()
        val shown = record.metadata
        val withoutIdentity = shown - RuntimeEvidenceAttribution.metadataKeys

        assertEquals(
            event.computeToolPermissionPayloadDigest(shown),
            record.digests.payloadDigest,
            "the advertised digest is the digest of the payload the record actually shows",
        )
        assertNotEquals(
            event.computeToolPermissionPayloadDigest(withoutIdentity),
            record.digests.payloadDigest,
            "removing the identity changes the digest, so the identity is bound and not incidental",
        )
    }

    @Test
    fun `the digest distinguishes two runs that differ only by identity`() {
        // Same event shape, same decision, same tool: only the governed identity differs. If the
        // digest did not cover the identity these two records would be indistinguishable evidence.
        val first = govern(governedIdentity())
        val second = govern(governedIdentity(deploymentId = "deployment-g1b-2"))

        assertNotEquals(
            first.digests.payloadDigest,
            second.digests.payloadDigest,
            "evidence for two deployments must not share a payload digest",
        )
        assertEquals(first.metadata.keys, second.metadata.keys, "the shape is identical; only values differ")
    }

    // ── the canonical run id is bound, not merely carried ────────────────────

    @Test
    fun `the canonical payload digest binds the canonical run id`() {
        // Everything except the canonical run id is deliberately identical: same five deployment
        // fields, same decision, same reason code, same tool. If the digest did not bind the run id
        // these two records would be interchangeable evidence for two different runs.
        val first = govern(governedIdentity(runId = "run-g1b-first"))
        val second = govern(governedIdentity(runId = "run-g1b-second"))

        assertEquals(first.metadata, second.metadata, "the five deployment fields are deliberately identical")
        assertEquals(first.decision.kind, second.decision.kind, "the decision is deliberately identical")
        assertNotEquals(first.workflowRunId, second.workflowRunId, "only the canonical run id differs")

        assertNotEquals(
            first.digests.payloadDigest,
            second.digests.payloadDigest,
            "the canonical payload digest must bind the run of the evidence it describes",
        )
    }

    @Test
    fun `legacy evidence keeps the digest it had before run-id binding existed`() {
        // Legacy evidence gains no run-id term: two ungoverned records differing only by their run id
        // keep an identical payload digest, which is exactly the behaviour they had before G1b.
        // Without this control, "bind the run id" could be satisfied by silently re-digesting every
        // record in the repository.
        val first = legacyRecord(runId = "run-legacy-a")
        val second = legacyRecord(runId = "run-legacy-b")

        assertNotEquals(first.workflowRunId, second.workflowRunId, "the two runs are genuinely different")
        assertTrue(
            first.metadata.keys.none { it.startsWith("identity.") },
            "both are legacy records: ${first.metadata.keys}",
        )
        assertEquals(
            first.digests.payloadDigest,
            second.digests.payloadDigest,
            "an ungoverned record's canonical payload digest is unchanged by the run id",
        )
    }

    // ── fail closed, never downgrade ─────────────────────────────────────────

    @Test
    fun `a partial identity tuple in the audit event is refused rather than downgraded`() {
        val identity = governedIdentity()
        // The decision is produced by the real evaluator from a complete tuple; the event is then
        // emitted from a context carrying only four of the five keys, which is the corruption shape.
        val decision = evaluate(policyContext(identity))
        val partial = GovernedIdentityAttribution.of(identity) - "identity.deploymentId"
        val event = emit(policyContext(identity, attributes = partial), decision)

        assertEquals(
            4,
            event.metadata.keys.count { it in RuntimeEvidenceAttribution.metadataKeys },
            "the fixture really does carry a partial tuple, so the refusal below is not vacuous",
        )
        val failure = assertFailsWith<IllegalStateException> { exporter.export(listOf(event)) }

        assertTrue(
            failure.message!!.contains("partial governed attribution"),
            "the refusal names the cause rather than silently recording a downgrade: ${failure.message}",
        )
    }

    @Test
    fun `a complete identity without the canonical run id is refused`() {
        val identity = governedIdentity()
        val decision = evaluate(policyContext(identity))
        val runLess = policyContext(identity).copy(workflowRunId = null)
        val event = emit(runLess, decision)

        assertEquals(
            RuntimeEvidenceAttribution.metadataKeys.size,
            event.metadata.keys.count { it in RuntimeEvidenceAttribution.metadataKeys },
            "the tuple is complete; only the canonical run id is absent",
        )
        val failure = assertFailsWith<IllegalStateException> { exporter.export(listOf(event)) }

        assertTrue(
            failure.message!!.contains("without the canonical workflowRunId"),
            "the refusal names the cause: ${failure.message}",
        )
    }

    // ── legacy evidence and the family allowlist are unchanged ───────────────

    @Test
    fun `legacy evidence without identity still exports unchanged`() {
        val context = policyContext(governedIdentity(), attributes = emptyMap())
        val event = emit(context, evaluate(policyContext(governedIdentity(), attributes = emptyMap())))

        val record = exporter.export(listOf(event)).single()

        assertTrue(
            record.metadata.keys.none { it.startsWith("identity.") },
            "an ungoverned event gains no identity keys: ${record.metadata.keys}",
        )
        assertEquals(
            FAMILY_ALLOWLIST,
            record.metadata.keys,
            "the legacy record is exactly the family allowlist, so zero keys is still valid evidence",
        )
        assertEquals(
            event.computeToolPermissionPayloadDigest(record.metadata),
            record.digests.payloadDigest,
            "the legacy digest is computed over exactly the payload the record shows",
        )
    }

    @Test
    fun `only the reserved keys are read as identity, and the family allowlist is not widened`() {
        val identity = governedIdentity()
        // A caller-supplied near-miss key and an unknown key, both present in the event metadata.
        val event =
            emit(
                policyContext(
                    identity,
                    attributes =
                        GovernedIdentityAttribution.of(identity) +
                            mapOf("identity.workload" to "spoofed", "secret" to "should-not-survive"),
                ),
                evaluate(policyContext(identity)),
            )

        val record = exporter.export(listOf(event)).single()

        assertEquals(
            FAMILY_ALLOWLIST + RuntimeEvidenceAttribution.metadataKeys,
            record.metadata.keys,
            "a near-miss identity key and an unknown key are both filtered, not promoted",
        )
        assertEquals(
            GovernedIdentityAttribution.of(identity),
            record.metadata.filterKeys { it in RuntimeEvidenceAttribution.metadataKeys },
            "the reserved keys are exact: a lookalike neither substitutes for nor augments the identity",
        )
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** The full production chain, ending in the exported record. */
    private fun govern(identity: GovernedRunIdentity): RuntimeEvidenceRecord {
        val context = policyContext(identity)
        return exporter.export(listOf(emit(context, evaluate(context)))).single()
    }

    private fun evaluate(context: PolicyContext): PolicyDecision =
        runBlocking { DefaultPolicyEngine(PolicyConfiguration()).evaluate(context) }

    private fun emit(
        context: PolicyContext,
        decision: PolicyDecision,
    ): AuditEvent {
        val store = CapturingAuditStore()
        runBlocking {
            AuditEnginePolicyDecisionAuditEmitter(AuditEngine(store))
                .emit(EnforcementPoint.BEFORE_TOOL_EXECUTION, context, decision)
        }
        // Read back the event the real audit engine actually chained, rather than re-deriving the
        // emission's metadata: the assertions then describe the production event, and a change that
        // stopped the emitter writing through the audit chain fails here instead of passing.
        return store.last ?: error("the production emitter recorded no audit event")
    }

    private fun policyContext(
        identity: GovernedRunIdentity,
        attributes: Map<String, String> = GovernedIdentityAttribution.of(identity),
    ): PolicyContext =
        PolicyContext(
            enforcementPoint = EnforcementPoint.BEFORE_TOOL_EXECUTION,
            workflowRunId = identity.runId.value,
            correlationId = "corr-g1b",
            actorId = "actor-g1b",
            toolName = TOOL_NAME,
            policyVersion = POLICY_VERSION,
            attributes = attributes,
        )

    private fun governedIdentity(
        deploymentId: String = "deployment-g1b-1",
        runId: String = RUN_ID,
    ): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId("workload-g1b"),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("configuration-g1b"),
                            version = ConfigurationVersion("v3"),
                        ),
                    environmentId = EnvironmentId("environment-g1b"),
                    deploymentId = DeploymentId(deploymentId),
                ),
            runId = RunId(runId),
        )

    /** The same governed call with zero reserved keys: the legacy shape an ungoverned caller produces. */
    private fun legacyRecord(runId: String): RuntimeEvidenceRecord {
        val context = policyContext(governedIdentity(runId = runId), attributes = emptyMap())
        return exporter.export(listOf(emit(context, evaluate(context)))).single()
    }

    private companion object {
        const val RUN_ID = "run-g1b-attribution"

        const val TOOL_NAME = "ledger.append"

        const val POLICY_VERSION = "policy-g1b-v1"

        /** The family allowlist the exporter has always applied: the tool name plus the enforcement point. */
        val FAMILY_ALLOWLIST = setOf("toolName", "enforcementPoint")
    }

    /**
     * Delegates to a real [InMemoryAuditStore] and keeps the event the engine chained, so the
     * production emitter's own output can be asserted without re-deriving it.
     */
    private class CapturingAuditStore(
        private val delegate: AuditStore = InMemoryAuditStore(),
    ) : AuditStore {
        var last: AuditEvent? = null
            private set

        override suspend fun appendNext(
            auditStreamId: String,
            eventFactory: (latest: AuditEvent?) -> AuditEvent,
        ): AuditEvent = delegate.appendNext(auditStreamId, eventFactory).also { last = it }

        override suspend fun readStream(auditStreamId: String): List<AuditEvent> = delegate.readStream(auditStreamId)

        override suspend fun latestEvent(auditStreamId: String): AuditEvent? = delegate.latestEvent(auditStreamId)
    }
}
