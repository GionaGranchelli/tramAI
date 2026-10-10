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
import dev.tramai.security.governance.AuthorizationRefusal
import dev.tramai.security.governance.CandidateSelection
import dev.tramai.security.governance.CandidateSelectionDecision
import dev.tramai.security.governance.CandidateSelectionStrategy
import dev.tramai.security.governance.CandidateViabilityDecision
import dev.tramai.security.governance.GovernanceWorld
import dev.tramai.security.governance.ProviderCandidate
import dev.tramai.security.governance.SelectionRefusal
import dev.tramai.security.governance.ViabilityRefusal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * 0.7.3g evidence proof: the decisions the adversarial matrix produces survive into historical
 * evidence through the canonical path.
 *
 * Every record here is built from a decision the composed stages actually returned — not from a
 * hand-written fixture — and is then pushed through [RuntimeEvidenceContractValidator] and
 * [RuntimeEvidenceBundleWriter] into `governance-decisions.jsonl`.
 *
 * 0.7.3f established that evidence wraps a decision without reinterpreting it. This class proves the
 * adversarial outcomes are wrapped the same way: a refused, unavailable or escaped candidate keeps
 * its own stage and its own reason, and nothing about the provider leaks.
 */
class GovernanceAdversarialEvidenceTest {
    @TempDir
    lateinit var tempDir: Path

    private val world = GovernanceWorld()
    private val selection = CandidateSelection()
    private val writer = RuntimeEvidenceBundleWriter()

    private val at: Instant = Instant.parse("2026-10-07T09:00:00Z")

    private val manifestJson =
        """{"bundleType": "sovereign-lab-evidence-bundle", "schemaVersion": 1, """ +
            """"claimBoundary": {}, "requiredFiles": [], "files": []}"""

    /**
     * The bundle writer is fail-closed about a directory with no manifest, so a persistence proof
     * has to establish the bundle the writer expects. Mirrors the writer suite's own fixture.
     */
    private fun createBundleManifest(dir: Path) {
        dir.resolve("manifest.json").toFile().writeText(manifestJson)
    }

    /** The whole adversarial matrix bound as evidence records, one event per decision. */
    private fun matrixRecords(): List<RuntimeEvidenceRecord> =
        matrixDecisions().mapIndexed { index, bound ->
            record(bound.first, bound.second, "event-$index")
        }

    /** The adversarial matrix, evaluated through the real stages. */
    private val pipeline =
        world.pipeline(
            listOf(world.candidateA, world.candidateB, world.candidateC, world.candidateD),
            requiredCapabilities = world.requiredCapability,
            unavailable = setOf(world.candidateB),
        )

    private fun identity() =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId("claims-triage"),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("claims-triage-v1"),
                            version = ConfigurationVersion("3"),
                        ),
                    environmentId = EnvironmentId("production"),
                    deploymentId = DeploymentId("eu-west-amsterdam-01"),
                ),
            runId = RunId("run-1"),
        )

    private fun <T : Any> record(
        decision: T,
        candidate: ProviderCandidate,
        eventId: String,
    ) = GovernanceDecisionEnvelope(
        identity = identity(),
        correlationId = "correlation-adversarial",
        policyVersion = "policy-0.7.3g",
        eventId = eventId,
        subjectDigest = CandidateSubjectDigest.of(candidate),
        decision = decision,
    ).toRuntimeEvidenceRecord(at, "actor-1")

    /** The five representative decisions, taken from the real composed path. */
    private fun matrixDecisions(): List<Pair<Any, ProviderCandidate>> {
        val capabilityRefusal = pipeline.decision(world.candidateC)
        val registrationRefusal = pipeline.decision(world.candidateD)
        val nonViable = pipeline.viabilityDecisions[world.candidateB]
        val selected = selection.select(pipeline.viable, CandidateSelectionStrategy { world.candidateA })
        val escape = selection.select(pipeline.viable, CandidateSelectionStrategy { world.candidateD })

        assertEquals(
            CandidateViabilityDecision.NotViable(ViabilityRefusal.AVAILABILITY),
            nonViable,
            "the matrix must produce a non-viability decision for B",
        )
        assertTrue(selected is CandidateSelectionDecision.Selected, "the matrix must select A")
        assertTrue(escape is CandidateSelectionDecision.NoSelection, "the matrix must refuse D's selection")

        return listOf(
            capabilityRefusal to world.candidateC,
            registrationRefusal to world.candidateD,
            nonViable!! to world.candidateB,
            selected to world.candidateA,
            escape to world.candidateD,
        )
    }

    @Test
    fun `the adversarial matrix decisions pass the canonical validator and the canonical writer`() {
        val records = matrixRecords()

        RuntimeEvidenceContractValidator.validate(records)
        assertTrue(records.all { it.eventType == GOVERNANCE_DECISION_EVENT_TYPE })
        assertTrue(records.all { it.source.component == GOVERNANCE_DECISION_COMPONENT })

        createBundleManifest(tempDir)
        val result = writer.write(tempDir, records)

        // The canonical location is the writer's own reported directory, not the bundle root.
        assertEquals("runtime-evidence", result.runtimeEvidenceDirectory.fileName.toString())
        val file = result.runtimeEvidenceDirectory.resolve("governance-decisions.jsonl")
        assertTrue(Files.exists(file), "governance decisions must persist to governance-decisions.jsonl")
        assertEquals(5, Files.readAllLines(file).count { it.isNotBlank() }, "one persisted record per decision")
        assertEquals(listOf("governance-decisions.jsonl"), result.writtenFiles.map { it.fileName.toString() })
        assertEquals(5, result.countsByEventType[GOVERNANCE_DECISION_EVENT_TYPE])
    }

    @Test
    fun `refused and non viable candidates are distinguishable by reason and by stage`() {
        val (capabilityRefusal, capabilityCandidate) = matrixDecisions()[0]
        val (registrationRefusal, registrationCandidate) = matrixDecisions()[1]
        val (nonViable, unavailableCandidate) = matrixDecisions()[2]

        val capabilityRecord = record(capabilityRefusal, capabilityCandidate, "event-c")
        val registrationRecord = record(registrationRefusal, registrationCandidate, "event-d")
        val nonViableRecord = record(nonViable, unavailableCandidate, "event-b")

        assertEquals(AUTHORIZATION_DECISION_KIND, capabilityRecord.decision.kind)
        assertEquals(AuthorizationRefusal.REQUIRED_CAPABILITY_NOT_SUPPORTED.name, capabilityRecord.decision.reasonCode)
        assertEquals(AUTHORIZATION_DECISION_KIND, registrationRecord.decision.kind)
        assertEquals(AuthorizationRefusal.PROVIDER_NOT_REGISTERED.name, registrationRecord.decision.reasonCode)

        assertEquals(VIABILITY_DECISION_KIND, nonViableRecord.decision.kind)
        assertEquals(ViabilityRefusal.AVAILABILITY.name, nonViableRecord.decision.reasonCode)

        // The stage swap this guards against: a capability refusal must never be readable as
        // unavailable, and an unavailable candidate must never be readable as a governance denial.
        assertNotEquals(VIABILITY_DECISION_KIND, capabilityRecord.decision.kind)
        assertNotEquals(VIABILITY_DECISION_KIND, registrationRecord.decision.kind)
        assertNotEquals(AUTHORIZATION_DECISION_KIND, nonViableRecord.decision.kind)
        assertFalse(ViabilityRefusal.entries.map { it.name }.contains(capabilityRecord.decision.reasonCode))
        assertFalse(AuthorizationRefusal.entries.map { it.name }.contains(nonViableRecord.decision.reasonCode))
    }

    @Test
    fun `a selection escape stays distinguishable from an ordinary strategy decline`() {
        val escape = matrixDecisions()[4].first
        // The ordinary decline comes from the selection stage too: building the decision value here
        // would be exactly the hand-written fixture this class claims not to use.
        val declined = selection.select(pipeline.viable, CandidateSelectionStrategy { null })

        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_DECLINED),
            declined,
            "a decline must be the selection stage's own outcome",
        )

        val escapeRecord = record(escape, world.candidateD, "event-escape")
        val declinedRecord = record(declined, world.candidateA, "event-declined")

        assertEquals(SELECTION_DECISION_KIND, escapeRecord.decision.kind)
        assertEquals(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET.name, escapeRecord.decision.reasonCode)
        assertEquals(SELECTION_DECISION_KIND, declinedRecord.decision.kind)
        assertEquals(SelectionRefusal.STRATEGY_DECLINED.name, declinedRecord.decision.reasonCode)
        assertNotEquals(escapeRecord.decision.reasonCode, declinedRecord.decision.reasonCode)

        // A selection escape is not a governance refusal: it must not be reportable through either
        // earlier family.
        assertFalse(AuthorizationRefusal.entries.map { it.name }.contains(escapeRecord.decision.reasonCode))
        assertFalse(ViabilityRefusal.entries.map { it.name }.contains(escapeRecord.decision.reasonCode))
    }

    @Test
    fun `the subject digest names the candidate concerned and separates equal outcomes`() {
        val authorizedA = record(pipeline.decision(world.candidateA), world.candidateA, "event-a")
        val authorizedB = record(pipeline.decision(world.candidateB), world.candidateB, "event-b")
        val refusedD = record(pipeline.decision(world.candidateD), world.candidateD, "event-d")

        assertEquals(CandidateSubjectDigest.of(world.candidateA), authorizedA.digests.subjectDigest)
        assertEquals(CandidateSubjectDigest.of(world.candidateB), authorizedB.digests.subjectDigest)
        assertEquals(CandidateSubjectDigest.of(world.candidateD), refusedD.digests.subjectDigest)

        // Identical outcome and identical reason for two different candidates: the records must
        // still be distinguishable, and the subject digest is what distinguishes them. The payload
        // digest deliberately covers the decision payload (kind, reason, policy version, workflow
        // digest, correlation, attribution) and not the candidate, so two equally authorized
        // candidates legitimately share it — the subject is carried separately, which is the point.
        assertEquals(authorizedA.decision, authorizedB.decision)
        assertNotEquals(authorizedA.digests.subjectDigest, authorizedB.digests.subjectDigest)
        assertEquals(authorizedA.digests.payloadDigest, authorizedB.digests.payloadDigest)
        assertNotEquals(
            RuntimeEvidenceJsonlWriter.write(listOf(authorizedA)),
            RuntimeEvidenceJsonlWriter.write(listOf(authorizedB)),
            "the exported records must differ even when the decision payload is the same fact",
        )
    }

    @Test
    fun `no raw provider model or deployment value reaches exported evidence`() {
        val records = matrixRecords()
        RuntimeEvidenceContractValidator.validate(records)

        val serialized = RuntimeEvidenceJsonlWriter.write(records)
        val rawValues =
            listOf(
                GovernanceWorld.ALPHA_PROVIDER,
                GovernanceWorld.BETA_PROVIDER,
                GovernanceWorld.GAMMA_PROVIDER,
                GovernanceWorld.DELTA_PROVIDER,
                GovernanceWorld.ALPHA_MODEL,
                GovernanceWorld.BETA_MODEL,
                GovernanceWorld.GAMMA_MODEL,
                GovernanceWorld.DELTA_MODEL,
                "dep-alpha",
                "dep-beta",
                "dep-gamma",
                "dep-delta",
                "dep-alpha-zone",
            )

        rawValues.forEach { raw ->
            assertFalse(serialized.contains(raw), "raw value '$raw' leaked into exported evidence")
        }
        records.forEach { assertTrue(it.digests.subjectDigest.startsWith("sha256:")) }
    }

    @Test
    fun `the governance evidence family carries no family metadata of its own`() {
        val records = matrixRecords()

        assertEquals(emptySet<String>(), RuntimeEvidenceBundleWriter.ALLOWED_METADATA_KEYS["governance.decision"])
        val attributionKeys = RuntimeEvidenceAttribution.metadataKeys
        records.forEach { record ->
            assertEquals(attributionKeys, record.metadata.keys, "only the governed identity attribution is carried")
        }
    }
}
