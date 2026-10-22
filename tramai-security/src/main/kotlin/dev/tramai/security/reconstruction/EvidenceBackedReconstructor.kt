package dev.tramai.security.reconstruction

import dev.tramai.security.governance.query.GovernanceQueryRequest
import dev.tramai.security.timeline.SemanticTimeline
import dev.tramai.security.timeline.SemanticTimelineQuery
import dev.tramai.security.timeline.TimelineRequest

/**
 * Where a reconstruction gets the historical configuration/policy identity of a run.
 *
 * An implementation reads **persisted historical evidence** for [workflowRunId] — the records the run
 * itself produced — and reports what it found. It must never report the configuration in force now:
 * an implementation backed by current configuration defeats the contract it feeds, because
 * [HistoricalConfigurationReference.Recorded] validates shape only and cannot detect the difference.
 *
 * Returning [HistoricalConfigurationReference.NotRecorded] is always correct when the evidence is not
 * there. It is reported as a limitation, while a substituted value would silently become a false
 * historical fact.
 *
 * **This interface cannot enforce that.** It declares a lookup and promises nothing about how an
 * implementation answers: a read-only source and a source that reads current configuration are the
 * same type, and a `Recorded` answer carries no provenance. Every `Recorded` value therefore produces
 * [ReconstructionLimitation.HISTORICAL_CONFIGURATION_UNVERIFIED], and
 * [ReconstructionStatus.SUPPORTED] must not be read as authentication. Better-behaved implementations
 * reduce the doubt; nothing here removes it.
 */
public fun interface HistoricalConfigurationSource {
    /** The configuration/policy identity the persisted evidence holds for [workflowRunId]. */
    public fun referenceFor(workflowRunId: String): HistoricalConfigurationReference
}

/**
 * Reads the stored evidence for a run and classifies it (Epic 0.7.5e).
 *
 * Side-effect-free by construction: its only collaborators are a read-only [SemanticTimelineQuery]
 * and a [HistoricalConfigurationSource], so it cannot invoke a provider, tool, approval or any other
 * external action — there is no collaborator through which such a call could be made. It adds no
 * persistence and writes nothing.
 *
 * It also builds the [ReconstructionRequest] itself, taking the historical reference from
 * [HistoricalConfigurationSource] instead of from its caller. That is the trust boundary from 0.7.5d
 * made operational: a caller cannot hand in a reference taken from current configuration and have it
 * treated as history.
 */
public class EvidenceBackedReconstructor(
    private val timelineQuery: SemanticTimelineQuery,
    private val configurationSource: HistoricalConfigurationSource,
) {
    /**
     * Reconstructs the available history for [workflowRunId].
     *
     * @param governanceScope optional governance scope, as in [TimelineRequest]; `null` means
     *   governance was not asked about, never that it recorded nothing.
     * @throws IllegalArgumentException if the governance scope names a different run.
     * @throws IllegalStateException if a backing store fails; the message is sanitized by the query
     *   exactly as before, and the failure is propagated rather than converted into "no evidence".
     */
    public fun reconstruct(
        workflowRunId: String,
        governanceScope: GovernanceQueryRequest? = null,
    ): ReconstructionResult {
        val request =
            ReconstructionRequest(
                timeline = TimelineRequest(workflowRunId, governanceScope),
                historicalConfiguration = configurationSource.referenceFor(workflowRunId),
            )
        val timeline: SemanticTimeline = timelineQuery.timeline(request.timeline)
        return ReconstructionResult.of(request, timeline)
    }
}
