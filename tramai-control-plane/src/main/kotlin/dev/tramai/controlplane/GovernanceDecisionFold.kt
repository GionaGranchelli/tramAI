package dev.tramai.controlplane

/**
 * 0.7.4c1 — folding source records into one projected fact per decision kind.
 *
 * Separated from [GovernanceProjectionReader] so each file stays inside the repository's function
 * budget, and because this is the part with the ordering rules worth reading on their own: dedupe by
 * identity, elect the highest source position regardless of delivery order, and refuse to claim
 * completeness when the source's own positions have a hole or do not exist.
 */
internal object GovernanceDecisionFold {
    fun project(
        kind: DecisionKind,
        records: List<RecordedDecision>,
    ): DecisionProjection {
        val distinct = records.distinct()
        val duplicates = records.size - distinct.size
        val sequences = GovernanceFactClassification.sequencesOf(distinct)
        return when {
            records.isEmpty() -> DecisionProjection(kind, noRecords(kind), Completeness.UNKNOWN)
            sequences == null -> unorderedFold(kind, distinct, duplicates)
            else -> orderedFold(kind, distinct, sequences, duplicates)
        }
    }

    private fun orderedFold(
        kind: DecisionKind,
        distinct: List<RecordedDecision>,
        sequences: List<Long>,
        duplicates: Int,
    ): DecisionProjection {
        val latest =
            requireNotNull(distinct.maxByOrNull { it.sourceSequence ?: Long.MIN_VALUE }) {
                "a non-empty fold has a newest record"
            }
        return DecisionProjection(
            kind = kind,
            latest = recordFact(kind, latest),
            completeness = GovernanceFactClassification.completenessOf(sequences),
            duplicatesIgnored = duplicates,
            staleIgnored = distinct.size - 1,
        )
    }

    /** No ordering basis: report the fact, and state that the newest could not be determined. */
    private fun unorderedFold(
        kind: DecisionKind,
        distinct: List<RecordedDecision>,
        duplicates: Int,
    ): DecisionProjection =
        DecisionProjection(
            kind = kind,
            latest =
                recordFact(kind, distinct.first()).copy(
                    freshness = FactFreshness.STALE_OR_UNKNOWN,
                    established =
                        "the source supplied ${distinct.size} record(s) with no ordering basis; " +
                            "the newest is not established by this read",
                ),
            completeness = Completeness.UNKNOWN,
            duplicatesIgnored = duplicates,
        )

    private fun recordFact(
        kind: DecisionKind,
        record: RecordedDecision,
    ): ProjectedFact<RecordedDecision> =
        ProjectedFact(
            value = record,
            authority = GovernanceFactClassification.authorityFor(kind),
            // A record with no source position is not identifiable as a point in the past, so it
            // cannot claim historical standing: its freshness stays unestablished.
            freshness =
                if (record.sourceSequence == null) {
                    FactFreshness.STALE_OR_UNKNOWN
                } else {
                    FactFreshness.HISTORICAL
                },
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

    fun noRecords(kind: DecisionKind): ProjectedFact<RecordedDecision> =
        ProjectedFact(
            value = null,
            authority = FactAuthority.NOT_PRODUCED,
            freshness = FactFreshness.ABSENT,
            established =
                when (kind) {
                    DecisionKind.PROVIDER_ROUTE -> {
                        "provider.route has no live production producer in this repository"
                    }

                    else -> {
                        "no ${readable(kind)} records were produced for this run"
                    }
                },
            mustNotInfer = GovernanceFactClassification.limitationsFor(kind),
        )

    private fun readable(kind: DecisionKind): String = kind.name.lowercase().replace('_', ' ')
}
