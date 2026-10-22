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
 * Two obligations bind every implementation:
 *
 * 1. **Return [HistoricalConfigurationReference.NotRecorded] only when the lookup completed and
 *    established that no reference was recorded.** If the lookup fails — store unreachable, unreadable,
 *    unparsable — throw. A failure propagates through [EvidenceBackedReconstructor] as a failure; a
 *    swallowed failure reported as `NotRecorded` would read as "the run recorded nothing", which is a
 *    different and false claim.
 * 2. **Never read current configuration as a fallback.** The historical identity is not derivable from
 *    the configuration in force now, and nothing downstream can detect the substitution.
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
 * **What this class does itself:** it builds a [ReconstructionRequest], calls its two collaborators,
 * and classifies what they return. It opens no connection, writes nothing and invokes no provider,
 * tool, approval or workflow — it has no call of its own that could do any of that.
 *
 * **What that does not prove.** Both collaborators are interfaces, so the guarantee is only as strong
 * as the implementations handed in: a `SemanticTimelineQuery` implementation may append to a store or
 * call a provider, and a `HistoricalConfigurationSource` implementation may call anything at all,
 * including reading the configuration currently in force. The shipped read path
 * ([SemanticTimelineQuery.of][SemanticTimelineQuery.Companion.of]) is verified to read and never write
 * (write-trapping tests in `SemanticTimelineQueryTest`, plus the trap in `AdversarialSemanticsTest`),
 * and `tramai-security` has no provider, tool or approval dependency at all — but a caller-supplied
 * implementation is **unverified**, and nothing in this type can inspect it. Side-effect-freedom here
 * is a property of the shipped composition, not of the signature.
 *
 * It also builds the [ReconstructionRequest] itself, taking the historical reference from
 * [HistoricalConfigurationSource] instead of from its caller. That is the trust boundary from 0.7.5d
 * made operational: a caller cannot hand in a reference taken from current configuration and have it
 * treated as history. Whether the source itself respected that is again an implementation property,
 * reported as `HISTORICAL_CONFIGURATION_UNVERIFIED` rather than assumed away.
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
     * @throws RuntimeException any failure raised by a collaborator propagates unchanged — a
     *   `HistoricalConfigurationSource` failure is not sanitized and not converted into
     *   `NotRecorded`, and a `SemanticTimelineQuery` store failure keeps whatever shape that query's
     *   contract gives it (the shipped query sanitizes store failures exactly as before). This class
     *   adds no wrapper, so an implementation's exception reaches the caller as itself.
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
