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
import dev.tramai.security.timeline.TimelineRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Contract tests for the 0.7.5d reconstruction vocabulary.
 *
 * These prove classifications, not an engine: each test hands already-read evidence to
 * [ReconstructionResult] and asserts what the contract is willing to say about it.
 */
class ReconstructionContractTest {
    @Test
    fun `complete evidence supports the declared scope`() {
        val result = result(timeline(history = AttemptHistoryState.RECORDED), recorded())

        assertThat(result.status).isEqualTo(ReconstructionStatus.SUPPORTED)
        assertThat(result.limitations).isEmpty()
        assertThat(result.attempts).isEqualTo(AttemptHistoryState.RECORDED)
        assertThat(result.governance).isInstanceOf(GovernanceSection.Snapshot::class.java)
    }

    @Test
    fun `a missing historical configuration can only be partial`() {
        val result =
            result(
                timeline(history = AttemptHistoryState.RECORDED),
                HistoricalConfigurationReference.NotRecorded,
            )

        assertThat(result.status).isEqualTo(ReconstructionStatus.PARTIAL)
        assertThat(result.limitations)
            .containsExactly(ReconstructionLimitation.HISTORICAL_CONFIGURATION_NOT_RECORDED)
    }

    @Test
    fun `an empty recorded configuration is refused instead of passing for evidence`() {
        assertThatThrownBy { HistoricalConfigurationReference.Recorded() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reconstruction-empty-recorded-configuration")
    }

    @Test
    fun `missing governance evidence stays partial rather than complete`() {
        val result =
            result(
                timeline(governance = GovernanceSection.NotQueried, history = AttemptHistoryState.RECORDED),
                recorded(),
            )

        assertThat(result.status).isEqualTo(ReconstructionStatus.PARTIAL)
        assertThat(result.limitations).containsExactly(ReconstructionLimitation.GOVERNANCE_NOT_OBSERVED)
    }

    @Test
    fun `nothing observed is unsupported and a recorded configuration does not fix that`() {
        val result =
            result(
                timeline(governance = GovernanceSection.NotConfigured, history = AttemptHistoryState.UNINSTRUMENTED),
                recorded(),
            )

        // A configuration identity is not evidence of a run: it cannot lift UNSUPPORTED to anything.
        assertThat(result.status).isEqualTo(ReconstructionStatus.UNSUPPORTED)
        assertThat(result.limitations)
            .containsExactlyInAnyOrder(
                ReconstructionLimitation.GOVERNANCE_NOT_OBSERVED,
                ReconstructionLimitation.ATTEMPTS_UNINSTRUMENTED,
            )
    }

    @Test
    fun `a refused projection is classified and never converted into a snapshot`() {
        val unsupported = GovernanceSection.UnsupportedFormat("governance-read-model.v9")
        val invalid = GovernanceSection.InvalidStoredProjection("digest mismatch")

        for (governance in listOf(unsupported, invalid)) {
            val result = result(timeline(governance = governance, history = AttemptHistoryState.RECORDED), recorded())

            assertThat(result.status).isEqualTo(ReconstructionStatus.PARTIAL)
            assertThat(result.governance).isSameAs(governance)
            assertThat(result.governance).isNotInstanceOf(GovernanceSection.Snapshot::class.java)
            assertThat(result.limitations).containsExactly(ReconstructionLimitation.GOVERNANCE_SOURCE_REFUSED)
        }
    }

    @Test
    fun `uninstrumented history is never reported as zero attempts`() {
        val uninstrumented = result(timeline(history = AttemptHistoryState.UNINSTRUMENTED), recorded())
        assertThat(uninstrumented.attempts).isEqualTo(AttemptHistoryState.UNINSTRUMENTED)
        assertThat(uninstrumented.timeline.invocations).isEmpty()
        assertThat(uninstrumented.timeline.usage).isNull()
        assertThat(uninstrumented.limitations).contains(ReconstructionLimitation.ATTEMPTS_UNINSTRUMENTED)

        // Recording was configured and found nothing: evidence of absence, not an uninstrumented gap.
        val empty = result(timeline(history = AttemptHistoryState.EMPTY), recorded())
        assertThat(empty.attempts).isEqualTo(AttemptHistoryState.EMPTY)
        assertThat(empty.limitations).doesNotContain(ReconstructionLimitation.ATTEMPTS_UNINSTRUMENTED)
    }

    @Test
    fun `a recorded ALLOW is not proof of execution`() {
        val result = result(timeline(history = AttemptHistoryState.EMPTY), recorded())

        val snapshot = result.governance as GovernanceSection.Snapshot
        assertThat(
            snapshot.view.decisions
                .single()
                .decision,
        ).isEqualTo("ALLOW")

        // The permission is reported, and nothing is inferred from it.
        assertThat(result.timeline.invocations).isEmpty()
        assertThat(result.timeline.usage).isNull()
        assertThat(result.limitations).contains(ReconstructionLimitation.EXECUTION_NOT_ESTABLISHED)
    }

    @Test
    fun `observed attempts never become authoritative and decisions never become telemetry`() {
        val result = result(timeline(history = AttemptHistoryState.RECORDED), recorded())

        assertThat(
            result.timeline
                .attempts()
                .single()
                .provenance,
        ).isEqualTo(AttemptProvenance.OBSERVED_IN_PROCESS)
        val decision = (result.governance as GovernanceSection.Snapshot).view.decisions.single()
        assertThat(decision.fact.authority).isEqualTo(FactAuthority.POLICY_AUTHORIZATION_RECORD)
    }

    @Test
    fun `unknown usage is named rather than zeroed`() {
        val silent =
            result(
                timeline(history = AttemptHistoryState.RECORDED, tokens = false),
                recorded(),
            )
        assertThat(silent.timeline.usage).isNull()
        assertThat(silent.limitations).containsExactly(ReconstructionLimitation.USAGE_UNKNOWN)

        val reported = result(timeline(history = AttemptHistoryState.RECORDED, tokens = true), recorded())
        assertThat(reported.timeline.usage!!.inputTokens).isEqualTo(10L)
        assertThat(reported.limitations).isEmpty()
    }

    @Test
    fun `shared run identity alone leaves correlation unresolved`() {
        val result =
            result(
                timeline(history = AttemptHistoryState.RECORDED, correlation = CorrelationEvidence.NONE),
                recorded(),
            )

        assertThat(result.status).isEqualTo(ReconstructionStatus.SUPPORTED)
        assertThat(result.limitations).containsExactly(ReconstructionLimitation.CORRELATION_UNRESOLVED)
    }

    @Test
    fun `evidence for another run is refused rather than reconstructed`() {
        assertThatThrownBy {
            ReconstructionResult.of(
                ReconstructionRequest(TimelineRequest(RUN), recorded()),
                timeline(workflowRunId = "run-2"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reconstruction-evidence-run-mismatch")
    }

    @Test
    fun `the classification is derived from the evidence and repeats identically`() {
        val evidence = timeline(history = AttemptHistoryState.RECORDED)
        val once = ReconstructionResult.of(ReconstructionRequest(TimelineRequest(RUN), recorded()), evidence)
        val twice = ReconstructionResult.of(ReconstructionRequest(TimelineRequest(RUN), recorded()), evidence)

        assertThat(once.status).isEqualTo(twice.status)
        assertThat(once.limitations).isEqualTo(twice.limitations)
        assertThat(ReconstructionResult.statusOf(evidence, recorded()))
            .isEqualTo(ReconstructionResult.statusOf(evidence, recorded()))
    }

    @Test
    fun `no exposed type can express a protected payload`() {
        val forbidden = listOf("prompt", "response", "payload", "reasoning", "content")
        val exposed =
            listOf(
                ReconstructionResult::class.java,
                ReconstructionRequest::class.java,
                HistoricalConfigurationReference.Recorded::class.java,
                SemanticTimeline::class.java,
                InvocationTimeline::class.java,
                ObservedUsage::class.java,
                GovernanceSection.Snapshot::class.java,
                ModelAttemptObservation::class.java,
                GovernanceProjectionView::class.java,
            )

        val offenders =
            exposed.flatMap { type ->
                type.methods
                    .filter { it.name.startsWith("get") && it.parameterCount == 0 }
                    .map { "${type.simpleName}.${it.name}" }
                    .filter { name ->
                        val lower = name.lowercase()
                        forbidden.any { lower.contains(it) }
                    }
            }

        assertThat(offenders).isEmpty()
    }

    private fun result(
        timeline: SemanticTimeline,
        configuration: HistoricalConfigurationReference,
    ): ReconstructionResult =
        ReconstructionResult.of(
            ReconstructionRequest(TimelineRequest(timeline.workflowRunId), configuration),
            timeline,
        )

    private fun recorded(): HistoricalConfigurationReference.Recorded =
        HistoricalConfigurationReference.Recorded(
            configuration =
                WorkloadConfigurationIdentity(
                    ConfigurationId("cfg-1"),
                    ConfigurationVersion("v1"),
                ),
            policyVersion = "policy-7",
        )

    private fun timeline(
        workflowRunId: String = RUN,
        governance: GovernanceSection = GovernanceSection.Snapshot(view()),
        history: AttemptHistoryState = AttemptHistoryState.RECORDED,
        correlation: CorrelationEvidence = CorrelationEvidence.SAME_WORKFLOW_RUN,
        tokens: Boolean = true,
    ): SemanticTimeline {
        val invocations =
            if (history == AttemptHistoryState.RECORDED) {
                listOf(InvocationTimeline("inv-1", "corr-1", listOf(observation(workflowRunId, tokens))))
            } else {
                emptyList()
            }
        return SemanticTimeline(
            workflowRunId = workflowRunId,
            history = history,
            invocations = invocations,
            // Mirrors the 0.7.5c read API: no usage record at all when nothing reported a count.
            usage = if (invocations.isEmpty() || !tokens) null else usage(),
            governance = governance,
            correlation = correlation,
        )
    }

    private fun usage() =
        ObservedUsage(
            attemptsCounted = 1,
            duplicatesIgnored = 0,
            attemptsWithoutUsage = 0,
            inputTokens = 10L,
            outputTokens = 5L,
            thinkingTokens = null,
        )

    private fun observation(
        runId: String,
        tokens: Boolean,
    ) = ModelAttemptObservation(
        invocationId = "inv-1",
        attempt = 0,
        workflowRunId = runId,
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
        inputTokens = if (tokens) 10 else null,
        outputTokens = if (tokens) 5 else null,
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
            registration = factSummary(FactAuthority.AUTHORITATIVE_PERSISTED, emptyList()),
            declaredRunIdentity = factSummary(FactAuthority.PRODUCER_DECLARED, listOf("authenticated identity")),
            decisions = listOf(decisionFact()),
            telemetry = factSummary(FactAuthority.TELEMETRY, emptyList()),
        )

    private fun factSummary(
        authority: FactAuthority,
        mustNotInfer: List<String>,
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
