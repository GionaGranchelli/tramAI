package dev.tramai.security.governance
import dev.tramai.controlplane.QueryConsistency

/**
 * 0.7.4c1 — where a projected fact's authority, its claim boundary and its completeness come from.
 *
 * Authority is assigned here, from the fact's category, and never taken from the source: a producer
 * cannot declare its own record authoritative by supplying one.
 */
internal object GovernanceFactClassification {
    /**
     * The freshness a read may claim, from the consistency the port reported and the watermark it
     * can name.
     *
     * The table is here, in one place, because inverting it is a silent authority violation rather
     * than a visible failure: an authoritative read labelled as lagging understates it, while a
     * derived read labelled current claims an authority the source never had. A projection whose
     * watermark cannot be named is neither: it reports freshness as unestablished.
     */
    fun freshnessFor(
        consistency: QueryConsistency,
        watermark: Long?,
    ): FactFreshness =
        when {
            consistency == QueryConsistency.AUTHORITATIVE -> FactFreshness.CURRENT_AT_VERSION
            watermark != null -> FactFreshness.EVENTUAL_KNOWN_LAG
            else -> FactFreshness.STALE_OR_UNKNOWN
        }

    fun authorityFor(kind: DecisionKind): FactAuthority =
        when (kind) {
            DecisionKind.POLICY_DECISION, DecisionKind.TOOL_PERMISSION -> FactAuthority.POLICY_AUTHORIZATION_RECORD
            DecisionKind.PROVIDER_ROUTE -> FactAuthority.PRODUCER_DECLARED
        }

    /**
     * Why a family reports nothing.
     *
     * An empty read is [FactAuthority.NOT_OBSERVED]: it says the read found nothing, not that nothing
     * was ever produced. Emission of these records is optional in this repository, so inferring a
     * missing producer from a missing record would manufacture authority from an observation gap.
     * [FactAuthority.NOT_PRODUCED] is reserved for a family where the absence of a producer is itself
     * established, which is the frozen G4 position for `provider.route`.
     */
    fun absenceAuthorityFor(kind: DecisionKind): FactAuthority =
        when (kind) {
            DecisionKind.PROVIDER_ROUTE -> FactAuthority.NOT_PRODUCED
            DecisionKind.POLICY_DECISION, DecisionKind.TOOL_PERMISSION -> FactAuthority.NOT_OBSERVED
        }

    /**
     * What the read may claim about the decision records it returned, given what it verified.
     *
     * This is not the raw-stream question. The verified range says which audit positions were read and
     * anchored; this says whether the records the read recognised account for the positions that were
     * supposed to produce one. A legitimate non-decision event is not a hole, and an event nobody
     * could classify is not assumed irrelevant — it is the difference between reporting a gap and
     * reporting that the source does not know.
     *
     * A record without an ordering basis, or a record outside the verified range, breaks the claim the
     * same way: the records are the observable half of it, so they are checked rather than trusted.
     */
    fun completenessOf(
        records: List<RecordedDecision>,
        coverage: StreamCoverage,
    ): Completeness {
        val range = coverage.verifiedRange ?: return Completeness.UNKNOWN
        val positions = records.mapNotNull { it.sourceSequence }
        val span = range.last - range.first + 1
        return when {
            coverage.unclassifiedPositions.isNotEmpty() -> {
                Completeness.UNKNOWN
            }

            positions.size != records.size -> {
                Completeness.UNKNOWN
            }

            positions.any { it !in range } -> {
                Completeness.UNKNOWN
            }

            coverage.unrecordedDecisionPositions.isNotEmpty() -> {
                Completeness.GAPS_PRESENT
            }

            // An empty record set completes nothing: the raw range still tells the caller the stream
            // was read, while a record claim with no records would say only that none was required.
            records.isEmpty() -> {
                Completeness.UNKNOWN
            }

            // The partition is the proof: records plus the recognised non-decisions the source counted
            // account for every verified position. Without it the completion would rest on the buckets
            // being exhaustive, which is exactly the silent drop this contract exists to prevent.
            coverage.accountedPositions == span -> {
                Completeness.COMPLETE
            }

            else -> {
                Completeness.UNKNOWN
            }
        }
    }

    /** What may not be inferred from a fact of this family. */
    fun limitationsFor(kind: DecisionKind): List<String> =
        when (kind) {
            DecisionKind.POLICY_DECISION, DecisionKind.TOOL_PERMISSION -> {
                listOf(
                    AUTHORIZATION_LIMIT,
                    "the absence of a completion fact is not evidence of either success or failure",
                )
            }

            DecisionKind.PROVIDER_ROUTE -> {
                listOf(
                    "provider.route has no live production producer; these records are source-declared",
                    "the absence of routing records is not evidence that no routing, fallback or " +
                        "blocked decision occurred",
                )
            }
        }

    /** What may not be inferred when a family reports nothing at all. */
    fun absenceLimitationsFor(kind: DecisionKind): List<String> =
        when (kind) {
            DecisionKind.PROVIDER_ROUTE -> {
                listOf(
                    "the absence of routing records is not evidence that no routing, fallback or " +
                        "blocked decision occurred",
                )
            }

            DecisionKind.POLICY_DECISION, DecisionKind.TOOL_PERMISSION -> {
                listOf(
                    "an empty read does not establish that no decision was produced; emission is " +
                        "optional and the read's coverage may be partial",
                )
            }
        }

    const val AUTHORIZATION_LIMIT =
        "a recorded allow decision attests authorization only: it is not evidence that the operation " +
            "ran, succeeded, or changed external state"

    /** Travels with any fact that names a boundary, so the claim and its limit stay together. */
    const val BOUNDARY_ORIGIN_LIMIT =
        "a registered boundary identifier and its registered strength do not prove this event " +
            "originated from that boundary"

    /** What may not be inferred from a producer-declared identity. */
    val DECLARED_IDENTITY_LIMITS: List<String> =
        listOf(
            "the declared identity is not authenticated against the workload registration authority (T1)",
            "a syntactically valid or digest-bound identity tuple does not establish workload origin",
            "a declared boundary's registered strength does not prove this event originated here",
        )

    /** What may not be inferred from a telemetry observation. */
    val TELEMETRY_LIMITS: List<String> =
        listOf(
            "telemetry is an observation, not a governance fact",
            "a telemetry report of a selected route is not routing evidence",
            "a telemetry report of a completed call is not completion evidence",
        )
}
