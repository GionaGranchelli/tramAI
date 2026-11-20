package dev.tramai.security.reconstruction

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.observation.attempt.AttemptOutcome
import dev.tramai.core.observation.attempt.AttemptProvenance
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
import dev.tramai.security.governance.query.GovernanceSourceProvenance
import dev.tramai.security.governance.query.RawStreamCoverage
import dev.tramai.security.governance.query.SourceCurrency
import dev.tramai.security.governance.query.VerifiedStreamRange
import dev.tramai.security.timeline.AttemptHistoryState
import dev.tramai.security.timeline.CorrelationEvidence
import dev.tramai.security.timeline.GovernanceSection
import dev.tramai.security.timeline.InvocationTimeline
import dev.tramai.security.timeline.ObservedUsage
import dev.tramai.security.timeline.SemanticTimeline
import dev.tramai.security.timeline.SemanticTimelineQuery
import dev.tramai.security.timeline.TimelineRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behaviour of the 0.7.5e reconstructor: it reads, it classifies, and it does nothing else.
 *
 * Every collaborator is a fake, so the assertions are about *what the reconstructor asked for* and
 * *what it was willing to conclude*, not about the stores.
 */
class EvidenceBackedReconstructorTest {
    @Test
    fun `the historical reference comes from the source, not from the caller`() {
        val source = FixedSource(recorded())
        val result = reconstructor(source = source).reconstruct(RUN)

        assertThat(result.historicalConfiguration).isEqualTo(source.reference)
        assertThat(result.request.timeline.workflowRunId).isEqualTo(RUN)
        assertThat(result.status).isEqualTo(ReconstructionStatus.SUPPORTED)
    }

    @Test
    fun `a source that recorded nothing yields a limitation, never a substituted value`() {
        val result =
            reconstructor(source = FixedSource(HistoricalConfigurationReference.NotRecorded)).reconstruct(RUN)

        assertThat(result.historicalConfiguration).isEqualTo(HistoricalConfigurationReference.NotRecorded)
        assertThat(result.status).isEqualTo(ReconstructionStatus.PARTIAL)
        assertThat(result.limitations).containsExactly(ReconstructionLimitation.HISTORICAL_CONFIGURATION_NOT_RECORDED)
    }

    @Test
    fun `each collaborator is read exactly once, with the requested run`() {
        val query = CountingQuery(timeline = timeline())
        val source = FixedSource(recorded())
        reconstructor(query, source).reconstruct(RUN)

        assertThat(query.calls.get()).isEqualTo(1)
        assertThat(query.lastRequest!!.workflowRunId).isEqualTo(RUN)
        assertThat(source.calls.get()).isEqualTo(1)
        assertThat(source.lastRunId).isEqualTo(RUN)
    }

    @Test
    fun `the governance scope reaches the timeline read unchanged`() {
        val query = CountingQuery(timeline = timeline())
        val scope = scope(RUN)
        reconstructor(query, FixedSource(recorded())).reconstruct(RUN, scope)

        assertThat(query.lastRequest!!.governanceScope).isSameAs(scope)
        assertThat(query.calls.get()).isEqualTo(1)
    }

    @Test
    fun `a scope naming another run is refused before anything is read`() {
        val query = CountingQuery(timeline = timeline())

        assertThatThrownBy {
            reconstructor(query, FixedSource(recorded())).reconstruct(RUN, scope("run-2"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("timeline-governance-scope-run-mismatch")

        assertThat(query.calls.get()).isZero()
    }

    @Test
    fun `uninstrumented history is never turned into zero attempts`() {
        val result =
            reconstructor(
                CountingQuery(timeline = timeline(history = AttemptHistoryState.UNINSTRUMENTED)),
                FixedSource(recorded()),
            ).reconstruct(RUN)

        assertThat(result.attempts).isEqualTo(AttemptHistoryState.UNINSTRUMENTED)
        assertThat(result.timeline.invocations).isEmpty()
        assertThat(result.timeline.usage).isNull()
        assertThat(result.limitations).contains(ReconstructionLimitation.ATTEMPTS_UNINSTRUMENTED)
    }

    @Test
    fun `a refused projection is reported as refused, not repaired`() {
        val refused = GovernanceSection.UnsupportedFormat("governance-read-model.v9")
        val result =
            reconstructor(CountingQuery(timeline = timeline(governance = refused)), FixedSource(recorded()))
                .reconstruct(RUN)

        assertThat(result.governance).isSameAs(refused)
        assertThat(result.status).isEqualTo(ReconstructionStatus.PARTIAL)
        assertThat(result.limitations)
            .containsExactly(
                ReconstructionLimitation.GOVERNANCE_SOURCE_REFUSED,
                ReconstructionLimitation.HISTORICAL_CONFIGURATION_UNVERIFIED,
            )
    }

    @Test
    fun `a store failure propagates instead of being reported as absent evidence`() {
        val failing = SemanticTimelineQuery { throw IllegalStateException("audit store unavailable") }

        assertThatThrownBy { reconstructor(failing, FixedSource(recorded())).reconstruct(RUN) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("audit store unavailable")
    }

    @Test
    fun `the reconstructor holds only its two read collaborators`() {
        val fields = EvidenceBackedReconstructor::class.java.declaredFields.map { it.type }

        // Field types pin what the shipped composition can reach; an arbitrary implementation of
        // either interface is unverified, which is why this asserts shape and not behaviour.
        assertThat(fields)
            .containsExactlyInAnyOrder(SemanticTimelineQuery::class.java, HistoricalConfigurationSource::class.java)
        // Ignore the synthetic members the compiler and the coverage instrumentation add.
        val declared =
            EvidenceBackedReconstructor::class.java.declaredMethods
                .map { it.name }
                .filterNot { it.startsWith("\$") || it.contains("\$default") }
        assertThat(declared).containsExactly("reconstruct")
    }

    @Test
    fun `reconstruction of the same evidence is repeatable`() {
        val reconstructor =
            reconstructor(
                CountingQuery(timeline = timeline(history = AttemptHistoryState.EMPTY)),
                FixedSource(recorded()),
            )

        val first = reconstructor.reconstruct(RUN)
        val second = reconstructor.reconstruct(RUN)

        assertThat(first.status).isEqualTo(second.status)
        assertThat(first.limitations).isEqualTo(second.limitations)
        assertThat(first.timeline).isEqualTo(second.timeline)
    }

    private fun reconstructor(
        query: SemanticTimelineQuery = CountingQuery(timeline = timeline()),
        source: HistoricalConfigurationSource = FixedSource(recorded()),
    ) = EvidenceBackedReconstructor(query, source)

    private class CountingQuery(
        private val timeline: SemanticTimeline,
    ) : SemanticTimelineQuery {
        val calls = AtomicInteger()
        var lastRequest: TimelineRequest? = null

        override fun timeline(request: TimelineRequest): SemanticTimeline {
            calls.incrementAndGet()
            lastRequest = request
            return timeline
        }
    }

    private class FixedSource(
        val reference: HistoricalConfigurationReference,
    ) : HistoricalConfigurationSource {
        val calls = AtomicInteger()
        var lastRunId: String? = null

        override fun referenceFor(workflowRunId: String): HistoricalConfigurationReference {
            calls.incrementAndGet()
            lastRunId = workflowRunId
            return reference
        }
    }

    private fun scope(runId: String) = GovernanceQueryRequest("wl", "env", "dep", runId)

    private fun recorded(): HistoricalConfigurationReference.Recorded =
        HistoricalConfigurationReference.Recorded(
            configuration = WorkloadConfigurationIdentity(ConfigurationId("cfg-1"), ConfigurationVersion("v1")),
            policyVersion = "policy-7",
        )

    private fun timeline(
        governance: GovernanceSection = GovernanceSection.Snapshot(view()),
        history: AttemptHistoryState = AttemptHistoryState.RECORDED,
    ): SemanticTimeline {
        val invocations =
            if (history == AttemptHistoryState.RECORDED) {
                listOf(InvocationTimeline("inv-1", "corr-1", listOf(observation())))
            } else {
                emptyList()
            }
        return SemanticTimeline(
            workflowRunId = RUN,
            history = history,
            invocations = invocations,
            usage =
                if (invocations.isEmpty()) {
                    null
                } else {
                    ObservedUsage(1, 0, 0, 10L, 5L, null)
                },
            governance = governance,
            correlation = CorrelationEvidence.SAME_WORKFLOW_RUN,
        )
    }

    private fun observation() =
        ModelAttemptObservation(
            invocationId = "inv-1",
            attempt = 0,
            workflowRunId = RUN,
            correlationId = "corr-1",
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

    private fun view(): GovernanceProjectionView =
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
            decisions = listOf(decisionFact()),
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

    private fun decisionFact() =
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
        )

    private companion object {
        const val RUN = "run-1"
    }
}
