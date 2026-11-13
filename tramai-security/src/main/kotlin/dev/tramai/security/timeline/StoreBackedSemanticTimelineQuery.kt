package dev.tramai.security.timeline

import dev.tramai.core.observation.attempt.AttemptHistory
import dev.tramai.core.observation.attempt.AttemptObservationStore
import dev.tramai.core.observation.attempt.ModelAttemptObservation
import dev.tramai.security.governance.query.GovernanceQueryService

/**
 * Projects stored evidence into a [SemanticTimeline] without adding evidence of its own.
 *
 * Both sources are read through their existing ports, in one pass, with no caching and no writes:
 * the query cannot influence the run it describes. Ordering is derived only from recorded fields
 * (`startedAt`, `attempt`, `invocationId`), so two queries over the same rows return the same
 * timeline, and no order is inferred from anything the stores do not state.
 */
internal class StoreBackedSemanticTimelineQuery(
    private val attempts: AttemptObservationStore,
    private val governance: GovernanceQueryService?,
) : SemanticTimelineQuery {
    override fun timeline(request: TimelineRequest): SemanticTimeline {
        val history = attempts.findByWorkflowRun(request.workflowRunId)
        val recorded = history.recordedAttempts()
        // A store may hand back the same (invocationId, attempt) twice; usage must not be inflated
        // by that, and neither may the invocation be listed twice.
        val distinct = recorded.distinctBy { it.invocationId to it.attempt }
        val governanceSection = governanceSection(request)
        return SemanticTimeline(
            workflowRunId = request.workflowRunId,
            history = history.state(),
            invocations = invocationsOf(distinct),
            usage = usageOf(recorded, distinct),
            governance = governanceSection,
            // With nothing observed there is no second side to correlate, so no link is claimed.
            correlation =
                if (distinct.isEmpty()) {
                    CorrelationEvidence.NONE
                } else {
                    correlationOf(request, governanceSection)
                },
        )
    }

    private fun governanceSection(request: TimelineRequest): GovernanceSection =
        when (val scope = request.governanceScope) {
            null -> GovernanceSection.NotQueried
            else -> governance?.query(scope)?.toGovernanceSection() ?: GovernanceSection.NotConfigured
        }

    private fun correlationOf(
        request: TimelineRequest,
        section: GovernanceSection,
    ): CorrelationEvidence {
        val view = (section as? GovernanceSection.Snapshot)?.view ?: return CorrelationEvidence.NONE
        val snapshotRunId = view.identity.workflowRunId
        return if (snapshotRunId != null && snapshotRunId == request.workflowRunId) {
            CorrelationEvidence.SAME_WORKFLOW_RUN
        } else {
            CorrelationEvidence.NONE
        }
    }
}

internal fun AttemptHistory.recordedAttempts(): List<ModelAttemptObservation> =
    when (this) {
        is AttemptHistory.Available -> attempts
        is AttemptHistory.Uninstrumented -> emptyList()
    }

internal fun invocationsOf(attempts: List<ModelAttemptObservation>): List<InvocationTimeline> =
    attempts
        .groupBy { it.invocationId }
        .map { (invocationId, group) ->
            val ordered = group.sortedWith(compareBy({ it.startedAt }, { it.attempt }))
            InvocationTimeline(invocationId, agreedCorrelationId(ordered), ordered)
        }.sortedWith(compareBy({ it.attempts.first().startedAt }, { it.invocationId }))

/**
 * One correlation id, or `null` when the attempts do not all carry the same one.
 *
 * `null` values are kept in the comparison: an attempt with an id beside an attempt without one is
 * mixed evidence, and picking the id would assert an association the records do not support.
 */
internal fun agreedCorrelationId(attempts: List<ModelAttemptObservation>): String? =
    attempts.map { it.correlationId }.distinct().singleOrNull()

/**
 * Run-level usage over distinct attempts. A component no attempt reported stays `null`: unknown is
 * not zero, and thinking tokens are never folded into the output total.
 */
internal fun usageOf(
    recorded: List<ModelAttemptObservation>,
    distinct: List<ModelAttemptObservation>,
): ObservedUsage? {
    if (distinct.isEmpty()) return null
    return ObservedUsage(
        attemptsCounted = distinct.size,
        duplicatesIgnored = recorded.size - distinct.size,
        attemptsWithoutUsage = distinct.count(::reportedNoUsage),
        inputTokens = distinct.completeSumOrNull { it.inputTokens },
        outputTokens = distinct.completeSumOrNull { it.outputTokens },
        thinkingTokens = distinct.completeSumOrNull { it.thinkingTokens },
    )
}

/** True when an attempt reported no token count at all: unknown, never zero. */
private fun reportedNoUsage(attempt: ModelAttemptObservation): Boolean =
    attempt.inputTokens == null && attempt.outputTokens == null && attempt.thinkingTokens == null

/**
 * A run total only when every counted attempt reported this component; otherwise `null`.
 *
 * Summing the attempts that happened to report would produce a number that reads as the run's total
 * while silently omitting the attempts that reported nothing. A partial sum is not a total, and an
 * invented total is the failure this record type exists to prevent.
 */
private fun List<ModelAttemptObservation>.completeSumOrNull(select: (ModelAttemptObservation) -> Int?): Long? {
    val values = mapNotNull(select)
    return values.takeIf { it.size == size }?.sumOf { it.toLong() }
}
