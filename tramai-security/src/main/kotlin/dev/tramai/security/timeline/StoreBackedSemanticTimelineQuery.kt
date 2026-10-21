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
            correlation = correlationOf(request, governanceSection),
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

/** One correlation id, or `null` when none was recorded or the attempts recorded different ones. */
internal fun agreedCorrelationId(attempts: List<ModelAttemptObservation>): String? =
    attempts.mapNotNull { it.correlationId }.distinct().singleOrNull()

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
        inputTokens = distinct.sumOrNull { it.inputTokens },
        outputTokens = distinct.sumOrNull { it.outputTokens },
        thinkingTokens = distinct.sumOrNull { it.thinkingTokens },
    )
}

/** True when an attempt reported no token count at all: unknown, never zero. */
private fun reportedNoUsage(attempt: ModelAttemptObservation): Boolean =
    attempt.inputTokens == null && attempt.outputTokens == null && attempt.thinkingTokens == null

/** Sums the reported values, or `null` when none was reported — never `0` for "unreported". */
private fun List<ModelAttemptObservation>.sumOrNull(select: (ModelAttemptObservation) -> Int?): Long? =
    mapNotNull(select).takeIf { it.isNotEmpty() }?.sumOf { it.toLong() }
