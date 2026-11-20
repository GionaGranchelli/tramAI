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
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gate 2 of Epic 0.7.5g — reading historical evidence must not repeat the actions being investigated.
 *
 * The trap is the store: [WriteTrapStore.append] throws. A reconstruction that completes therefore
 * wrote nothing. Read counts are exact, so a read cannot hide in a retry.
 *
 * Three properties are proven here and are deliberately kept apart, because they are not the same
 * claim:
 *
 * 1. **Types** — what the reconstruction surface can express at all (no provider, tool, approval or
 *    network type appears in it).
 * 2. **Shipped implementation** — `SemanticTimelineQuery.of(...)` plus [EvidenceBackedReconstructor]
 *    over a real store: zero writes, exact reads, stable repeat runs.
 * 3. **External collaborators** — unverifiable by contract, demonstrated by an adversarial
 *    implementation below. That implementation is *not* a defect; it is the reason the guarantee is
 *    scoped to the shipped composition.
 */
class ReconstructionSideEffectTrapTest {
    @Test
    fun `the shipped reconstruction performs zero writes and an exact number of reads`() {
        val store = WriteTrapStore(listOf(attempt(0), attempt(1)))
        val governance = CountingGovernance(GovernanceQueryResult.Found(view()))
        val reconstructor =
            EvidenceBackedReconstructor(SemanticTimelineQuery.of(store, governance), recordedSource())

        val result = reconstructor.reconstruct(RUN, scope())

        assertThat(result.status).isEqualTo(ReconstructionStatus.SUPPORTED)
        assertThat(result.timeline.attempts()).hasSize(2)

        // Zero writes, and the reads are exactly what one projection needs: one run read, no paging.
        assertThat(store.appends.get()).isZero()
        assertThat(store.runReads.get()).isEqualTo(1)
        assertThat(store.invocationReads.get()).isZero()
        // Governance is read exactly once, and only because a scope was supplied.
        assertThat(governance.queries.get()).isEqualTo(1)
    }

    @Test
    fun `repeated reconstruction is stable and records nothing new`() {
        val observations = InMemoryAttemptObservationStore()
        observations.append(attempt(0))
        observations.append(attempt(1))
        val before = observations.all().toList()
        val reconstructor =
            EvidenceBackedReconstructor(
                SemanticTimelineQuery.of(observations, CountingGovernance(GovernanceQueryResult.Found(view()))),
                recordedSource(),
            )

        val runs = (1..5).map { reconstructor.reconstruct(RUN, scope()) }

        // Same status, same limitations, same attempts: a read has no side effect to accumulate.
        assertThat(runs.map { it.status }.distinct()).containsExactly(ReconstructionStatus.SUPPORTED)
        assertThat(runs.map { it.limitations }.distinct()).hasSize(1)
        assertThat(
            runs
                .map { it.timeline.attempts().map { attempt -> attempt.invocationId + attempt.attempt } }
                .distinct(),
        ).hasSize(1)
        // The stored evidence is byte-for-byte what it was: nothing appended, nothing rewritten.
        assertThat(observations.all()).isEqualTo(before)
    }

    @Test
    fun `no provider, tool, approval or network type is reachable from the reconstruction surface`() {
        val reached =
            (listOf(EvidenceBackedReconstructor::class.java) + RECONSTRUCTION_TYPES)
                .flatMap { type ->
                    type.declaredFields.map { it.type } +
                        type.methods.flatMap { it.parameterTypes.toList() }
                }.flatMap { type ->
                    listOf(type, type.componentType, *type.interfaces, type.superclass)
                        .filterNotNull()
                }.map { it.name }
                .distinct()

        assertThat(reached)
            .describedAs("no reconstruction type may reference an execution or network capability")
            .noneMatch { name ->
                name.startsWith("dev.tramai.core.provider.") ||
                    name.startsWith("dev.tramai.core.tool.") ||
                    name.contains("ToolRegistry") ||
                    name.contains("Approval") ||
                    name.startsWith("java.net.") ||
                    name.contains("HttpClient") ||
                    name.contains("ModelProvider")
            }
    }

    @Test
    fun `an adversarial collaborator shows why the interface alone certifies nothing`() {
        val store = CountingStore(listOf(attempt(0)))
        // A deliberately read-impure query: it writes before reading. The interface cannot forbid it,
        // and the reconstructor cannot detect it. This is the documented trust boundary, not a defect.
        val adversarial =
            SemanticTimelineQuery { request ->
                store.append(attempt(9))
                SemanticTimelineQuery.of(store, null).timeline(request)
            }
        val reconstructor = EvidenceBackedReconstructor(adversarial, recordedSource())

        val result = reconstructor.reconstruct(RUN, null)

        // The reconstruction still succeeds and the write really happened: the trap reports it.
        assertThat(result.timeline.attempts()).isNotEmpty()
        assertThat(store.appends.get()).isEqualTo(1)
        // ... which is exactly why no guarantee is claimed about a caller-supplied implementation.
    }

    // ---------------------------------------------------------------- fixtures

    private class WriteTrapStore(
        private val observations: List<ModelAttemptObservation>,
    ) : AttemptObservationStore {
        val appends = AtomicInteger()
        val runReads = AtomicInteger()
        val invocationReads = AtomicInteger()

        override fun append(observation: ModelAttemptObservation): AttemptAppendOutcome {
            appends.incrementAndGet()
            error("reconstruction must never write")
        }

        override fun findByWorkflowRun(workflowRunId: String): AttemptHistory {
            runReads.incrementAndGet()
            return AttemptHistory.Available(observations)
        }

        override fun findByInvocation(invocationId: String): AttemptHistory {
            invocationReads.incrementAndGet()
            return AttemptHistory.Available(observations.filter { it.invocationId == invocationId })
        }
    }

    /** Tolerates writes so the adversarial collaborator can demonstrate one happening. */
    private class CountingStore(
        private val observations: List<ModelAttemptObservation>,
    ) : AttemptObservationStore {
        val appends = AtomicInteger()

        override fun append(observation: ModelAttemptObservation): AttemptAppendOutcome {
            appends.incrementAndGet()
            return AttemptAppendOutcome.RECORDED
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

    private fun recordedSource() =
        HistoricalConfigurationSource {
            HistoricalConfigurationReference.Recorded(policyVersion = "policy-17")
        }

    private fun scope() = GovernanceQueryRequest("wl", "env", "dep", RUN)

    private fun attempt(attempt: Int) =
        ModelAttemptObservation(
            invocationId = "inv-1",
            attempt = attempt,
            workflowRunId = RUN,
            correlationId = "corr-1",
            routeIndex = 0,
            providerId = "primary",
            requestedModel = "logical-model",
            reportedModel = "logical-model",
            startedAt = Instant.parse("2026-10-21T10:00:00Z").plusSeconds(attempt.toLong()),
            completedAt = Instant.parse("2026-10-21T10:00:01Z").plusSeconds(attempt.toLong()),
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
            registration = summary(FactAuthority.AUTHORITATIVE_PERSISTED),
            declaredRunIdentity = summary(FactAuthority.PRODUCER_DECLARED, listOf("authenticated identity")),
            decisions = listOf(decision()),
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
        const val RUN = "run-5g-side-effects"

        /** Every public type in the reconstruction surface. */
        val RECONSTRUCTION_TYPES: List<Class<*>> =
            listOf(
                ReconstructionRequest::class.java,
                ReconstructionResult::class.java,
                HistoricalConfigurationReference::class.java,
                HistoricalConfigurationSource::class.java,
                ReconstructionLimitation::class.java,
                ReconstructionStatus::class.java,
            )
    }
}
