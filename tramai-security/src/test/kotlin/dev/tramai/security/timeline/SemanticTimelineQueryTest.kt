package dev.tramai.security.timeline

import dev.tramai.core.observation.attempt.AttemptAppendOutcome
import dev.tramai.core.observation.attempt.AttemptHistory
import dev.tramai.core.observation.attempt.AttemptObservationStore
import dev.tramai.core.observation.attempt.AttemptOutcome
import dev.tramai.core.observation.attempt.AttemptProvenance
import dev.tramai.core.observation.attempt.ModelAttemptObservation
import dev.tramai.core.observation.attempt.UninstrumentedAttemptObservationStore
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
import dev.tramai.security.governance.query.SourceCurrency
import dev.tramai.security.governance.query.VerifiedStreamRange
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Epic 0.7.5c: the timeline projects stored evidence and adds none of its own.
 *
 * Each test names one invariant from the task brief, so a reviewer can map claim to check.
 */
class SemanticTimelineQueryTest {
    // ---------------------------------------------------------------- identity and separation

    @Test
    fun `a retry stays two attempts of one invocation and a repeated call stays two invocations`() {
        val timeline =
            query(
                attempts(
                    observation("inv-a", attempt = 0, startedAt = "10:00:00", outcome = AttemptOutcome.FAILED),
                    observation("inv-a", attempt = 1, startedAt = "10:00:05"),
                    observation("inv-b", attempt = 0, startedAt = "10:01:00"),
                ),
            ).timeline(TimelineRequest(RUN))

        assertThat(timeline.history).isEqualTo(AttemptHistoryState.RECORDED)
        assertThat(timeline.invocations).hasSize(2)

        val first = timeline.invocations.first()
        assertThat(first.invocationId).isEqualTo("inv-a")
        assertThat(first.attempts.map { it.attempt }).containsExactly(0, 1)
        assertThat(first.finalOutcome).isEqualTo(AttemptOutcome.SUCCEEDED)

        val second = timeline.invocations.last()
        assertThat(second.invocationId).isEqualTo("inv-b")
        assertThat(second.attempts).hasSize(1)
    }

    @Test
    fun `ordering comes from recorded timestamps and identity, never from store order`() {
        val timeline =
            query(
                attempts(
                    observation("inv-z", attempt = 0, startedAt = "10:05:00"),
                    observation("inv-a", attempt = 1, startedAt = "10:02:00"),
                    observation("inv-a", attempt = 0, startedAt = "10:00:00"),
                ),
            ).timeline(TimelineRequest(RUN))

        assertThat(timeline.invocations.map { it.invocationId }).containsExactly("inv-a", "inv-z")
        assertThat(
            timeline.invocations
                .first()
                .attempts
                .map { it.attempt },
        ).containsExactly(0, 1)
        assertThat(timeline.firstAttemptAt).isEqualTo(at("10:00:00"))
        assertThat(timeline.attempts().map { it.invocationId }).containsExactly("inv-a", "inv-a", "inv-z")
    }

    @Test
    fun `a timeline is stable across repeated queries`() {
        val store =
            attempts(
                observation("inv-b", attempt = 0, startedAt = "10:05:00"),
                observation("inv-a", attempt = 0, startedAt = "10:00:00"),
            )

        val first = query(store).timeline(TimelineRequest(RUN))
        val second = query(store).timeline(TimelineRequest(RUN))

        assertThat(first).isEqualTo(second)
    }

    // ---------------------------------------------------------------- usage

    @Test
    fun `duplicate observations do not inflate usage`() {
        val duplicated = observation("inv-a", attempt = 0, tokens = Tokens(input = 10, output = 5))
        val timeline =
            query(
                attempts(
                    duplicated,
                    duplicated,
                    duplicated,
                    observation("inv-a", attempt = 1, tokens = Tokens(input = 1)),
                ),
            ).timeline(TimelineRequest(RUN))

        assertThat(timeline.invocations.single().attempts).hasSize(2)
        val usage = timeline.usage!!
        assertThat(usage.attemptsCounted).isEqualTo(2)
        assertThat(usage.duplicatesIgnored).isEqualTo(2)
        assertThat(usage.inputTokens).isEqualTo(11L)
        assertThat(usage.outputTokens).isEqualTo(5L)
    }

    @Test
    fun `unknown usage stays unknown and thinking tokens are never added to output`() {
        val timeline =
            query(
                attempts(
                    observation(
                        "inv-a",
                        attempt = 0,
                        tokens = Tokens(input = 10, output = 5, thinking = 7),
                    ),
                    observation("inv-a", attempt = 1),
                ),
            ).timeline(TimelineRequest(RUN))

        val usage = timeline.usage!!
        assertThat(usage.inputTokens).isEqualTo(10L)
        assertThat(usage.outputTokens).isEqualTo(5L)
        // Reported as its own component, never folded into the completion count.
        assertThat(usage.thinkingTokens).isEqualTo(7L)
        assertThat(usage.attemptsWithoutUsage).isEqualTo(1)

        // A component no attempt reported is unknown, not zero.
        val silent = query(attempts(observation("inv-a", attempt = 0))).timeline(TimelineRequest(RUN)).usage!!
        assertThat(silent.inputTokens).isNull()
        assertThat(silent.outputTokens).isNull()
        assertThat(silent.thinkingTokens).isNull()
        assertThat(silent.attemptsWithoutUsage).isEqualTo(1)
    }

    @Test
    fun `no attempt at all means no usage summary rather than a zero summary`() {
        assertThat(query(attempts()).timeline(TimelineRequest(RUN)).usage).isNull()
    }

    // ---------------------------------------------------------------- missing vs empty

    @Test
    fun `uninstrumented history is not empty history`() {
        val timeline = query(UninstrumentedAttemptObservationStore).timeline(TimelineRequest(RUN))

        assertThat(timeline.history).isEqualTo(AttemptHistoryState.UNINSTRUMENTED)
        assertThat(timeline.invocations).isEmpty()
        assertThat(timeline.usage).isNull()

        val empty = query(attempts()).timeline(TimelineRequest(RUN))

        assertThat(empty.history).isEqualTo(AttemptHistoryState.EMPTY)
        assertThat(empty.invocations).isEmpty()
    }

    // ---------------------------------------------------------------- correlation

    @Test
    fun `missing correlation remains unresolved`() {
        // No governance scope asked for: nothing is claimed about governance or a link to it.
        val unasked = query(attempts(observation("inv-a", attempt = 0))).timeline(TimelineRequest(RUN))
        assertThat(unasked.governance).isEqualTo(GovernanceSection.NotQueried)
        assertThat(unasked.correlation).isEqualTo(CorrelationEvidence.NONE)

        // Governance scope asked for, but no governance query configured in this process.
        val unconfigured =
            query(attempts(observation("inv-a", attempt = 0)))
                .timeline(TimelineRequest(RUN, scopeFor(RUN)))
        assertThat(unconfigured.governance).isEqualTo(GovernanceSection.NotConfigured)
        assertThat(unconfigured.correlation).isEqualTo(CorrelationEvidence.NONE)

        // A projection exists but names no run: still unresolved, never guessed from timing.
        val runless =
            query(attempts(observation("inv-a", attempt = 0)), service(GovernanceQueryResult.Found(view(runId = null))))
                .timeline(TimelineRequest(RUN, scopeFor(RUN)))
        assertThat(runless.governance).isInstanceOf(GovernanceSection.Snapshot::class.java)
        assertThat(runless.correlation).isEqualTo(CorrelationEvidence.NONE)
    }

    @Test
    fun `a governance snapshot for the same run is the only correlation claimed`() {
        val timeline =
            query(attempts(observation("inv-a", attempt = 0)), service(GovernanceQueryResult.Found(view(runId = RUN))))
                .timeline(TimelineRequest(RUN, scopeFor(RUN)))

        assertThat(timeline.correlation).isEqualTo(CorrelationEvidence.SAME_WORKFLOW_RUN)
        val snapshot = timeline.governance as GovernanceSection.Snapshot
        assertThat(snapshot.decisionKinds).containsExactly(DecisionKind.POLICY_DECISION)
    }

    @Test
    fun `a governance scope naming another run is refused rather than presented`() {
        assertThatThrownBy { TimelineRequest(RUN, scopeFor("another-run")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("timeline-governance-scope-run-mismatch")
    }

    @Test
    fun `an unmaterialized projection is reported as such and not as absent governance`() {
        val timeline =
            query(attempts(observation("inv-a", attempt = 0)), service(GovernanceQueryResult.NotMaterialized))
                .timeline(TimelineRequest(RUN, scopeFor(RUN)))

        assertThat(timeline.governance).isEqualTo(GovernanceSection.NotMaterialized)
        assertThat(timeline.correlation).isEqualTo(CorrelationEvidence.NONE)
        // Attempts are still reported: a governance gap does not erase observations.
        assertThat(timeline.invocations).hasSize(1)
    }

    // ---------------------------------------------------------------- authority and purity

    @Test
    fun `observations stay observed telemetry and never become governance evidence`() {
        val timeline =
            query(attempts(observation("inv-a", attempt = 0)), service(GovernanceQueryResult.Found(view(runId = RUN))))
                .timeline(TimelineRequest(RUN, scopeFor(RUN)))

        assertThat(timeline.attempts().map { it.provenance }.distinct())
            .containsExactly(AttemptProvenance.OBSERVED_IN_PROCESS)

        // Governance facts keep their own authority, unaffected by the attempts beside them.
        val view = (timeline.governance as GovernanceSection.Snapshot).view
        assertThat(view.registration.authority).isEqualTo(FactAuthority.AUTHORITATIVE_PERSISTED)
        assertThat(
            view.decisions
                .single()
                .fact.authority,
        ).isEqualTo(FactAuthority.POLICY_AUTHORIZATION_RECORD)
        assertThat(
            view.decisions
                .single()
                .fact.mustNotInfer,
        ).isNotEmpty()
    }

    @Test
    fun `a query reads and never writes`() {
        var reads = 0
        val countingStore =
            object : AttemptObservationStore {
                override fun append(observation: ModelAttemptObservation): AttemptAppendOutcome =
                    error("a timeline query must never append")

                override fun findByWorkflowRun(workflowRunId: String): AttemptHistory {
                    reads++
                    return AttemptHistory.Available(listOf(observation("inv-a", attempt = 0)))
                }

                override fun findByInvocation(invocationId: String): AttemptHistory =
                    error("a timeline query addresses a run, not an invocation")
            }

        repeat(3) { SemanticTimelineQuery.of(countingStore).timeline(TimelineRequest(RUN)) }

        assertThat(reads).isEqualTo(3)
    }

    // ---------------------------------------------------------------- fixtures

    /** Token counts as a provider reported them; a `null` component was never reported. */
    private data class Tokens(
        val input: Int? = null,
        val output: Int? = null,
        val thinking: Int? = null,
    )

    private fun query(
        store: AttemptObservationStore,
        governance: GovernanceQueryService? = null,
    ) = SemanticTimelineQuery.of(store, governance)

    private fun attempts(vararg observations: ModelAttemptObservation) =
        object : AttemptObservationStore {
            override fun append(observation: ModelAttemptObservation) = AttemptAppendOutcome.RECORDED

            override fun findByWorkflowRun(workflowRunId: String) = AttemptHistory.Available(observations.toList())

            override fun findByInvocation(invocationId: String) = AttemptHistory.Available(emptyList())
        }

    private fun service(result: GovernanceQueryResult) = GovernanceQueryService { result }

    private fun scopeFor(runId: String) = GovernanceQueryRequest("wl", "env", "dep", runId)

    private fun observation(
        invocationId: String,
        attempt: Int,
        startedAt: String = "10:00:00",
        tokens: Tokens = Tokens(),
        outcome: AttemptOutcome = AttemptOutcome.SUCCEEDED,
    ) = ModelAttemptObservation(
        invocationId = invocationId,
        attempt = attempt,
        workflowRunId = RUN,
        correlationId = "corr-1",
        routeIndex = 0,
        providerId = "openai",
        requestedModel = "gpt-x",
        reportedModel = "gpt-x-2026",
        startedAt = at(startedAt),
        completedAt = at(startedAt).plusSeconds(1),
        durationMillis = 1_000,
        outcome = outcome,
        failureCategory = null,
        finishReason = "STOP",
        finishReasonReported = false,
        inputTokens = tokens.input,
        outputTokens = tokens.output,
        thinkingTokens = tokens.thinking,
        provenance = AttemptProvenance.OBSERVED_IN_PROCESS,
    )

    private fun at(time: String): Instant = Instant.parse("2026-10-21T$time.000Z")

    private fun view(runId: String?): GovernanceProjectionView =
        GovernanceProjectionView(
            identity = GovernanceProjectionIdentity("wl", "env", "dep", runId),
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
