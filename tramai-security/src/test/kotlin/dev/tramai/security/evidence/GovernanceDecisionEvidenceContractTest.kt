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
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.governance.AuthorizationRefusal
import dev.tramai.security.governance.CandidateAuthorizationDecision
import dev.tramai.security.governance.CandidateSelectionDecision
import dev.tramai.security.governance.CandidateViabilityDecision
import dev.tramai.security.governance.NamedTrustZone
import dev.tramai.security.governance.ProviderCandidate
import dev.tramai.security.governance.ProviderDeployment
import dev.tramai.security.governance.TrustZoneName
import dev.tramai.security.governance.ViabilityRefusal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Proves the `governance.decision` family survives the **canonical** evidence path, and that the
 * candidate subject is bound rather than derived.
 *
 * Every reject case is a tampered copy of a record the seam actually produced, so the proof runs
 * against real output rather than a hand-built fixture that might not resemble it.
 */
class GovernanceDecisionEvidenceContractTest {
    private val at = Instant.parse("2026-10-06T10:00:00Z")
    private val writer = RuntimeEvidenceBundleWriter()

    private fun identity() =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId("workload-a"),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("config-a"),
                            version = ConfigurationVersion("1"),
                        ),
                    environmentId = EnvironmentId("prod"),
                    deploymentId = DeploymentId("deployment-a"),
                ),
            runId = RunId("run-1"),
        )

    private fun candidate(
        provider: String = "provider-a",
        model: String = "model-a",
        deploymentId: String = "provider-deployment-1",
    ) = ProviderCandidate(
        providerId = provider,
        modelId = model,
        deployment =
            ProviderDeployment(
                deploymentId = deploymentId,
                providerId = provider,
                trustZone =
                    NamedTrustZone(
                        name = TrustZoneName("zone-a"),
                        category = ProviderTrustZone.GLOBAL_CLOUD,
                    ),
            ),
    )

    private fun <T : Any> envelope(
        decision: T,
        candidate: ProviderCandidate = candidate(),
        eventId: String = "event-1",
    ) = GovernanceDecisionEnvelope(
        identity = identity(),
        correlationId = "correlation-1",
        policyVersion = "policy-7",
        eventId = eventId,
        subjectDigest = CandidateSubjectDigest.of(candidate),
        decision = decision,
    )

    private fun record(
        decision: Any,
        candidate: ProviderCandidate = candidate(),
        eventId: String = "event-1",
    ) = envelope(decision, candidate, eventId).toRuntimeEvidenceRecord(at, "actor-1")

    // ---- canonical validator accepts -------------------------------------------------------

    @Test
    fun `the canonical family table knows the governance decision family`() {
        assertEquals("governance-decisions.jsonl", RuntimeEvidenceBundleWriter.EVENT_FILES["governance.decision"])
        assertEquals(
            GOVERNANCE_DECISION_KINDS,
            RuntimeEvidenceBundleWriter.ALLOWED_DECISION_KINDS["governance.decision"],
        )
        assertEquals(
            GOVERNANCE_DECISION_COMPONENT,
            RuntimeEvidenceBundleWriter.EXPECTED_SOURCE_COMPONENTS["governance.decision"],
        )
        assertEquals(emptySet(), RuntimeEvidenceBundleWriter.ALLOWED_METADATA_KEYS["governance.decision"])
    }

    @Test
    fun `the canonical validator accepts every governance decision outcome`() {
        val decisions =
            listOf(
                CandidateAuthorizationDecision.Authorized,
                CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED),
                CandidateViabilityDecision.Viable,
                CandidateViabilityDecision.NotViable(ViabilityRefusal.AVAILABILITY),
                CandidateSelectionDecision.Selected(candidate()),
                CandidateSelectionDecision.NoSelection(
                    dev.tramai.security.governance.SelectionRefusal.STRATEGY_DECLINED,
                ),
            )
        val records = decisions.mapIndexed { index, decision -> record(decision, eventId = "event-$index") }
        assertEquals(6, records.size)
        RuntimeEvidenceContractValidator.validate(records)
        records.forEach { assertEquals(GOVERNANCE_DECISION_EVENT_TYPE, it.eventType) }
    }

    @Test
    fun `a governance record survives the canonical bundle writer`() {
        val bundle =
            java.nio.file.Files
                .createTempDirectory("governance-evidence")
        val bundleRoot =
            java.nio.file.Files
                .createDirectories(bundle.resolve("bundle"))
        bundleRoot
            .resolve("manifest.json")
            .toFile()
            .writeText("{\"bundleType\":\"sovereign-lab-evidence-bundle\"}")
        val result = writer.write(bundleRoot, listOf(record(CandidateAuthorizationDecision.Authorized)))
        val file = result.runtimeEvidenceDirectory.resolve("governance-decisions.jsonl")
        assertTrue(
            java.nio.file.Files
                .exists(file),
            "governance records must land in their own family file",
        )
        val content =
            java.nio.file.Files
                .readString(file)
        assertTrue(content.contains("\"eventType\":\"governance.decision\""))
        assertTrue(content.contains("\"kind\":\"governance.authorization\""))
        assertTrue(content.endsWith("\n"))
        assertEquals(1, result.countsByEventType["governance.decision"])
    }

    // ---- canonical validator rejects ------------------------------------------------------

    @Test
    fun `an unknown governance decision kind is rejected by the canonical validator`() {
        val tampered =
            record(CandidateAuthorizationDecision.Authorized).copy(
                decision = RuntimeEvidenceDecision(kind = "governance.escalation", reasonCode = null),
            )
        assertFailsWith<IllegalArgumentException> { RuntimeEvidenceContractValidator.validate(listOf(tampered)) }
    }

    @Test
    fun `a reason outside its own closed family is rejected`() {
        // An authorization refusal presented under the viability kind: the pairing must not be free.
        val tampered =
            record(CandidateViabilityDecision.NotViable(ViabilityRefusal.AVAILABILITY)).copy(
                decision =
                    RuntimeEvidenceDecision(
                        kind = VIABILITY_DECISION_KIND,
                        reasonCode = AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED.name,
                    ),
            )
        assertFailsWith<IllegalArgumentException> { RuntimeEvidenceContractValidator.validate(listOf(tampered)) }
    }

    @Test
    fun `partial governed attribution is rejected by the canonical validator`() {
        val full = record(CandidateAuthorizationDecision.Authorized)
        val partial = full.copy(metadata = full.metadata.filterKeys { it != "identity.environmentId" })
        assertFailsWith<IllegalStateException> { RuntimeEvidenceContractValidator.validate(listOf(partial)) }
    }

    @Test
    fun `non-allowlisted metadata is rejected by the canonical validator`() {
        val full = record(CandidateAuthorizationDecision.Authorized)
        val tampered = full.copy(metadata = full.metadata + ("providerName" to "provider-a"))
        assertFailsWith<IllegalArgumentException> { RuntimeEvidenceContractValidator.validate(listOf(tampered)) }
    }

    @Test
    fun `a foreign source component is rejected by the canonical validator`() {
        val full = record(CandidateAuthorizationDecision.Authorized)
        val tampered = full.copy(source = full.source.copy(component = "provider-router"))
        assertFailsWith<IllegalArgumentException> { RuntimeEvidenceContractValidator.validate(listOf(tampered)) }
    }

    // ---- candidate subject binding --------------------------------------------------------

    @Test
    fun `candidate scoped outcomes bind the candidate subject`() {
        val a = candidate(provider = "provider-a")
        val b = candidate(provider = "provider-b", model = "model-b")
        val pairs =
            listOf(
                CandidateAuthorizationDecision.Authorized,
                CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.PROVIDER_NOT_REGISTERED),
                CandidateViabilityDecision.Viable,
                CandidateViabilityDecision.NotViable(ViabilityRefusal.AVAILABILITY),
            )
        pairs.forEach { decision ->
            val recordA = record(decision, a)
            val recordB = record(decision, b)
            assertNotEquals(
                recordA.digests.subjectDigest,
                recordB.digests.subjectDigest,
                "distinct candidates with the same outcome must not collapse into one subject: $decision",
            )
            assertEquals(CandidateSubjectDigest.of(a), recordA.digests.subjectDigest)
            assertEquals(CandidateSubjectDigest.of(b), recordB.digests.subjectDigest)
        }
    }

    @Test
    fun `a selection cannot be bound to a subject it does not name`() {
        val chosen = candidate(provider = "provider-a")
        val other = candidate(provider = "provider-b", model = "model-b")
        assertFailsWith<IllegalArgumentException> {
            GovernanceDecisionEnvelope(
                identity = identity(),
                correlationId = "correlation-1",
                policyVersion = "policy-7",
                eventId = "event-1",
                subjectDigest = CandidateSubjectDigest.of(other),
                decision = CandidateSelectionDecision.Selected(chosen),
            )
        }
        // The agreeing binding is accepted, so the refusal above is the mismatch and not the shape.
        val bound =
            GovernanceDecisionEnvelope(
                identity = identity(),
                correlationId = "correlation-1",
                policyVersion = "policy-7",
                eventId = "event-1",
                subjectDigest = CandidateSubjectDigest.of(chosen),
                decision = CandidateSelectionDecision.Selected(chosen),
            )
        assertEquals(CandidateSubjectDigest.of(chosen), bound.toRuntimeEvidenceRecord(at).digests.subjectDigest)
    }

    @Test
    fun `a malformed subject digest is refused`() {
        assertFailsWith<IllegalArgumentException> {
            GovernanceDecisionEnvelope(
                identity = identity(),
                correlationId = "correlation-1",
                policyVersion = "policy-7",
                eventId = "event-1",
                subjectDigest = "sha256:not-a-digest",
                decision = CandidateAuthorizationDecision.Authorized,
            )
        }
    }

    @Test
    fun `candidate scoped evidence exposes no raw provider model or deployment values`() {
        val secretish = candidate(provider = "provider-secret-xyz", model = "model-secret-abc")
        val bundle =
            listOf(
                record(CandidateAuthorizationDecision.Authorized, secretish, "event-a"),
                record(CandidateViabilityDecision.NotViable(ViabilityRefusal.AVAILABILITY), secretish, "event-b"),
                record(CandidateSelectionDecision.Selected(secretish), secretish, "event-c"),
            )
        RuntimeEvidenceContractValidator.validate(bundle)
        val serialized = RuntimeEvidenceJsonlWriter.write(bundle)
        assertTrue(!serialized.contains("provider-secret-xyz"), "raw providerId leaked into exported evidence")
        assertTrue(!serialized.contains("model-secret-abc"), "raw modelId leaked into exported evidence")
        bundle.forEach { assertTrue(it.digests.subjectDigest.startsWith("sha256:")) }
    }
}
