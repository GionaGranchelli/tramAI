package dev.tramai.security.reconstruction

import dev.tramai.core.observation.attempt.AttemptAppendOutcome
import dev.tramai.core.observation.attempt.AttemptHistory
import dev.tramai.core.observation.attempt.AttemptObservationStore
import dev.tramai.core.observation.attempt.AttemptOutcome
import dev.tramai.core.observation.attempt.AttemptProvenance
import dev.tramai.core.observation.attempt.InMemoryAttemptObservationStore
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
import dev.tramai.security.timeline.AttemptHistoryState
import dev.tramai.security.timeline.SemanticTimelineQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Missing and unknown evidence must stay missing and unknown (Epic 0.7.5f).
 *
 * These run through the **shipped** path — the real in-memory attempt store, the real timeline query
 * and the real reconstructor — so the semantics are asserted where a caller would actually meet them,
 * not on hand-assembled fakes.
 */
class MissingEvidenceSemanticsTest {
    @Test
    fun `no attempt recorded is not proof that execution did not occur`() {
        val result = reconstruct(attempts = listOf(), decisions = emptyList())

        // Recording was configured and found nothing: evidence of absence of *records*, nothing more.
        assertThat(result.attempts).isEqualTo(AttemptHistoryState.EMPTY)
        assertThat(result.limitations).contains(ReconstructionLimitation.EXECUTION_NOT_ESTABLISHED)
        assertThat(result.timeline.invocations).isEmpty()
    }

    @Test
    fun `an uninstrumented store leaves execution unknown rather than empty`() {
        val result = reconstruct(store = UninstrumentedAttemptObservationStore)

        assertThat(result.attempts).isEqualTo(AttemptHistoryState.UNINSTRUMENTED)
        assertThat(result.status).isEqualTo(ReconstructionStatus.PARTIAL)
        assertThat(result.limitations)
            .contains(
                ReconstructionLimitation.ATTEMPTS_UNINSTRUMENTED,
                ReconstructionLimitation.EXECUTION_NOT_ESTABLISHED,
            )
    }

    @Test
    fun `governance that was never queried is unevaluated, not absent`() {
        val result = reconstruct(scope = null, attempts = listOf(attempt("inv-1", 0, tokens = true)))

        assertThat(result.governance)
            .isEqualTo(dev.tramai.security.timeline.GovernanceSection.NotQueried)
        assertThat(result.limitations).contains(ReconstructionLimitation.GOVERNANCE_NOT_OBSERVED)
        // Governance being unexamined says nothing about the attempts that were observed.
        assertThat(result.timeline.invocations).hasSize(1)
    }

    @Test
    fun `a correctly shaped reference is reported as unauthenticated`() {
        val result = reconstruct(attempts = listOf(attempt("inv-1", 0, tokens = true)))

        assertThat(result.status).isEqualTo(ReconstructionStatus.SUPPORTED)
        assertThat(result.historicalConfiguration)
            .isInstanceOf(HistoricalConfigurationReference.Recorded::class.java)
        // SUPPORTED is a statement about sources answering, never about authenticity.
        assertThat(result.limitations).contains(ReconstructionLimitation.HISTORICAL_CONFIGURATION_UNVERIFIED)
    }

    @Test
    fun `token usage explicitly reported as zero stays zero`() {
        val result = reconstruct(attempts = listOf(attempt("inv-1", 0, tokens = true, zeroTokens = true)))

        val usage = result.timeline.usage!!
        assertThat(usage.inputTokens).isZero()
        assertThat(usage.outputTokens).isZero()
        assertThat(usage.attemptsWithoutUsage).isZero()
        assertThat(result.limitations).doesNotContain(ReconstructionLimitation.USAGE_UNKNOWN)
    }

    @Test
    fun `an incomplete component total stays unknown`() {
        val result =
            reconstruct(
                attempts =
                    listOf(
                        attempt("inv-1", 0, tokens = true),
                        attempt("inv-1", 1, tokens = false),
                    ),
            )

        val usage = result.timeline.usage!!
        // One attempt reported nothing, so no component total is a run total.
        assertThat(usage.attemptsCounted).isEqualTo(2)
        assertThat(usage.attemptsWithoutUsage).isEqualTo(1)
        assertThat(usage.inputTokens).isNull()
        assertThat(usage.outputTokens).isNull()
        assertThat(usage.thinkingTokens).isNull()
    }

    @Test
    fun `finish reason provenance is never fabricated`() {
        val result = reconstruct(attempts = listOf(attempt("inv-1", 0, tokens = true)))

        val observation = result.timeline.attempts().single()
        assertThat(observation.finishReasonReported).isFalse()
        assertThat(observation.finishReason).isEqualTo("STOP")
        assertThat(observation.provenance).isEqualTo(AttemptProvenance.OBSERVED_IN_PROCESS)
    }

    @Test
    fun `retries and repeated invocations stay distinct observations`() {
        val result =
            reconstruct(
                attempts =
                    listOf(
                        attempt("inv-a", 0, tokens = true),
                        attempt("inv-a", 1, tokens = true),
                        attempt("inv-b", 0, tokens = true),
                    ),
            )

        assertThat(result.timeline.invocations.map { it.invocationId }).containsExactly("inv-a", "inv-b")
        assertThat(
            result.timeline.invocations
                .first()
                .attempts,
        ).hasSize(2)
        assertThat(result.timeline.attempts()).hasSize(3)
        assertThat(result.timeline.usage!!.inputTokens).isEqualTo(30L)
    }

    @Test
    fun `the shipped read path performs no writes and invokes nothing external`() {
        val store = WriteTrappingStore(observations = listOf(attempt("inv-1", 0, tokens = true)))
        val governance = CountingGovernanceQuery(GovernanceQueryResult.Found(view()))

        val result =
            EvidenceBackedReconstructor(
                SemanticTimelineQuery.of(store, governance),
                FixedSource(recorded()),
            ).reconstruct(RUN, GovernanceQueryRequest("wl", "env", "dep", RUN))

        assertThat(result.status).isEqualTo(ReconstructionStatus.SUPPORTED)
        // The trap throws on any write, so reaching this line already proves none was attempted.
        assertThat(store.appends.get()).isZero()
        // Reads happened, and only reads.
        assertThat(store.runReads.get()).isEqualTo(1)
        assertThat(governance.queries.get()).isEqualTo(1)
    }

    private fun reconstruct(
        store: AttemptObservationStore =
            InMemoryAttemptObservationStore().apply { observations().forEach { append(it) } },
        scope: GovernanceQueryRequest? = GovernanceQueryRequest("wl", "env", "dep", RUN),
        attempts: List<ModelAttemptObservation>? = null,
        decisions: List<GovernanceDecisionFact>? = null,
    ): ReconstructionResult {
        val resolvedStore =
            if (attempts == null) {
                store
            } else {
                InMemoryAttemptObservationStore().apply { attempts.forEach { append(it) } }
            }
        val governance = CountingGovernanceQuery(GovernanceQueryResult.Found(view(decisions ?: listOf(decisionFact()))))
        val reconstructor =
            EvidenceBackedReconstructor(
                SemanticTimelineQuery.of(resolvedStore, governance),
                FixedSource(recorded()),
            )
        return reconstructor.reconstruct(RUN, scope)
    }

    private fun observations(): List<ModelAttemptObservation> = listOf(attempt("inv-1", 0, tokens = true))

    private class WriteTrappingStore(
        private val observations: List<ModelAttemptObservation>,
    ) : AttemptObservationStore {
        val appends = AtomicInteger()
        val runReads = AtomicInteger()

        override fun append(observation: ModelAttemptObservation): AttemptAppendOutcome {
            appends.incrementAndGet()
            error("a reconstruction must never write to the attempt store")
        }

        override fun findByWorkflowRun(workflowRunId: String): AttemptHistory {
            runReads.incrementAndGet()
            return AttemptHistory.Available(observations)
        }

        override fun findByInvocation(invocationId: String): AttemptHistory =
            AttemptHistory.Available(observations.filter { it.invocationId == invocationId })
    }

    private class CountingGovernanceQuery(
        private val result: GovernanceQueryResult,
    ) : GovernanceQueryService {
        val queries = AtomicInteger()

        override fun query(request: GovernanceQueryRequest): GovernanceQueryResult {
            queries.incrementAndGet()
            return result
        }
    }

    private class FixedSource(
        val reference: HistoricalConfigurationReference,
    ) : HistoricalConfigurationSource {
        override fun referenceFor(workflowRunId: String): HistoricalConfigurationReference = reference
    }

    private fun recorded(): HistoricalConfigurationReference.Recorded =
        HistoricalConfigurationReference.Recorded(policyVersion = "policy-7")

    private fun attempt(
        invocationId: String,
        attempt: Int,
        tokens: Boolean,
        zeroTokens: Boolean = false,
    ) = ModelAttemptObservation(
        invocationId = invocationId,
        attempt = attempt,
        workflowRunId = RUN,
        correlationId = "corr-1",
        routeIndex = 0,
        providerId = "openai",
        requestedModel = "gpt-x",
        reportedModel = "gpt-x-2026",
        startedAt = Instant.parse("2026-10-21T10:00:00Z").plusSeconds(attempt.toLong()),
        completedAt = Instant.parse("2026-10-21T10:00:01Z").plusSeconds(attempt.toLong()),
        durationMillis = 1_000,
        outcome = AttemptOutcome.SUCCEEDED,
        failureCategory = null,
        finishReason = "STOP",
        finishReasonReported = false,
        inputTokens =
            if (tokens) {
                if (zeroTokens) {
                    0
                } else {
                    10
                }
            } else {
                null
            },
        outputTokens =
            if (tokens) {
                if (zeroTokens) {
                    0
                } else {
                    5
                }
            } else {
                null
            },
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
