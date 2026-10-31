package dev.tramai.security.reconstruction

import dev.tramai.core.observation.attempt.AttemptAppendOutcome
import dev.tramai.core.observation.attempt.AttemptHistory
import dev.tramai.core.observation.attempt.AttemptObservationStore
import dev.tramai.core.observation.attempt.AttemptOutcome
import dev.tramai.core.observation.attempt.AttemptProvenance
import dev.tramai.core.observation.attempt.InMemoryAttemptObservationStore
import dev.tramai.core.observation.attempt.ModelAttemptObservation
import dev.tramai.security.governance.query.Completeness
import dev.tramai.security.governance.query.DecisionKind
import dev.tramai.security.governance.query.DecisionRecordCoverage
import dev.tramai.security.governance.query.FactAuthority
import dev.tramai.security.governance.query.FactFreshness
import dev.tramai.security.governance.query.GovernanceCurrencyAssessment
import dev.tramai.security.governance.query.GovernanceDecisionFact
import dev.tramai.security.governance.query.GovernanceFactSummary
import dev.tramai.security.governance.query.GovernanceProjectionIdentity
import dev.tramai.security.governance.query.GovernanceProjectionView
import dev.tramai.security.governance.query.GovernanceQueryRequest
import dev.tramai.security.governance.query.GovernanceQueryResult
import dev.tramai.security.governance.query.GovernanceQueryService
import dev.tramai.security.governance.query.GovernanceSourceProvenance
import dev.tramai.security.governance.query.RawStreamCoverage
import dev.tramai.security.governance.query.ReasonCodeExposure
import dev.tramai.security.governance.query.SourceCurrency
import dev.tramai.security.governance.query.VerifiedStreamRange
import dev.tramai.security.timeline.SemanticTimelineQuery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial regression tests for the highest-risk missing-evidence and provenance claims (0.7.5f).
 *
 * Each test names a way a reconstruction could claim more than its sources establish, and fails if the
 * claim comes back.
 */
class AdversarialSemanticsTest {
    @Test
    fun `a refused projection cannot be supported merely because attempts exist`() {
        val result = reconstruct(governance = GovernanceQueryResult.UnsupportedFormat("read-model.v9"))

        // Attempts answer; governance is refused evidence. One answering source is not enough.
        assertThat(result.timeline.invocations).isNotEmpty()
        assertThat(result.status).isNotEqualTo(ReconstructionStatus.SUPPORTED)
        assertThat(result.status).isEqualTo(ReconstructionStatus.PARTIAL)
        assertThat(result.limitations).contains(ReconstructionLimitation.GOVERNANCE_SOURCE_REFUSED)
    }

    @Test
    fun `missing historical configuration never falls back to a current configuration`() {
        val result = reconstruct(source = FixedSource(HistoricalConfigurationReference.NotRecorded))

        assertThat(result.historicalConfiguration).isEqualTo(HistoricalConfigurationReference.NotRecorded)
        assertThat(result.limitations)
            .contains(ReconstructionLimitation.HISTORICAL_CONFIGURATION_NOT_RECORDED)
        // A recorded configuration would have produced a historical identity; none is invented here.
        assertThat(result.status).isEqualTo(ReconstructionStatus.PARTIAL)
    }

    @Test
    fun `a source failure propagates instead of becoming NotRecorded`() {
        val failing =
            HistoricalConfigurationSource { error("historical configuration store unreachable") }

        assertThatThrownBy { reconstructor(source = failing).reconstruct(RUN, scope()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("historical configuration store unreachable")
    }

    @Test
    fun `a source-declared reference never becomes authenticated provenance`() {
        val result = reconstruct()

        assertThat(result.status).isEqualTo(ReconstructionStatus.SUPPORTED)
        assertThat(result.limitations).contains(ReconstructionLimitation.HISTORICAL_CONFIGURATION_UNVERIFIED)

        // No vocabulary exists to claim verification, so an "authenticated" reading is not expressible.
        assertThat(ReconstructionLimitation.values().map { it.name })
            .noneMatch { it.contains("AUTHENTICATED") || it.contains("VERIFIED_REFERENCE") }
    }

    @Test
    fun `no time-to-first-token or provider-reported finish reason is fabricated`() {
        val result = reconstruct()
        val observation = result.timeline.attempts().single()

        assertThat(observation.finishReasonReported).isFalse()
        assertThat(observation.provenance).isEqualTo(AttemptProvenance.OBSERVED_IN_PROCESS)
        // Nothing in the reconstruction surface can report a latency-to-first-token it never recorded.
        val exposed =
            listOf(
                ReconstructionResult::class.java,
                result.timeline::class.java,
                result.timeline.invocations.first()::class.java,
                ModelAttemptObservation::class.java,
            )
        val exposedNames = exposed.flatMap { type -> type.methods.map { it.name.lowercase() } }
        assertThat(exposedNames).noneMatch { it.contains("ttft") || it.contains("firsttoken") }
    }

    @Test
    fun `the reconstruction queries only what it was asked for`() {
        val store = CountingStore(listOf(attempt("inv-1", 0)))
        val governance = CountingGovernance(GovernanceQueryResult.Found(view()))

        // No scope: governance must not be consulted at all.
        val unasked = reconstructor(store = store, governance = governance).reconstruct(RUN, null)
        assertThat(governance.queries.get()).isZero()
        assertThat(unasked.limitations).contains(ReconstructionLimitation.GOVERNANCE_NOT_OBSERVED)

        // A scope: exactly one governance read.
        reconstructor(store = store, governance = governance).reconstruct(RUN, scope())
        assertThat(governance.queries.get()).isEqualTo(1)
        assertThat(store.appends.get()).isZero()
    }

    @Test
    fun `values the source recorded are preserved and no unrecorded canary appears`() {
        val canary = "CANARY-9f13-da7a"
        val store = InMemoryAttemptObservationStore().apply { append(attempt("inv-1", 0, correlationId = canary)) }
        val governance =
            CountingGovernance(
                GovernanceQueryResult.Found(view(decisions = listOf(decisionFact(reasonCode = "$canary-reason")))),
            )
        val result = reconstructor(store = store, governance = governance).reconstruct(RUN, scope())

        val rendered = result.toString() + "\n" + result.timeline.toString()

        // Scope of this proof: it shows recorded values are carried through and nothing is invented.
        // It does NOT prove end-to-end confidentiality for prompts, responses or tool payloads through
        // a producer path - those fields do not exist in this surface, and that proof is 0.7.5g.
        // The recorded values surface (so the rendering is not vacuous) ...
        assertThat(rendered).contains(canary)
        assertThat(rendered).contains("$canary-reason")
        // ... and a canary that was never recorded anywhere cannot appear at all.
        assertThat(rendered).doesNotContain("CANARY-never-recorded")
    }

    private fun reconstruct(
        source: HistoricalConfigurationSource = FixedSource(recorded()),
        governance: GovernanceQueryResult =
            GovernanceQueryResult.Found(view()),
        store: AttemptObservationStore =
            InMemoryAttemptObservationStore().apply { append(attempt("inv-1", 0)) },
    ): ReconstructionResult =
        reconstructor(source = source, governance = governance, store = store)
            .reconstruct(RUN, scope())

    private fun reconstructor(
        source: HistoricalConfigurationSource = FixedSource(recorded()),
        governance: GovernanceQueryResult = GovernanceQueryResult.Found(view()),
        store: AttemptObservationStore = InMemoryAttemptObservationStore().apply { append(attempt("inv-1", 0)) },
    ) = EvidenceBackedReconstructor(SemanticTimelineQuery.of(store, CountingGovernance(governance)), source)

    private fun reconstructor(
        store: AttemptObservationStore,
        governance: GovernanceQueryService,
        source: HistoricalConfigurationSource = FixedSource(recorded()),
    ) = EvidenceBackedReconstructor(SemanticTimelineQuery.of(store, governance), source)

    private fun scope() = GovernanceQueryRequest("wl", "env", "dep", RUN)

    private class CountingStore(
        private val observations: List<ModelAttemptObservation>,
    ) : AttemptObservationStore {
        val appends = AtomicInteger()

        override fun append(observation: ModelAttemptObservation): AttemptAppendOutcome {
            appends.incrementAndGet()
            error("reconstruction must not write")
        }

        override fun findByWorkflowRun(workflowRunId: String): AttemptHistory = AttemptHistory.Available(observations)

        override fun findByInvocation(invocationId: String): AttemptHistory =
            AttemptHistory.Available(observations.filter { it.invocationId == invocationId })
    }

    private class CountingGovernance(
        private val result: GovernanceQueryResult,
    ) : GovernanceQueryService {
        val queries = AtomicInteger()

        override fun query(request: GovernanceQueryRequest): GovernanceQueryResult {
            queries.incrementAndGet()
            return result
        }
    }

    private class FixedSource(
        private val reference: HistoricalConfigurationReference,
    ) : HistoricalConfigurationSource {
        override fun referenceFor(workflowRunId: String): HistoricalConfigurationReference = reference
    }

    private fun recorded(): HistoricalConfigurationReference.Recorded =
        HistoricalConfigurationReference.Recorded(policyVersion = "policy-7")

    private fun attempt(
        invocationId: String,
        attempt: Int,
        correlationId: String = "corr-1",
    ) = ModelAttemptObservation(
        invocationId = invocationId,
        attempt = attempt,
        workflowRunId = RUN,
        correlationId = correlationId,
        routeIndex = 0,
        providerId = "openai",
        requestedModel = "gpt-x",
        reportedModel = "gpt-x-2026",
        startedAt = Instant.parse("2026-10-21T10:00:00Z"),
        completedAt = Instant.parse("2026-10-21T10:00:01Z"),
        durationMillis = 1_000,
        outcome = AttemptOutcome.SUCCEEDED,
        failureCategory = null,
        finishReason = "STOP",
        finishReasonReported = false,
        inputTokens = 10,
        outputTokens = 5,
        thinkingTokens = null,
        provenance = AttemptProvenance.OBSERVED_IN_PROCESS,
    )

    private fun view(decisions: List<GovernanceDecisionFact> = listOf(decisionFact())): GovernanceProjectionView =
        GovernanceProjectionView(
            identity = GovernanceProjectionIdentity("wl", "env", "dep", RUN),
            formatVersion = "governance-read-model.v1",
            materializedAt = Instant.parse("2026-10-21T10:00:00Z"),
            provenance = GovernanceSourceProvenance(1L, 2L, "hash-2", "rev-1"),
            currency =
                GovernanceCurrencyAssessment(
                    registration = SourceCurrency.UNKNOWN,
                    audit = SourceCurrency.UNKNOWN,
                    note = "no fresh observation",
                ),
            registration = factSummary(FactAuthority.AUTHORITATIVE_PERSISTED),
            declaredRunIdentity = factSummary(FactAuthority.PRODUCER_DECLARED, listOf("authenticated identity")),
            decisions = decisions,
            telemetry = factSummary(FactAuthority.TELEMETRY),
        )

    private fun factSummary(
        authority: FactAuthority,
        mustNotInfer: List<String> = emptyList(),
    ) = GovernanceFactSummary(
        authority = authority,
        freshness = FactFreshness.HISTORICAL,
        established = "established",
        mustNotInfer = mustNotInfer,
        valuePresent = true,
        observedVersion = 1L,
        sourceVersion = null,
        watermark = null,
    )

    private fun decisionFact(reasonCode: String? = null) =
        GovernanceDecisionFact(
            kind = DecisionKind.POLICY_DECISION,
            fact = factSummary(FactAuthority.POLICY_AUTHORIZATION_RECORD, listOf("execution")),
            rawCoverage = RawStreamCoverage(VerifiedStreamRange(1L, 2L), "hash-2"),
            recordCoverage =
                DecisionRecordCoverage(
                    completeness = Completeness.COMPLETE,
                    accountedPositions = 2L,
                    unrecordedDecisionPositions = emptySet(),
                    unclassifiedPositions = emptySet(),
                    classificationRevision = "rev-1",
                ),
            decision = "ALLOW",
            reasonCode = reasonCode,
            reasonExposure = if (reasonCode == null) ReasonCodeExposure.NOT_RECORDED else ReasonCodeExposure.EXPOSED,
        )

    private companion object {
        const val RUN = "run-1"
    }
}
