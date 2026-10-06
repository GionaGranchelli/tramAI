package dev.tramai.security.governance

/**
 * The candidates the authorization boundary has authorized, as a value distinct
 * from any set of candidates.
 *
 * Viability is only meaningful for candidates that are already authorized, and
 * this type is how that is enforced structurally rather than by convention: the
 * constructor is module-internal, so only code inside `tramai-security` can
 * produce one — in practice [CandidateAuthorization], which is its only caller. A
 * viability boundary that accepts [AuthorizedCandidates] therefore cannot be asked
 * to evaluate a candidate that was refused authorization.
 *
 * What the module boundary guarantees, stated precisely:
 *
 * - no consumer outside this module can construct an [AuthorizedCandidates], so a
 *   refused candidate cannot be smuggled in as an authorized one;
 * - [candidates] is `internal`, so it is not part of the published API and cannot
 *   be reached, cast, or mutated from outside the module.
 *
 * Inside the module this is a plain wrapper over a `Set`, which Kotlin models as
 * read-only rather than immutable. Module-internal code could in principle cast a
 * returned set and mutate it; there is no token framework here to prevent that,
 * and admitting one would cost far more than the risk it removes. The claim is
 * scoped to the module boundary, which is where the obligation actually lives.
 */
@JvmInline
value class AuthorizedCandidates internal constructor(
    internal val candidates: Set<ProviderCandidate>,
)

/**
 * Whether an already-authorized candidate can be used *right now*.
 *
 * Authorization and viability answer different questions, and this boundary keeps
 * them apart:
 *
 * ```text
 * authorization:  may TramAI use this candidate?      governance
 * viability:      can TramAI use it right now?        operational
 * ```
 *
 * The epic fixes which stage owns what:
 *
 * ```text
 * authorized = policy ∩ classification ∩ trust ∩ capability ∩ registration
 * viable     = authorized ∩ required runtime constraints
 * ```
 *
 * Capability therefore belongs to [CandidateAuthorization], not here — a model that
 * cannot perform the required capability is not temporarily unusable, it is not an
 * eligible authorized candidate for that request. Wiring capability facts into
 * authorization is a separate, smaller correction.
 *
 * The ordering is structural. [viableCandidates] and [decisions] accept
 * [AuthorizedCandidates], which no consumer outside this module can produce, so
 * there is no way to evaluate viability for a candidate that was not authorized,
 * and no way to express a viable candidate outside the authorized set.
 *
 * **The evaluator is required.** There is deliberately no default: a permissive
 * default would let `CandidateViability()` mark every authorized candidate viable
 * without consulting a single runtime fact, which is fail-open on a governance
 * boundary. Callers must pass their runtime facts explicitly. The function returns
 * `null` when the candidate satisfies every constraint it checks; whatever it
 * cannot establish it must report as a refusal, because an unestablished
 * constraint is not evidence of availability.
 *
 * Availability facts are supplied rather than modelled here, so this boundary
 * cannot become a second source of them: `ProviderCircuitBreaker`'s
 * `CircuitBreakerAdmission.Rejected` means the deployment is blocked right now.
 *
 * This is operational only. It does not rank, score, prefer, select, fall back,
 * retry, invoke, or perform I/O, and it attaches no precedence to its results.
 */
class CandidateViability(
    private val runtimeConstraintRefusal: (ProviderCandidate) -> ViabilityRefusal?,
) {
    /**
     * The viability outcome for every authorized candidate.
     *
     * Keys are a subset of [authorized] by construction, and the result is
     * order-insensitive: candidate ordering cannot influence which candidates are
     * viable.
     */
    fun decisions(authorized: AuthorizedCandidates): Map<ProviderCandidate, CandidateViabilityDecision> =
        authorized.candidates.associateWith { candidate ->
            val refusal = runtimeConstraintRefusal(candidate)
            if (refusal == null) {
                CandidateViabilityDecision.Viable
            } else {
                CandidateViabilityDecision.NotViable(refusal)
            }
        }

    /**
     * The authorized candidates that are viable right now, as the value the selection stage
     * requires. Possibly empty, which means nothing may be used at this moment, not that the caller
     * should reach outside the authorized set.
     *
     * This is the only producer of [ViableCandidates]. A selectable candidate is therefore always one
     * that authorization permitted and this stage found usable, and selection has no way to reach a
     * candidate that is not.
     */
    fun viableCandidates(authorized: AuthorizedCandidates): ViableCandidates =
        ViableCandidates(
            decisions(authorized)
                .filterValues { it is CandidateViabilityDecision.Viable }
                .keys
                .toSet(),
        )
}
