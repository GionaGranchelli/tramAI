package dev.tramai.controlplane

/**
 * 0.7.4c1 — where a projected fact's authority and its claim boundary come from.
 *
 * Authority is assigned here, from the fact's category, and never taken from a source's own claim:
 * a source that returns routing records still gets a source-declared routing fact. Keeping the
 * tables in one place is what makes "a projection may not manufacture authority" checkable instead
 * of a matter of which call site was edited last.
 */
internal object GovernanceFactClassification {
    /**
     * The authority a category's records can carry.
     *
     * A recorded policy or tool decision is an authorization record, never authoritative runtime
     * state, and a routing record has no engine producer, so it is source-declared at best.
     */
    fun authorityFor(kind: DecisionKind): FactAuthority =
        when (kind) {
            DecisionKind.POLICY_DECISION, DecisionKind.TOOL_PERMISSION -> FactAuthority.POLICY_AUTHORIZATION_RECORD
            DecisionKind.PROVIDER_ROUTE -> FactAuthority.PRODUCER_DECLARED
        }

    /** What a consumer must not infer from a fact of this category. */
    fun limitationsFor(kind: DecisionKind): List<String> =
        when (kind) {
            DecisionKind.PROVIDER_ROUTE -> {
                listOf(
                    "provider.route has no live production producer: these records are source-declared",
                    "the absence of routing records is not evidence that no routing, fallback or blocked " +
                        "decision occurred",
                )
            }

            DecisionKind.POLICY_DECISION, DecisionKind.TOOL_PERMISSION -> {
                listOf(
                    AUTHORIZATION_LIMIT,
                    "the absence of a completion fact is not evidence of success or of failure",
                )
            }
        }

    val declaredIdentityLimits: List<String> =
        listOf(
            "the declared identity is not authenticated against the workload registration authority (T1, open)",
            "a syntactically valid or digest-bound tuple does not establish workload origin",
            "a declared boundary's registered strength does not prove this event originated here",
        )

    val telemetryLimits: List<String> =
        listOf(
            "telemetry is an observation, not a governance fact",
            "a telemetry report of a selected route is not routing evidence",
            "a telemetry report of a completed call is not completion evidence",
        )

    /**
     * Whether the source's own positions are contiguous, so completeness may be claimed.
     *
     * Computed from the span of distinct positions rather than by enumerating a range: enumeration is
     * O(max - min) and throws for a range wider than `Int.MAX_VALUE`, which a large gap between a
     * first and last position would trigger on a governance read path.
     */
    fun completenessOf(sequences: List<Long>): Completeness {
        val positions = sequences.toSet()
        val span = positions.max() - positions.min()
        return if (span == (positions.size - 1).toLong()) Completeness.COMPLETE else Completeness.GAPS_PRESENT
    }

    /** The source positions when every record carries one, or null when the source has no basis. */
    fun sequencesOf(records: List<RecordedDecision>): List<Long>? {
        val sequences = records.mapNotNull { it.sourceSequence }
        return sequences.takeIf { it.size == records.size }
    }

    /** Travels with any fact that names a boundary, so the claim and its limit stay together. */
    const val BOUNDARY_ORIGIN_LIMIT =
        "a registered boundary identifier and its registered strength do not prove this event originated here"

    private const val AUTHORIZATION_LIMIT =
        "an authorization record attests the decision, never that the operation ran, succeeded or changed state"
}
