package dev.tramai.security.timeline

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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Gates 3 and 4 of Epic 0.7.5g — historical identity, causal truth, and usage accounting, proven on the
 * full read path (`AttemptObservationStore -> SemanticTimelineQuery.of(...) -> timeline`).
 *
 * Every number here is exact. Where a total is unknown the assertion is `null`, never a smaller number:
 * an unknown total and a zero total are different claims, and a test that accepted either would prove
 * nothing.
 */
class TimelineEvidenceIntegrityTest {
    // ------------------------------------------------------------------ Gate 3: causal truth

    @Test
    fun `shared run identity is not a decision-to-attempt edge`() {
        val store = storeWith(attempt())
        val timeline =
            SemanticTimelineQuery
                .of(store, governance(GovernanceQueryResult.Found(view())))
                .timeline(TimelineRequest(RUN, scope()))

        // The only link available is the run both sides were recorded under.
        assertThat(timeline.correlation).isEqualTo(CorrelationEvidence.SAME_WORKFLOW_RUN)
        // The vocabulary cannot express "this decision caused this attempt": there is no third variant.
        assertThat(CorrelationEvidence.entries.map { it.name })
            .containsExactlyInAnyOrder("SAME_WORKFLOW_RUN", "NONE")
        // ... and no attempt grew a field naming a decision.
        assertThat(ModelAttemptObservation::class.java.methods.map { it.name.lowercase() })
            .noneMatch { it.contains("decision") }
        assertThat(timeline.attempts()).hasSize(1)
    }

    @Test
    fun `status and limitations are judged from each source independently`() {
        // Governance refused, attempts recorded: partial, and the refusal is named.
        val refused =
            SemanticTimelineQuery
                .of(storeWith(attempt()), governance(GovernanceQueryResult.InvalidStoredProjection("bad")))
                .timeline(TimelineRequest(RUN, scope()))
        assertThat(refused.governance).isInstanceOf(GovernanceSection.InvalidStoredProjection::class.java)
        assertThat(refused.attempts()).hasSize(1)

        // Governance answered, attempts uninstrumented: the other side degrades on its own.
        val uninstrumented =
            SemanticTimelineQuery
                .of(UninstrumentedStore, governance(GovernanceQueryResult.Found(view())))
                .timeline(TimelineRequest(RUN, scope()))
        assertThat(uninstrumented.history).isEqualTo(AttemptHistoryState.UNINSTRUMENTED)
        assertThat(uninstrumented.attempts()).isEmpty()
        assertThat(uninstrumented.governance).isInstanceOf(GovernanceSection.Snapshot::class.java)
    }

    @Test
    fun `skewed and concurrent timestamps never establish causality`() {
        // Attempt A ends before attempt B starts, in the same run as a decision: still no edge.
        val skewed =
            storeWith(
                attempt(Spec(end = "2026-10-21T10:00:05Z")),
                attempt(Spec(invocationId = "inv-2", start = "2026-10-21T10:00:01Z", end = "2026-10-21T10:00:02Z")),
            )
        val timeline =
            SemanticTimelineQuery
                .of(skewed, governance(GovernanceQueryResult.Found(view())))
                .timeline(TimelineRequest(RUN, scope()))

        assertThat(timeline.correlation).isEqualTo(CorrelationEvidence.SAME_WORKFLOW_RUN)
        // Ordering is deterministic and evidence-based, and it is not presented as a cause.
        assertThat(timeline.attempts().map { it.invocationId + "/" + it.attempt })
            .containsExactly("inv-1/0", "inv-2/0")
    }

    // ------------------------------------------------------------------ Gate 4: usage accounting

    @Test
    fun `explicit zero usage is preserved as zero and missing usage stays unknown`() {
        val zero =
            SemanticTimelineQuery
                .of(
                    storeWith(attempt(Spec(input = 0, output = 0))),
                    governance(null),
                ).timeline(TimelineRequest(RUN))
        assertThat(zero.usage?.inputTokens).isEqualTo(0L)
        assertThat(zero.usage?.outputTokens).isEqualTo(0L)
        assertThat(zero.usage?.attemptsWithoutUsage).isEqualTo(0)
        assertThat(zero.usage?.attemptsCounted).isEqualTo(1)

        val missing =
            SemanticTimelineQuery
                .of(storeWith(attempt(Spec(input = null, output = null))), governance(null))
                .timeline(TimelineRequest(RUN))
        assertThat(missing.usage?.inputTokens).isNull()
        assertThat(missing.usage?.outputTokens).isNull()
        assertThat(missing.usage?.attemptsWithoutUsage).isEqualTo(1)
        assertThat(missing.usage?.attemptsCounted).isEqualTo(1)
    }

    @Test
    fun `a partial component is unknown rather than a smaller total`() {
        // Attempt 0 reports input only; attempt 1 reports nothing. No component is a run total.
        val timeline =
            SemanticTimelineQuery
                .of(
                    storeWith(
                        attempt(Spec(output = null)),
                        attempt(Spec(attempt = 1, input = null, output = null)),
                    ),
                    governance(null),
                ).timeline(TimelineRequest(RUN))

        assertThat(timeline.usage?.attemptsCounted).isEqualTo(2)
        assertThat(timeline.usage?.attemptsWithoutUsage).isEqualTo(1)
        assertThat(timeline.usage?.inputTokens).isNull()
        assertThat(timeline.usage?.outputTokens).isNull()
    }

    @Test
    fun `thinking tokens are never added into output tokens`() {
        val timeline =
            SemanticTimelineQuery
                .of(
                    storeWith(attempt(Spec(thinking = 400))),
                    governance(null),
                ).timeline(TimelineRequest(RUN))

        assertThat(timeline.usage?.inputTokens).isEqualTo(10L)
        assertThat(timeline.usage?.outputTokens).isEqualTo(5L)
        assertThat(timeline.usage?.thinkingTokens).isEqualTo(400L)
        // The sum of the components is nowhere reported as a total, so no reader can add reasoning twice.
        assertThat(timeline.usage?.outputTokens).isNotEqualTo(405L)
    }

    @Test
    fun `duplicate observations are counted once and retries stay separate attempts`() {
        val duplicated =
            ListStore(
                listOf(
                    attempt(),
                    attempt(),
                ),
            )
        val totals =
            SemanticTimelineQuery
                .of(duplicated, governance(null))
                .timeline(TimelineRequest(RUN))

        assertThat(totals.usage?.attemptsCounted).isEqualTo(1)
        assertThat(totals.usage?.duplicatesIgnored).isEqualTo(1)
        assertThat(totals.usage?.inputTokens).isEqualTo(10L)
        assertThat(totals.invocations).hasSize(1)
        assertThat(totals.invocations.single().attempts).hasSize(1)

        val retried =
            SemanticTimelineQuery
                .of(
                    storeWith(
                        attempt(Spec(outcome = AttemptOutcome.FAILED)),
                        attempt(Spec(attempt = 1, input = 4, output = 2)),
                    ),
                    governance(null),
                ).timeline(TimelineRequest(RUN))

        assertThat(retried.usage?.attemptsCounted).isEqualTo(2)
        assertThat(retried.usage?.inputTokens).isEqualTo(14L)
        assertThat(retried.usage?.outputTokens).isEqualTo(7L)
        assertThat(
            retried.invocations
                .single()
                .attempts
                .map { it.attempt },
        ).containsExactly(0, 1)
    }

    @Test
    fun `unreported provenance is never upgraded to a provider claim`() {
        val timeline =
            SemanticTimelineQuery
                .of(storeWith(attempt(Spec(reportedModel = null))), governance(null))
                .timeline(TimelineRequest(RUN))

        val observation = timeline.attempts().single()
        // The requested model never stands in for the model the provider claimed to use.
        assertThat(observation.requestedModel).isEqualTo("logical-model")
        assertThat(observation.reportedModel).isNull()
        // A default STOP is not a provider report, and there is no TTFT to report.
        assertThat(observation.finishReason).isEqualTo("STOP")
        assertThat(observation.finishReasonReported).isFalse()
        assertThat(ModelAttemptObservation::class.java.methods.map { it.name.lowercase() })
            .noneMatch { it.contains("ttft") || it.contains("firsttoken") }
        // Provenance stays attribution, never authority.
        assertThat(observation.provenance).isEqualTo(AttemptProvenance.OBSERVED_IN_PROCESS)
    }

    // ---------------------------------------------------------------- fixtures

    private fun governance(result: GovernanceQueryResult?) =
        result?.let { fixed ->
            object : GovernanceQueryService {
                override fun query(request: GovernanceQueryRequest): GovernanceQueryResult = fixed
            }
        }

    private fun scope() = GovernanceQueryRequest("wl", "env", "dep", RUN)

    private fun storeWith(vararg observations: ModelAttemptObservation): AttemptObservationStore =
        InMemoryAttemptObservationStore().apply { observations.forEach { append(it) } }

    /** Returns its list verbatim, so a duplicate really does reach the projection. */
    private class ListStore(
        private val observations: List<ModelAttemptObservation>,
    ) : AttemptObservationStore {
        override fun append(observation: ModelAttemptObservation) = error("read-only")

        override fun findByWorkflowRun(workflowRunId: String) = AttemptHistory.Available(observations)

        override fun findByInvocation(invocationId: String) =
            AttemptHistory.Available(observations.filter { it.invocationId == invocationId })
    }

    private object UninstrumentedStore : AttemptObservationStore {
        override fun append(observation: ModelAttemptObservation) = error("read-only")

        override fun findByWorkflowRun(workflowRunId: String) = AttemptHistory.Uninstrumented

        override fun findByInvocation(invocationId: String) = AttemptHistory.Uninstrumented
    }

    /** Valid-by-default attempt shape; call sites override one field and take the rest. */
    private data class Spec(
        val invocationId: String = "inv-1",
        val attempt: Int = 0,
        val input: Int? = 10,
        val output: Int? = 5,
        val thinking: Int? = null,
        val reportedModel: String? = "logical-model",
        val outcome: AttemptOutcome = AttemptOutcome.SUCCEEDED,
        val start: String = "2026-10-21T10:00:00Z",
        val end: String = "2026-10-21T10:00:01Z",
    )

    private fun attempt(spec: Spec = Spec()) =
        ModelAttemptObservation(
            invocationId = spec.invocationId,
            attempt = spec.attempt,
            workflowRunId = RUN,
            correlationId = "corr-1",
            routeIndex = 0,
            providerId = "primary",
            requestedModel = "logical-model",
            reportedModel = spec.reportedModel,
            startedAt = Instant.parse(spec.start),
            completedAt = Instant.parse(spec.end),
            durationMillis =
                java.time.Duration
                    .between(Instant.parse(spec.start), Instant.parse(spec.end))
                    .toMillis(),
            outcome = spec.outcome,
            failureCategory = null,
            finishReason = "STOP",
            finishReasonReported = false,
            inputTokens = spec.input,
            outputTokens = spec.output,
            thinkingTokens = spec.thinking,
            provenance = AttemptProvenance.OBSERVED_IN_PROCESS,
        )

    private fun view(decisions: List<GovernanceDecisionFact> = listOf(decision())) =
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
            registration = summary(FactAuthority.AUTHORITATIVE_PERSISTED),
            declaredRunIdentity = summary(FactAuthority.PRODUCER_DECLARED, listOf("authenticated identity")),
            decisions = decisions,
            telemetry = summary(FactAuthority.TELEMETRY),
        )

    private fun summary(
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

    private fun decision() =
        GovernanceDecisionFact(
            kind = DecisionKind.POLICY_DECISION,
            fact = summary(FactAuthority.POLICY_AUTHORIZATION_RECORD, listOf("execution")),
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
            reasonCode = null,
            reasonExposure = ReasonCodeExposure.NOT_RECORDED,
        )

    private companion object {
        const val RUN = "run-5g-integrity"
    }
}
