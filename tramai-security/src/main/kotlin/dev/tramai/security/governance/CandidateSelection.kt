package dev.tramai.security.governance

/**
 * The outcome of the selection stage: exactly [Selected] or [NoSelection].
 *
 * There is no third outcome and no nullable form. A `ProviderCandidate?` cannot say whether nothing
 * was viable, whether the strategy declined, or whether the strategy tried to answer with a
 * candidate from outside the viable set — three different facts that need three different
 * responses upstream. Every non-selection therefore carries a [SelectionRefusal].
 */
sealed interface CandidateSelectionDecision {
    /** The strategy chose [candidate], which is a member of the viable set it was given. */
    data class Selected(
        val candidate: ProviderCandidate,
    ) : CandidateSelectionDecision

    /** No candidate was selected, for [reason]. */
    data class NoSelection(
        val reason: SelectionRefusal,
    ) : CandidateSelectionDecision
}

/**
 * Stable, closed reason family for [CandidateSelectionDecision.NoSelection].
 *
 * Each member names a fact this boundary can actually produce, and nothing else can produce a
 * non-selection. The family is exhaustive: a caller can branch on it without a fallback.
 *
 * Optimization signals are deliberately absent. Cost, latency, health and preference order viable
 * candidates; they do not refuse them, and they never create one.
 */
enum class SelectionRefusal {
    /** The viable set is empty: nothing may be selected, and nothing outside it may be reached for. */
    NO_VIABLE_CANDIDATES,

    /** The strategy answered with no candidate at all. */
    STRATEGY_DECLINED,

    /**
     * The strategy answered with a candidate that is not in the viable set it was given.
     *
     * This is the escape this boundary exists to refuse. It is reported as a non-selection rather
     * than accepted, and rather than being silently rewritten to an in-set candidate: widening
     * authority and guessing at intent are both worse than a deterministic refusal.
     */
    STRATEGY_OUTSIDE_VIABLE_SET,
}

/**
 * A preference over viable candidates: given the viable set, which single candidate is preferred.
 *
 * Returning `null` declines to choose, which is [SelectionRefusal.STRATEGY_DECLINED] and not an
 * error. Ranking, ordering by cost or latency, and preferring a configured primary are all
 * expressible here; none of them can add a candidate, because [CandidateSelection] checks the
 * answer against the set it supplied.
 */
fun interface CandidateSelectionStrategy {
    fun select(viable: Set<ProviderCandidate>): ProviderCandidate?
}

/**
 * Chooses zero or one candidate from a viable set.
 *
 * Its only job is the epic's final stage:
 *
 * ```text
 * selected = selectionStrategy(viable)
 * ```
 *
 * It does not authorize, evaluate trust, classification, registration or capability, reevaluate
 * availability, discover providers, resolve fallback routes, or invoke anything. It consumes the
 * result of the two stages before it and decides.
 *
 * The membership fence is the point of this boundary. A strategy is a caller-supplied function, so
 * its answer is untrusted input: a strategy that returns a candidate from outside the set cannot
 * widen the selection, because the answer is checked against [ViableCandidates] before it is used.
 * This is enforced here, not documented as a strategy's obligation.
 *
 * Fallback and retry compose from the same envelope: pass `viable.without(attempted)` and the
 * remaining candidates are drawn from the original set. Discovery is never consulted again, so a
 * retry cannot recreate authority.
 */
class CandidateSelection {
    /**
     * The selection decision for [viable] under [strategy].
     *
     * Refusal order is deterministic: an empty viable set is refused before the strategy is
     * consulted, so a strategy is never asked to choose from nothing and cannot turn an empty set
     * into a selection.
     */
    fun select(
        viable: ViableCandidates,
        strategy: CandidateSelectionStrategy,
    ): CandidateSelectionDecision {
        val empty = viable.isEmpty()
        // The strategy receives a defensive copy, never the envelope's own backing set: a strategy
        // is caller-supplied code and Kotlin's read-only Set is not immutable, so a set it can cast
        // to MutableSet would let it add a candidate to the very set the check below consults.
        val chosen = if (empty) null else strategy.select(viable.candidates.toSet())
        return when {
            empty -> {
                CandidateSelectionDecision.NoSelection(SelectionRefusal.NO_VIABLE_CANDIDATES)
            }

            chosen == null -> {
                CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_DECLINED)
            }

            !viable.contains(chosen) -> {
                CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET)
            }

            else -> {
                CandidateSelectionDecision.Selected(chosen)
            }
        }
    }
}
