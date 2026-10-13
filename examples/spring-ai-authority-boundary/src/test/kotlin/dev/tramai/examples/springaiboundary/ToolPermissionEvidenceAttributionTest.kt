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
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.evidence.RuntimeEvidenceBundleWriter
import dev.tramai.security.evidence.RuntimeEvidenceRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * G1b: the identity this boundary evaluates with is the identity its evidence carries.
 *
 * [GovernedToolCallback] documents that "evaluation, refusal and evidence therefore all carry the
 * same identity". That claim is what these tests hold it to. They drive the real callback through the
 * real [DefaultPolicyEngine], inspect the audit event the boundary recorded, and read the exported
 * `tool.permission` record from the production exporter — no fixture copies an identity tuple into a
 * record, so a broken transport cannot be hidden by a hand-built one.
 *
 * The digest case is the discriminating one: two boundaries identical in tool, decision, reason code
 * and canonical run id, differing only in deployment identity, must not share a payload digest. If
 * the identity stopped reaching the record, those two payload digests would be equal.
 *
 * The reserved key names are pinned here as literals, deliberately, for the same reason
 * [GovernedIdentityCarriageTest] pins them: a rename in the boundary's own translation must fail a
 * test rather than silently downgrade governed evidence to unattributed evidence. The literal is an
 * assertion input only — every record under assertion is produced by the production exporter from
 * the real chain, never assembled from it.
 */
class ToolPermissionEvidenceAttributionTest {
    private val runId = "run-g1b-boundary"

    @Test
    fun `the audit event this boundary records carries the complete governed identity`() {
        val identity = governedIdentity()
        val boundary = boundary(identity)

        boundary.call("""{"value":"denied"}""")

        val event = boundary.events().single()
        assertEquals(runId, event.workflowRunId, "the canonical run id reaches the audit event")
        assertEquals(
            EXPECTED_ATTRIBUTION,
            event.metadata.filterKeys { it.startsWith("identity.") },
            "the complete deployment tuple reaches the audit event, key for key",
        )
    }

    @Test
    fun `exported tool permission evidence carries the complete identity and the run it belongs to`() {
        val identity = governedIdentity()
        val boundary = boundary(identity)

        boundary.call("""{"value":"denied"}""")

        val record = boundary.evidence().single()
        assertEquals("tool.permission", record.eventType, "the event is projectable as tool permission evidence")
        assertEquals(runId, record.workflowRunId, "the evidence names the run, not just the deployment")
        assertEquals(
            EXPECTED_ATTRIBUTION,
            record.metadata.filterKeys { it.startsWith("identity.") },
            "the deployment tuple survives into the exported record",
        )
        assertEquals(
            setOf("toolName", "enforcementPoint") + EXPECTED_ATTRIBUTION.keys + EXPECTED_PROVENANCE.keys,
            record.metadata.keys,
            "the record carries the family allowlist plus exactly the reserved identity and provenance keys",
        )
        assertTrue(
            record.digests.payloadDigest.startsWith("sha256:"),
            "the record advertises a canonical digest: ${record.digests.payloadDigest}",
        )
    }

    @Test
    fun `evidence for two deployments of the same run does not share a payload digest`() {
        // Identical in tool, decision, reason code and canonical run id; only the deployment differs.
        val first = evidenceOf(governedIdentity(deploymentId = "deployment-g1b-1"))
        val second = evidenceOf(governedIdentity(deploymentId = "deployment-g1b-2"))

        assertEquals(first.workflowRunId, second.workflowRunId, "the run id is deliberately the same")
        assertEquals(first.metadata.keys, second.metadata.keys, "the shape is the same; only values differ")
        assertNotEquals(
            first.metadata["identity.deploymentId"],
            second.metadata["identity.deploymentId"],
            "the two deployments must genuinely differ for this to mean anything",
        )
        assertNotEquals(
            first.digests.payloadDigest,
            second.digests.payloadDigest,
            "the digest must cover the identity, or two deployments would be indistinguishable evidence",
        )
    }

    // ── G2: registered enforcement-boundary provenance, through the real boundary ──────────────

    @Test
    fun `exported evidence names the declared registered boundary and its registered strength`() {
        val boundary = boundary(governedIdentity())
        boundary.call("""{"value":"denied"}""")

        val record = boundary.evidence().single()

        assertEquals(
            EXPECTED_PROVENANCE,
            record.metadata.filterKeys { it.startsWith("boundary.") },
            "the record must name the declared boundary and the strength its registration grants",
        )
        assertEquals(
            "policy-engine",
            record.source.component,
            "the boundary must not displace the decision producer in source.component",
        )
    }

    @Test
    fun `the boundary's record satisfies the evidence contract and keeps its provenance serialized`(
        @TempDir tempDir: Path,
    ) {
        val boundary = boundary(governedIdentity())
        boundary.call("""{"value":"denied"}""")
        val record = boundary.evidence().single()

        // The writer requires a bundle root; the manifest's own contract is exercised elsewhere.
        tempDir.resolve("manifest.json").toFile().writeText(
            """{"bundleType": "sovereign-lab-evidence-bundle", "schemaVersion": 1, """ +
                """"claimBoundary": {}, "requiredFiles": [], "files": []}""",
        )

        // The writer validates through the production contract validator before writing anything, so
        // reaching the written file proves the provenance this boundary produced is contract-valid —
        // and that it survived serialization rather than being filtered on the way out.
        val written = RuntimeEvidenceBundleWriter().write(tempDir, listOf(record))

        val jsonl = Files.readString(written.runtimeEvidenceDirectory.resolve("tool-permissions.jsonl"))
        EXPECTED_PROVENANCE.forEach { (key, value) ->
            assertTrue(
                jsonl.contains("\"$key\":\"$value\""),
                "the serialized record must carry $key=$value, got: $jsonl",
            )
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun evidenceOf(identity: GovernedRunIdentity): RuntimeEvidenceRecord {
        val boundary = boundary(identity)
        boundary.call("""{"value":"denied"}""")
        return boundary.evidence().single()
    }

    private fun boundary(identity: GovernedRunIdentity): GovernedToolCallback =
        GovernedToolCallback(
            delegate = SideEffectfulToolCallback(),
            engine = DefaultPolicyEngine(PolicyConfiguration()),
            auditEngine = AuditEngine(InMemoryAuditStore()),
            context =
                BoundaryContext(
                    identity = identity,
                    security =
                        ToolSecurityMetadata(
                            permission = "ledger.append",
                            risk = RiskLevel.LOW,
                            approval = ApprovalMode.AUTO,
                            managedNetworkEgress = ManagedNetworkEgress.ALLOW,
                            audit = AuditDetail.DECISION_ONLY,
                        ),
                    policyVersion = "policy-g1b-v1",
                    actorId = "spring-ai-workload",
                    correlationId = "corr-g1b-boundary",
                ),
        )

    private fun governedIdentity(deploymentId: String = "deployment-g1b-1"): GovernedRunIdentity =
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

    private companion object {
        /** The complete deployment tuple, pinned key-for-key for the default identity. */
        val EXPECTED_ATTRIBUTION =
            mapOf(
                "identity.workloadId" to "workload-g1b",
                "identity.configurationId" to "configuration-g1b",
                "identity.configurationVersion" to "v3",
                "identity.environmentId" to "environment-g1b",
                "identity.deploymentId" to "deployment-g1b-1",
            )

        /**
         * The reserved provenance keys the framework resolves for this declared boundary, pinned as
         * literals for the same reason the identity keys are: a rename must fail a test rather than
         * silently drop provenance from the evidence.
         */
        val EXPECTED_PROVENANCE =
            mapOf(
                "boundary.id" to "spring-ai-tool-callback",
                "boundary.enforcementStrength" to "AUTHORITATIVE",
            )
    }
}
