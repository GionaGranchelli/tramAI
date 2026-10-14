package dev.tramai.controlplane

/**
 * 0.7.4c1 — where a projected fact's authority, its claim boundary and its completeness come from.
 *
 * Authority is assigned here, from the fact's category, and never taken from the source: a producer
 * cannot declare its own record authoritative by supplying one.
 */
internal object GovernanceFactClassification {
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
     * Whether the projection may claim it covered everything it reports.
     *
     * Rests on the source-attested range, never on the observed positions alone: a single observed
     * position is contiguous with itself, and a contiguous window says nothing about the positions
     * outside it. Positions are compared as a set so that two records sharing a position are not
     * misread as a hole, and contiguity is a span comparison rather than a range walk, which is
     * O(max - min) and throws for a range wider than `Int.MAX_VALUE`.
     */
    fun completenessOf(
        records: List<RecordedDecision>,
        coverage: LongRange?,
    ): Completeness {
        val positions = records.mapNotNull { it.sourceSequence }
        val first = positions.minOrNull()
        val last = positions.maxOrNull()
        val span = if (coverage == null) null else coverage.last - coverage.first + 1
        return when {
            coverage == null || span == null || span <= 0 -> {
                Completeness.UNKNOWN
            }

            positions.size != records.size -> {
                Completeness.UNKNOWN
            }

            first == null || last == null -> {
                Completeness.UNKNOWN
            }

            positions.toSet().size.toLong() == span && first == coverage.first && last == coverage.last -> {
                Completeness.COMPLETE
            }

            else -> {
                Completeness.GAPS_PRESENT
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
