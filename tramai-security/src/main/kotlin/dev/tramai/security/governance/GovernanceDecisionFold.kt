package dev.tramai.security.governance

/**
 * 0.7.4c1 — folding source records into one projected fact per decision family.
 *
 * Separated from [GovernanceProjectionReader] so each file stays inside the repository's function
 * budget, and because this is where the ordering rules live: dedupe by identity, elect the highest
 * source position regardless of delivery order, never elect one without an ordering basis, and carry
 * completeness through from the read rather than deriving it per family — the audit stream's
 * positions are per stream, so another family's position is not a hole in this family's history, and
 * the decision-record claim belongs to the read that verified the stream rather than to this fold.
 */
internal object GovernanceDecisionFold {
    fun project(
        kind: DecisionKind,
        records: List<RecordedDecision>,
        coverage: StreamCoverage,
        completeness: Completeness,
    ): DecisionProjection {
        val distinct = records.distinct()
        val duplicates = records.size - distinct.size
        val contradictions = contradictions(distinct)
        return when {
            records.isEmpty() -> {
                absent(kind, coverage, completeness)
            }

            contradictions.isNotEmpty() -> {
                conflicting(kind, contradictions, duplicates, coverage)
            }

            distinct.all { it.sourceSequence != null } -> {
                orderedFold(kind, distinct, duplicates, coverage, completeness)
            }

            else -> {
                unorderedFold(kind, distinct, duplicates, coverage)
            }
        }
    }

    /**
     * Positions the source answered more than once, differently.
     *
     * Repeating a record is a duplicate, and [RecordedDecision] equality already collapses those. Two *different*
     * records at one position are not a duplicate: the source has not established which of them happened, and
     * electing either would make the projected fact a function of the order the source happened to return them
     * in. No source contract here promises that order, so the position is reported as unresolved instead.
     */
    private fun contradictions(distinct: List<RecordedDecision>): Map<Long, List<RecordedDecision>> =
        distinct
            .filter { it.sourceSequence != null }
            .groupBy { it.sourceSequence as Long }
            .filterValues { atPosition -> atPosition.map { it.copy(sourceSequence = null) }.distinct().size > 1 }

    /**
     * A position the source contradicted itself on.
     *
     * The read observed records but could not establish which of them is the recorded one, so the claim is the
     * one [FactAuthority.NOT_OBSERVED] carries for exactly this case: evidence about the read, not about the
     * world. The contradiction is named in [ProjectedFact.established] so the absence cannot be mistaken for an
     * empty stream.
     */
    private fun conflicting(
        kind: DecisionKind,
        contradictions: Map<Long, List<RecordedDecision>>,
        duplicates: Int,
        coverage: StreamCoverage,
    ): DecisionProjection {
        val positions = contradictions.keys.sorted().joinToString(", ")
        return unresolved(
            kind = kind,
            duplicates = duplicates,
            coverage = coverage,
            established =
                "the source returned contradictory records at source position(s) $positions; " +
                    "this read does not establish which of them was recorded",
        )
    }

    /**
     * A read that observed records but cannot say which one is the recorded one.
     *
     * Reporting a value would need an election, and every election available here is delivery order, which no
     * source contract makes meaningful. Completeness is withdrawn for the same reason, and the limitation says
     * plainly that this is not evidence that nothing happened.
     */
    private fun unresolved(
        kind: DecisionKind,
        duplicates: Int,
        coverage: StreamCoverage,
        established: String,
    ): DecisionProjection =
        DecisionProjection(
            kind = kind,
            latest =
                ProjectedFact(
                    value = null,
                    authority = FactAuthority.NOT_OBSERVED,
                    freshness = FactFreshness.ABSENT,
                    established = established,
                    mustNotInfer =
                        listOf(
                            "records the source contradicted or failed to order are not evidence that no " +
                                "decision occurred",
                        ),
                ),
            completeness = Completeness.UNKNOWN,
            coverage = coverage,
            duplicatesIgnored = duplicates,
        )

    private fun orderedFold(
        kind: DecisionKind,
        distinct: List<RecordedDecision>,
        duplicates: Int,
        coverage: StreamCoverage,
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
     * No ordering basis: report the fact only if the records agree on it.
     *
     * The records are historical by nature, but without a position none can be identified as the point it
     * reflects, so none of them may claim historical standing. Agreement is a separate question from ordering:
     * when the records say the same thing there is still a fact to report, and when they disagree electing one
     * would make the projection a function of the order the source happened to deliver them in.
     */
    private fun unorderedFold(
        kind: DecisionKind,
        distinct: List<RecordedDecision>,
        duplicates: Int,
        coverage: StreamCoverage,
    ): DecisionProjection {
        val agrees = distinct.map { it.copy(sourceSequence = null) }.distinct().size == 1
        if (!agrees) {
            return unresolved(
                kind = kind,
                duplicates = duplicates,
                coverage = coverage,
                established =
                    "the source supplied ${distinct.size} records with no ordering basis and they disagree; " +
                        "this read does not establish which of them was recorded",
            )
        }
        // The record that stands for an agreeing fact must not be chosen by arrival order: its source position
        // reaches ProjectedFact.sourceVersion and from there the persisted snapshot's canonical digest, so
        // taking the first delivered record would let delivery order decide a digest that is supposed to
        // describe the evidence alone. The highest known position is the only order-independent choice here,
        // and it identifies the fact only - the freshness below stays unknown, so it claims nothing newer.
        val consensus = distinct.sortedByDescending { it.sourceSequence ?: Long.MIN_VALUE }.first()
        return DecisionProjection(
            kind = kind,
            latest =
                recordFact(kind, consensus, FactFreshness.STALE_OR_UNKNOWN).copy(
                    established =
                        "the source supplied ${distinct.size} record(s) with no ordering basis; " +
                            "the newest is not established by this read",
                ),
            completeness = Completeness.UNKNOWN,
            coverage = coverage,
            duplicatesIgnored = duplicates,
        )
    }

    /** A family that reports nothing: absence classified by why it is absent, not by the empty list. */
    fun absent(
        kind: DecisionKind,
        coverage: StreamCoverage = StreamCoverage(),
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
