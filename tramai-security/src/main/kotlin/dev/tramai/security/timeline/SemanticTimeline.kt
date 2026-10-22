package dev.tramai.security.timeline

import dev.tramai.core.observation.attempt.AttemptHistory
import dev.tramai.core.observation.attempt.AttemptObservationStore
import dev.tramai.core.observation.attempt.AttemptOutcome
import dev.tramai.core.observation.attempt.ModelAttemptObservation
import dev.tramai.security.governance.query.DecisionKind
import dev.tramai.security.governance.query.GovernanceProjectionView
import dev.tramai.security.governance.query.GovernanceQueryRequest
import dev.tramai.security.governance.query.GovernanceQueryResult
import dev.tramai.security.governance.query.GovernanceQueryService
import dev.tramai.security.governance.query.requireSafeIdentifier
import java.time.Instant

/**
 * Read-only semantic timeline over two existing, independently owned evidence sources (Epic 0.7.5c).
 *
 * The timeline answers "what did governance record, and what model attempts were observed, for this
 * run". It reads:
 *
 * - the materialized governance projection through [GovernanceQueryService] (Epic 0.7.4), and
 * - recorded model attempts through [AttemptObservationStore] (Epic 0.7.5b).
 *
 * It is a *projection of stored evidence*, not a reconstruction: it holds no event log, invents no
 * causal order, and cannot claim that a specific governance decision caused a specific attempt. The
 * only association it reports is the identity both sides actually carry, described by
 * [SemanticTimeline.correlation]; when that identity is absent the association stays
 * [CorrelationEvidence.NONE] rather than being inferred from timing or ordering.
 *
 * Every record is content-free: prompts, responses, tool payloads and reasoning are not expressible
 * in these types, so no redaction step can be forgotten. Querying is a pure read — it performs no
 * provider, tool or network call, and never writes to either store.
 */
public data class TimelineRequest
    @JvmOverloads
    constructor(
        /** The governed run the timeline addresses. Attempts are read for exactly this run. */
        public val workflowRunId: String,
        /**
         * Optional governance scope to read governance facts for. `null` means governance was not
         * asked about ([GovernanceSection.NotQueried]), never that governance recorded nothing.
         */
        public val governanceScope: GovernanceQueryRequest? = null,
    ) {
        init {
            requireSafeIdentifier(workflowRunId, "workflowRunId")
            // A governance scope naming another run would place that run's governance facts next to
            // these attempts, which is exactly the association this contract refuses to invent.
            require(governanceScope == null || governanceScope.workflowRunId == workflowRunId) {
                "timeline-governance-scope-run-mismatch"
            }
        }
    }

/** What the attempt store could establish, which is not the same question as "were there attempts". */
public enum class AttemptHistoryState {
    /** Recording is configured and observations were found. */
    RECORDED,

    /** Recording is configured and nothing was recorded for this run. Evidence of absence. */
    EMPTY,

    /** No observation persistence is configured: history cannot be established either way. */
    UNINSTRUMENTED,
}

/**
 * The only association this contract reports between governance facts and observed attempts.
 *
 * There is deliberately no variant that names a decision-to-attempt edge: the stored evidence does
 * not carry one, so the timeline does not express one.
 */
public enum class CorrelationEvidence {
    /**
     * The governance snapshot answers for the same `workflowRunId` these attempts were recorded under,
     * so both sections belong to one run. This still does not associate a decision with an attempt.
     */
    SAME_WORKFLOW_RUN,

    /**
     * Unresolved: nothing in the stored evidence links the governance section to these attempts. The
     * two sections are returned side by side and must not be read as one causal sequence.
     */
    NONE,
}

/**
 * The governance evidence behind a timeline: a governed snapshot, or the reason it is unavailable.
 *
 * A [Snapshot] is a materialized *state*, not a historical event log. It reports the decisions the
 * projection recognized within a verified audit range and never claims that an unrecognized or
 * absent decision did not occur.
 */
public sealed interface GovernanceSection {
    /** The caller asked for no governance scope, so governance was not consulted. */
    public data object NotQueried : GovernanceSection

    /** A governance scope was asked for, but no governance query is configured in this process. */
    public data object NotConfigured : GovernanceSection

    /** No projection has been materialized for the requested scope. Not evidence that nothing happened. */
    public data object NotMaterialized : GovernanceSection

    /** A projection exists but its stored format is not supported by this build. */
    public data class UnsupportedFormat(
        public val reason: String,
    ) : GovernanceSection

    /** A projection exists but violates its contract and is refused rather than partially returned. */
    public data class InvalidStoredProjection(
        public val reason: String,
    ) : GovernanceSection

    /** A verified materialized projection: the governance facts recorded for this run. */
    public data class Snapshot(
        public val view: GovernanceProjectionView,
    ) : GovernanceSection {
        /** The decision kinds the projection recorded, in projection order. */
        public val decisionKinds: List<DecisionKind> get() = view.decisions.map { it.kind }
    }
}

/**
 * One invocation's observed attempts, in the order they were recorded.
 *
 * Invocations are kept apart: a retry is another attempt of the same invocation, and a repeated
 * service call is a different invocation with its own identity. Collapsing the two would lose the
 * distinction the attempt store exists to preserve.
 */
public data class InvocationTimeline(
    public val invocationId: String,
    /**
     * The correlation id the attempts of this invocation carry, or `null` when none was recorded or
     * when the attempts do not all carry the same one — including the case where one carries an id
     * and another carries none. Mixed evidence is reported as `null`, never resolved by picking.
     */
    public val correlationId: String?,
    /** Attempts ordered by `(startedAt, attempt)`. */
    public val attempts: List<ModelAttemptObservation>,
) {
    init {
        require(invocationId.isNotBlank()) { "timeline-invocation-blank" }
        require(attempts.isNotEmpty()) { "timeline-invocation-without-attempts: $invocationId" }
        require(attempts.all { it.invocationId == invocationId }) {
            "timeline-invocation-attempt-mismatch: $invocationId"
        }
    }

    /** The terminal outcome of the last recorded attempt; the others were superseded by it. */
    public val finalOutcome: AttemptOutcome get() = attempts.last().outcome
}

/**
 * Run-level usage observed across the attempts in this timeline.
 *
 * `null` components are unknown, never zero: a provider that reported no token count is not a
 * provider that used none. A component is reported only when **every** counted attempt reported it —
 * summing the attempts that happened to report would read as a run total while omitting the rest, so
 * [attemptsWithoutUsage] is the count to read when a component is `null`. Thinking tokens are their
 * own component and are never added into [outputTokens], because providers that fold reasoning into
 * the completion count would then be counted twice.
 */
public data class ObservedUsage(
    /** Distinct `(invocationId, attempt)` observations counted. */
    public val attemptsCounted: Int,
    /** Observations dropped because the same `(invocationId, attempt)` was returned more than once. */
    public val duplicatesIgnored: Int,
    /** Attempts that reported no token counts at all. */
    public val attemptsWithoutUsage: Int,
    public val inputTokens: Long?,
    public val outputTokens: Long?,
    public val thinkingTokens: Long?,
) {
    init {
        require(attemptsCounted >= 0 && duplicatesIgnored >= 0 && attemptsWithoutUsage >= 0) {
            "usage counts cannot be negative"
        }
        require(attemptsWithoutUsage <= attemptsCounted) { "more attempts without usage than attempts counted" }
        require(listOfNotNull(inputTokens, outputTokens, thinkingTokens).all { it >= 0L }) {
            "usage totals cannot be negative"
        }
    }
}

/**
 * The semantic timeline for one governed run.
 *
 * Sections are independent findings, not a sequence: [governance] is a snapshot of recorded
 * governance facts, [invocations] are observed model attempts, and [correlation] says only what
 * identity the two actually share.
 */
public data class SemanticTimeline(
    public val workflowRunId: String,
    public val history: AttemptHistoryState,
    public val invocations: List<InvocationTimeline>,
    /** Run-level usage, or `null` when no attempt recorded any token count. Zero is not used as "unknown". */
    public val usage: ObservedUsage?,
    public val governance: GovernanceSection,
    public val correlation: CorrelationEvidence,
) {
    init {
        require(workflowRunId.isNotBlank()) { "timeline-blank-workflow-run-id" }
        require(invocations.map { it.invocationId }.distinct().size == invocations.size) {
            "each invocation may appear at most once in a timeline"
        }
    }

    /** Attempts across every invocation, ordered by `(startedAt, invocationId, attempt)`. */
    public fun attempts(): List<ModelAttemptObservation> =
        invocations
            .flatMap { it.attempts }
            .sortedWith(compareBy({ it.startedAt }, { it.invocationId }, { it.attempt }))

    /** The earliest observed attempt start, or `null` when no attempt was recorded. */
    public val firstAttemptAt: Instant? get() = attempts().firstOrNull()?.startedAt
}

/**
 * Headless read API for [SemanticTimeline] (Epic 0.7.5c).
 *
 * Blocking by design, matching the governance query it composes: a Java consumer needs no coroutine
 * machinery, and a suspending variant is deliberately not part of this slice.
 */
public fun interface SemanticTimelineQuery {
    /**
     * Projects the stored evidence for [request] into a timeline.
     *
     * @throws IllegalStateException if a backing store fails unexpectedly; the message is sanitized
     *   exactly as the governance query sanitizes its own infrastructure failures.
     */
    public fun timeline(request: TimelineRequest): SemanticTimeline

    public companion object {
        /**
         * Creates a read-only query over [attempts] and an optional [governance] query.
         *
         * When [governance] is `null`, a request that asks for a governance scope reports
         * [GovernanceSection.NotConfigured] instead of silently reporting "not materialized".
         */
        @JvmStatic
        @JvmOverloads
        public fun of(
            attempts: AttemptObservationStore,
            governance: GovernanceQueryService? = null,
        ): SemanticTimelineQuery = StoreBackedSemanticTimelineQuery(attempts, governance)
    }
}

/** Maps a governance query result onto the section it belongs in; carried here so both stay in step. */
internal fun GovernanceQueryResult.toGovernanceSection(): GovernanceSection =
    when (this) {
        is GovernanceQueryResult.Found -> GovernanceSection.Snapshot(view)
        is GovernanceQueryResult.NotMaterialized -> GovernanceSection.NotMaterialized
        is GovernanceQueryResult.UnsupportedFormat -> GovernanceSection.UnsupportedFormat(reason)
        is GovernanceQueryResult.InvalidStoredProjection -> GovernanceSection.InvalidStoredProjection(reason)
    }

/** History state as the store reports it; an empty [AttemptHistory.Available] is real evidence. */
internal fun AttemptHistory.state(): AttemptHistoryState =
    when (this) {
        is AttemptHistory.Uninstrumented -> {
            AttemptHistoryState.UNINSTRUMENTED
        }

        is AttemptHistory.Available -> {
            if (attempts.isEmpty()) AttemptHistoryState.EMPTY else AttemptHistoryState.RECORDED
        }
    }
