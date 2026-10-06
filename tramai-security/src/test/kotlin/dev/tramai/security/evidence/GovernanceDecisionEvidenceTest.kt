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
import dev.tramai.security.governance.SelectionRefusal
import dev.tramai.security.governance.TrustZoneName
import dev.tramai.security.governance.ViabilityRefusal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conditions 21 and 23 — external-runtime independence — are proven structurally by this file
 * compiling at all: it constructs the envelope, binds decisions and projects evidence using only
 * `tramai-core` identity/policy types and `tramai-security` governance/evidence types. No workflow,
 * orchestration, scheduling, provider-execution or engine type appears anywhere in it, and no
 * provider is invoked. A type that needed one of those could not be used here.
 */
class GovernanceDecisionEvidenceTest {
    private val at = Instant.parse("2026-10-06T09:00:00Z")

    // ---- fixtures -------------------------------------------------------------------------

    private fun identity(
        workload: String = "workload-a",
        configurationId: String = "config-a",
        configurationVersion: String = "1",
        deployment: String = "deployment-a",
        run: String = "run-1",
    ) = GovernedRunIdentity(
        deployment =
            WorkloadDeploymentIdentity(
                workloadId = WorkloadId(workload),
                configuration =
                    WorkloadConfigurationIdentity(
                        id = ConfigurationId(configurationId),
                        version = ConfigurationVersion(configurationVersion),
                    ),
                environmentId = EnvironmentId("prod"),
                deploymentId = DeploymentId(deployment),
            ),
        runId = RunId(run),
    )

    private fun candidate(
        provider: String = "provider-a",
        model: String = "model-a",
        deploymentId: String = "provider-deployment-1",
        zone: String = "zone-a",
        category: ProviderTrustZone = ProviderTrustZone.GLOBAL_CLOUD,
    ) = ProviderCandidate(
        providerId = provider,
        modelId = model,
        deployment =
            ProviderDeployment(
                deploymentId = deploymentId,
                providerId = provider,
                trustZone = NamedTrustZone(name = TrustZoneName(zone), category = category),
            ),
    )

    private fun envelope(
        decision: Any,
        identity: GovernedRunIdentity = identity(),
        correlationId: String = "correlation-1",
        eventId: String = "event-1",
        candidate: ProviderCandidate = candidate(),
    ) = GovernanceDecisionEnvelope(
        identity = identity,
        correlationId = correlationId,
        policyVersion = "policy-7",
        eventId = eventId,
        subjectDigest = CandidateSubjectDigest.of(candidate),
        decision = decision,
    )

    private fun record(
        decision: Any,
        candidate: ProviderCandidate = candidate(),
    ) = envelope(decision, candidate = candidate).toRuntimeEvidenceRecord(at, "actor-1")

    private fun attribution(record: RuntimeEvidenceRecord) = record.metadata.filterKeys { it.startsWith("identity.") }

    // ---- identity (1-5) -------------------------------------------------------------------

    @Test
    fun `1 a decision is bound to the exact governed run identity`() {
        val identity = identity()
        val record = envelope(CandidateSelectionDecision.Selected(candidate()), identity).toRuntimeEvidenceRecord(at)
        assertEquals(
            mapOf(
                "identity.workloadId" to "workload-a",
                "identity.configurationId" to "config-a",
                "identity.configurationVersion" to "1",
                "identity.environmentId" to "prod",
                "identity.deploymentId" to "deployment-a",
            ),
            attribution(record),
        )
        assertEquals("run-1", record.workflowRunId)
    }

    @Test
    fun `2 two runs of the same workload and configuration remain distinct`() {
        val first = envelope(CandidateSelectionDecision.Selected(candidate()), identity(run = "run-1"))
        val second = envelope(CandidateSelectionDecision.Selected(candidate()), identity(run = "run-2"))
        assertNotEquals(first.toRuntimeEvidenceRecord(at), second.toRuntimeEvidenceRecord(at))
        assertNotEquals(
            first.toRuntimeEvidenceRecord(at).workflowRunId,
            second.toRuntimeEvidenceRecord(at).workflowRunId,
        )
    }

    @Test
    fun `3 two deployments remain distinct`() {
        val first = attribution(record(CandidateSelectionDecision.Selected(candidate())))
        val other = identity(deployment = "deployment-b")
        val otherRecord =
            envelope(CandidateSelectionDecision.Selected(candidate()), other).toRuntimeEvidenceRecord(at)
        val second = attribution(otherRecord)
        assertEquals("deployment-a", first["identity.deploymentId"])
        assertEquals("deployment-b", second["identity.deploymentId"])
        assertNotEquals(first, second)
    }

    @Test
    fun `4 configuration version changes remain distinguishable`() {
        val v1 = envelope(CandidateSelectionDecision.Selected(candidate()), identity(configurationVersion = "1"))
        val v2 = envelope(CandidateSelectionDecision.Selected(candidate()), identity(configurationVersion = "2"))
        assertNotEquals(
            v1.toRuntimeEvidenceRecord(at).digests.payloadDigest,
            v2.toRuntimeEvidenceRecord(at).digests.payloadDigest,
        )
        assertEquals("2", attribution(v2.toRuntimeEvidenceRecord(at))["identity.configurationVersion"])
    }

    @Test
    fun `5 correlation identity is preserved rather than regenerated`() {
        val record = record(CandidateSelectionDecision.Selected(candidate()))
        assertEquals("correlation-1", record.correlationId)
    }

    // ---- decision semantics (6-12) --------------------------------------------------------

    @Test
    fun `6 an authorized decision preserves the authorized outcome`() {
        val record = record(CandidateAuthorizationDecision.Authorized)
        assertEquals(AUTHORIZATION_DECISION_KIND, record.decision.kind)
        assertNull(record.decision.reasonCode)
    }

    @Test
    fun `7 every authorization refusal maps deterministically`() {
        assertEquals(
            AuthorizationRefusal.entries.map { it.name },
            AuthorizationRefusal.entries.map { reason ->
                record(CandidateAuthorizationDecision.NotAuthorized(reason)).decision.reasonCode
            },
        )
        AuthorizationRefusal.entries.forEach { reason ->
            assertEquals(
                record(CandidateAuthorizationDecision.NotAuthorized(reason)).decision.reasonCode,
                record(CandidateAuthorizationDecision.NotAuthorized(reason)).decision.reasonCode,
            )
        }
    }

    @Test
    fun `8 viable and non-viable preserve the exact viability refusal`() {
        val viable = record(CandidateViabilityDecision.Viable)
        assertEquals(VIABILITY_DECISION_KIND, viable.decision.kind)
        assertNull(viable.decision.reasonCode)
        assertEquals(
            ViabilityRefusal.AVAILABILITY.name,
            record(CandidateViabilityDecision.NotViable(ViabilityRefusal.AVAILABILITY)).decision.reasonCode,
        )
    }

    @Test
    fun `9 selected preserves the exact selected candidate digest`() {
        val chosen = candidate(provider = "provider-b", model = "model-b")
        val record = record(CandidateSelectionDecision.Selected(chosen), chosen)
        assertEquals(CandidateSubjectDigest.of(chosen), record.digests.subjectDigest)
        assertNotEquals(CandidateSubjectDigest.of(candidate()), record.digests.subjectDigest)
    }

    @Test
    fun `10 every selection refusal remains distinguishable`() {
        val codes =
            SelectionRefusal.entries.map {
                record(CandidateSelectionDecision.NoSelection(it)).decision.reasonCode
            }
        assertEquals(SelectionRefusal.entries.map { it.name }, codes)
        assertEquals(SelectionRefusal.entries.size, codes.toSet().size)
    }

    @Test
    fun `11 evidence never converts non-viable into not-authorized`() {
        val nonViable = record(CandidateViabilityDecision.NotViable(ViabilityRefusal.AVAILABILITY))
        val refusal = AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED
        val notAuthorized = record(CandidateAuthorizationDecision.NotAuthorized(refusal))
        assertNotEquals(notAuthorized.decision.kind, nonViable.decision.kind)
        assertEquals(VIABILITY_DECISION_KIND, nonViable.decision.kind)
        assertEquals(AUTHORIZATION_DECISION_KIND, notAuthorized.decision.kind)
    }

    @Test
    fun `12 evidence never converts an escape refusal into a strategy decline`() {
        val escape = record(CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET))
        val declined = record(CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_DECLINED))
        assertEquals(SELECTION_DECISION_KIND, escape.decision.kind)
        assertEquals(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET.name, escape.decision.reasonCode)
        assertNotEquals(declined.decision.reasonCode, escape.decision.reasonCode)
    }

    // ---- historical integrity (13-17) -----------------------------------------------------

    @Test
    fun `13 evidence is interpretable without current registration state`() {
        // The record is complete on its own: no lookup, registry or current provider state is needed.
        val record = record(CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.PROVIDER_NOT_REGISTERED))
        assertEquals(AuthorizationRefusal.PROVIDER_NOT_REGISTERED.name, record.decision.reasonCode)
        assertEquals(5, attribution(record).size)
        assertTrue(record.digests.subjectDigest.startsWith("sha256:"))
    }

    @Test
    fun `14 evidence is interpretable without current availability state`() {
        val record = record(CandidateViabilityDecision.NotViable(ViabilityRefusal.AVAILABILITY))
        assertEquals(ViabilityRefusal.AVAILABILITY.name, record.decision.reasonCode)
        assertEquals(VIABILITY_DECISION_KIND, record.decision.kind)
    }

    @Test
    fun `15 current preference changes cannot alter historical selection evidence`() {
        val chosen = candidate()
        val historical = record(CandidateSelectionDecision.Selected(chosen))
        // A different preference would select a different candidate; the recorded fact is unaffected.
        val preferB = candidate(provider = "provider-b")
        val other = record(CandidateSelectionDecision.Selected(preferB), preferB)
        assertEquals(CandidateSubjectDigest.of(chosen), historical.digests.subjectDigest)
        assertNotEquals(other.digests.subjectDigest, historical.digests.subjectDigest)
        assertEquals(SELECTION_DECISION_KIND, historical.decision.kind)
    }

    @Test
    fun `16 changing one authority or configuration input changes the bound digest`() {
        val base = record(CandidateSelectionDecision.Selected(candidate()))
        val policyChanged =
            GovernanceDecisionEnvelope(
                identity = identity(),
                correlationId = "correlation-1",
                policyVersion = "policy-8",
                eventId = "event-1",
                subjectDigest = CandidateSubjectDigest.of(candidate()),
                decision = CandidateSelectionDecision.Selected(candidate()),
            ).toRuntimeEvidenceRecord(at)
        val digestChanged =
            GovernanceDecisionEnvelope(
                identity = identity(),
                correlationId = "correlation-1",
                policyVersion = "policy-7",
                eventId = "event-1",
                subjectDigest = CandidateSubjectDigest.of(candidate()),
                decision = CandidateSelectionDecision.Selected(candidate()),
                workflowDigest = "other",
            ).toRuntimeEvidenceRecord(at)
        assertNotEquals(policyChanged.digests.payloadDigest, base.digests.payloadDigest)
        assertNotEquals(digestChanged.digests.payloadDigest, base.digests.payloadDigest)
        assertEquals("policy-8", policyChanged.source.module)
    }

    @Test
    fun `17 unordered map input cannot create nondeterministic digest output`() {
        val forward = GovernedIdentityAttribution.of(identity()).toList()
        val reversed =
            GovernedIdentityAttribution
                .of(identity())
                .entries
                .reversed()
                .associate { it.toPair() }
        assertEquals(
            GovernedIdentityAttribution.of(identity()),
            GovernedIdentityAttribution.of(identity()),
        )
        assertEquals(
            RuntimeEvidenceAttribution.merge(emptyMap(), forward.toMap()),
            RuntimeEvidenceAttribution.merge(emptyMap(), reversed),
        )
        val first = envelope(CandidateSelectionDecision.Selected(candidate())).toRuntimeEvidenceRecord(at)
        val second = envelope(CandidateSelectionDecision.Selected(candidate())).toRuntimeEvidenceRecord(at)
        assertEquals(first.digests.payloadDigest, second.digests.payloadDigest)
    }

    // ---- privacy / safe evidence (18-20) --------------------------------------------------

    @Test
    fun `18 raw provider and model values do not leak into exported evidence`() {
        val secretish = candidate(provider = "provider-secret-xyz", model = "model-secret-abc")
        val record = record(CandidateSelectionDecision.Selected(secretish), secretish)
        val serialized = RuntimeEvidenceJsonlWriter.write(listOf(record))
        assertFalse(serialized.contains("provider-secret-xyz"), "raw providerId leaked: $serialized")
        assertFalse(serialized.contains("model-secret-abc"), "raw modelId leaked: $serialized")
        assertFalse(serialized.contains("provider-deployment-1"), "raw deploymentId leaked")
        assertTrue(record.digests.subjectDigest.startsWith("sha256:"))
    }

    @Test
    fun `19 reason evidence uses structured codes not free-form text`() {
        val records =
            listOf(
                record(CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED)),
                record(CandidateViabilityDecision.NotViable(ViabilityRefusal.AVAILABILITY)),
                record(CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_DECLINED)),
            )
        val allowed =
            (AuthorizationRefusal.entries + ViabilityRefusal.entries + SelectionRefusal.entries).map { it.name }.toSet()
        records.forEach { record ->
            assertTrue(
                record.decision.reasonCode in allowed,
                "reason code ${record.decision.reasonCode} is not from a closed reason family",
            )
        }
    }

    @Test
    fun `20 partial governed attribution fails closed`() {
        val partial = mapOf("identity.workloadId" to "workload-a", "identity.deploymentId" to "deployment-a")
        assertFailsWith<IllegalStateException> { RuntimeEvidenceAttribution.validate("run-1", partial) }
        assertFailsWith<IllegalArgumentException> { RuntimeEvidenceAttribution.merge(emptyMap(), mapOf("gate" to "x")) }
        // The envelope itself always emits the complete set, so partial attribution cannot be produced.
        assertEquals(5, attribution(record(CandidateSelectionDecision.Selected(candidate()))).size)
    }

    // ---- external-runtime independence (21-23) --------------------------------------------

    @Test
    fun `21 binding a decision requires no workflow or orchestration type`() {
        // Constructed from governance inputs only: canonical identity, correlation, policy context.
        val bound =
            GovernanceDecisionEnvelope(
                identity = identity(),
                correlationId = "correlation-1",
                policyVersion = "policy-7",
                eventId = "event-1",
                subjectDigest = CandidateSubjectDigest.of(candidate()),
                decision = CandidateSelectionDecision.Selected(candidate()),
            )
        assertEquals("correlation-1", bound.toRuntimeEvidenceRecord(at).correlationId)
        assertEquals(SELECTION_DECISION_KIND, bound.toRuntimeEvidenceRecord(at).decision.kind)
    }

    @Test
    fun `22 projecting evidence invokes no provider`() {
        val candidate = candidate()
        val record = record(CandidateSelectionDecision.Selected(candidate))
        // The candidate is carried as typed fact and digested; nothing is contacted.
        assertEquals(CandidateSubjectDigest.of(candidate), record.digests.subjectDigest)
    }

    @Test
    fun `23 the reusable envelope requires no engine-only type`() {
        // The envelope's declared shape is identity + correlation + policy + decision identity + decision.
        val bound =
            GovernanceDecisionEnvelope(
                identity = identity(),
                correlationId = "correlation-1",
                policyVersion = "policy-7",
                eventId = "event-1",
                subjectDigest = CandidateSubjectDigest.of(candidate()),
                decision = CandidateViabilityDecision.Viable,
            )
        assertEquals(VIABILITY_DECISION_KIND, bound.toRuntimeEvidenceRecord(at).decision.kind)
    }

    // ---- failures closed ------------------------------------------------------------------

    @Test
    fun `two independent decisions in one run do not collapse into one identity`() {
        val first = envelope(CandidateSelectionDecision.Selected(candidate()), eventId = "event-1")
        val second = envelope(CandidateSelectionDecision.Selected(candidate()), eventId = "event-2")
        assertEquals("event-1", first.toRuntimeEvidenceRecord(at).eventId)
        assertEquals("event-2", second.toRuntimeEvidenceRecord(at).eventId)
        assertNotEquals(
            first.toRuntimeEvidenceRecord(at).eventId,
            second.toRuntimeEvidenceRecord(at).eventId,
        )
    }

    @Test
    fun `an unknown decision type is refused rather than coded`() {
        assertFailsWith<IllegalStateException> { GovernanceDecisionOutcome.of("not a decision") }
    }

    @Test
    fun `blank identity or policy context is refused`() {
        assertFailsWith<IllegalArgumentException> {
            GovernanceDecisionEnvelope(
                identity = identity(),
                correlationId = " ",
                policyVersion = "policy-7",
                eventId = "event-1",
                subjectDigest = CandidateSubjectDigest.of(candidate()),
                decision = CandidateViabilityDecision.Viable,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GovernanceDecisionEnvelope(
                identity = identity(),
                correlationId = "correlation-1",
                policyVersion = " ",
                eventId = "event-1",
                subjectDigest = CandidateSubjectDigest.of(candidate()),
                decision = CandidateViabilityDecision.Viable,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GovernanceDecisionEnvelope(
                identity = identity(),
                correlationId = "correlation-1",
                policyVersion = "policy-7",
                eventId = " ",
                subjectDigest = CandidateSubjectDigest.of(candidate()),
                decision = CandidateViabilityDecision.Viable,
            )
        }
    }

    // ---- candidate digest canonical form --------------------------------------------------

    @Test
    fun `the candidate digest includes the deployment as well as provider and model`() {
        val plain = CandidateSubjectDigest.of(candidate())
        val otherDeployment = CandidateSubjectDigest.of(candidate(deploymentId = "provider-deployment-2"))
        val otherZone = CandidateSubjectDigest.of(candidate(zone = "zone-b"))
        val otherZoneCategory = CandidateSubjectDigest.of(candidate(category = ProviderTrustZone.LOCAL))
        assertNotEquals(otherDeployment, plain, "deployment must change the subject digest")
        assertNotEquals(otherZone, plain, "trust zone must change the subject digest")
        assertNotEquals(otherZoneCategory, plain, "trust zone category must change the subject digest")
        assertEquals(plain, CandidateSubjectDigest.of(candidate()), "digest must be deterministic")
    }
}
