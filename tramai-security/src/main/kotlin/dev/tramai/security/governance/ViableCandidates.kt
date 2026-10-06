package dev.tramai.security.governance

/**
 * The candidates the viability stage found usable right now, as a value distinct from any set of
 * candidates.
 *
 * Selection is only meaningful for candidates that are already viable, and this type is how that is
 * enforced structurally rather than by convention: the constructor is module-internal, so only code
 * inside `tramai-security` can produce one — in practice [CandidateViability], which is its only
 * caller. A selection boundary that accepts [ViableCandidates] therefore cannot be handed a
 * candidate that was refused authorization or found non-viable, and no caller can widen the
 * universe it selects from.
 *
 * The epic fixes the chain this type sits in:
 *
 * ```text
 * authorized = policy ∩ classification ∩ trust ∩ capability ∩ registration
 * viable     = authorized ∩ required runtime constraints
 * selected   = selectionStrategy(viable)
 * ```
 *
 * What the module boundary guarantees, stated precisely:
 *
 * - no consumer outside this module can construct a [ViableCandidates], so a non-viable candidate
 *   cannot be smuggled in as a selectable one;
 * - [candidates] is `internal`, so it is not part of the published API and cannot be reached,
 *   cast, or mutated from outside the module.
 *
 * Inside the module this is a plain wrapper over a `Set`, which Kotlin models as read-only rather
 * than immutable. The claim is scoped to the module boundary, which is where the obligation
 * actually lives.
 *
 * Fallback and retry stay inside this value rather than being re-derived from routing
 * configuration: [without] removes an attempted candidate and can only ever narrow the set. A
 * configured fallback route is not authority, so a candidate absent from this set is never
 * selectable, however it is configured.
 */
@JvmInline
value class ViableCandidates internal constructor(
    internal val candidates: Set<ProviderCandidate>,
) {
    /**
     * The same authority envelope with [candidate] removed, for a retry or fallback that must not
     * reconsider an attempted candidate.
     *
     * It narrows only: the result is always a subset of this value, so removing a candidate can
     * never introduce one, and a candidate that was not viable cannot become selectable by being
     * excluded.
     */
    fun without(candidate: ProviderCandidate): ViableCandidates = ViableCandidates(candidates - candidate)

    /** Whether this envelope holds no selectable candidate at all. */
    fun isEmpty(): Boolean = candidates.isEmpty()

    /**
     * Membership in this envelope. A selection result is checked against it, so this is the fact
     * that decides whether a strategy's answer may be used.
     */
    fun contains(candidate: ProviderCandidate): Boolean = candidate in candidates

    /**
     * The viable candidates in the order the supplied candidates arrived, for a strategy that
     * needs a deterministic ordering to express a preference over.
     *
     * This orders; it cannot authorize. Every element is already a member of this envelope.
     */
    fun orderedBy(preference: List<ProviderCandidate>): List<ProviderCandidate> = preference.filter { it in candidates }
}
