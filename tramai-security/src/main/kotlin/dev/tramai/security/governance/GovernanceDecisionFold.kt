package dev.tramai.security.governance

/**
 * 0.7.4c1 — folding source records into one projected fact per decision family.
 *
 * Separated from [GovernanceProjectionReader] so each file stays inside the repository's function
 * budget, and because this is where the ordering rules live: dedupe by identity, elect the highest
 * source position regardless of delivery order, never elect one without an ordering basis, and carry
 * completeness through from the read rather than deriving it per family — the audit stream's
 * positions are per stream, so another family's position is not a hole in this family's history.
 */
internal object GovernanceDecisionFold {
    fun project(
        kind: DecisionKind,
        records: List<RecordedDecision>,
        coverage: LongRange?,
        completeness: Completeness,
    ): DecisionProjection {
        val distinct = records.distinct()
        val duplicates = records.size - distinct.size
        return when {
            records.isEmpty() -> {
                absent(kind, coverage, completeness)
            }

            distinct.all { it.sourceSequence != null } -> {
                orderedFold(kind, distinct, duplicates, coverage, completeness)
            }

            else -> {
                unorderedFold(kind, distinct, duplicates, coverage)
            }
        }
    }

    private fun orderedFold(
        kind: DecisionKind,
        distinct: List<RecordedDecision>,
        duplicates: Int,
        coverage: LongRange?,
        completeness: Completeness,
    ): DecisionProjection {
        val latest =
            requireNotNull(distinct.maxByOrNull { it.sourceSequence ?: Long.MIN_VALUE }) {
                "a non-empty fold has a newest record"
            }
        return DecisionProjection(
            kind = kind,
            latest = recordFact(kind, latest, FactFreshness.HISTORICAL),
            completeness = completeness,
            coverage = coverage,
            duplicatesIgnored = duplicates,
            staleIgnored = distinct.size - 1,
        )
    }

    /**
     * No ordering basis: report the fact, and state that the newest could not be determined.
     *
     * The records are historical by nature, but without a position none can be identified as the
     * point it reflects, so none of them may claim historical standing.
     */
    private fun unorderedFold(
        kind: DecisionKind,
        distinct: List<RecordedDecision>,
        duplicates: Int,
        coverage: LongRange?,
    ): DecisionProjection =
        DecisionProjection(
            kind = kind,
            latest =
                recordFact(kind, distinct.first(), FactFreshness.STALE_OR_UNKNOWN).copy(
                    established =
                        "the source supplied ${distinct.size} record(s) with no ordering basis; " +
                            "the newest is not established by this read",
                ),
            completeness = Completeness.UNKNOWN,
            coverage = coverage,
            duplicatesIgnored = duplicates,
        )

    /** A family that reports nothing: absence classified by why it is absent, not by the empty list. */
    fun absent(
        kind: DecisionKind,
        coverage: LongRange? = null,
        completeness: Completeness = Completeness.UNKNOWN,
    ): DecisionProjection {
        val authority = GovernanceFactClassification.absenceAuthorityFor(kind)
        return DecisionProjection(
            kind = kind,
            latest =
                ProjectedFact(
                    value = null,
                    authority = authority,
                    freshness = FactFreshness.ABSENT,
                    established =
                        if (authority == FactAuthority.NOT_PRODUCED) {
                            "${readable(kind)} has no live production producer in this repository"
                        } else {
                            "the source read returned no ${readable(kind)} records for this run"
                        },
                    mustNotInfer = GovernanceFactClassification.absenceLimitationsFor(kind),
                ),
            completeness = completeness,
            coverage = coverage,
        )
    }

    private fun recordFact(
        kind: DecisionKind,
        record: RecordedDecision,
        freshness: FactFreshness,
    ): ProjectedFact<RecordedDecision> =
        ProjectedFact(
            value = record,
            authority = GovernanceFactClassification.authorityFor(kind),
            freshness = freshness,
            established = "a recorded ${readable(kind)} fact at its own source position",
            mustNotInfer = GovernanceFactClassification.limitationsFor(kind) + boundaryLimits(record),
            sourceVersion = record.sourceSequence,
        )

    /**
     * A record that names a boundary carries that boundary's registered strength, and that strength
     * never proves the event came from the boundary. The limitation travels with the claim.
     */
    private fun boundaryLimits(record: RecordedDecision): List<String> =
        if (record.boundary == null) {
            emptyList()
        } else {
            listOf(GovernanceFactClassification.BOUNDARY_ORIGIN_LIMIT)
        }

    private fun readable(kind: DecisionKind): String = kind.name.lowercase().replace('_', ' ')
}
