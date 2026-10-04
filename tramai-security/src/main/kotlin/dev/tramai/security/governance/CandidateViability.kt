package dev.tramai.security.governance

/**
 * The candidates the authorization boundary has authorized, as a value distinct
 * from any set of candidates.
 *
 * Viability is only meaningful for candidates that are already authorized, and
 * this type is how that is enforced structurally rather than by convention: the
 * constructor is module-internal, so [CandidateAuthorization] is the only thing
 * that can produce one. A viability boundary that accepts [AuthorizedCandidates]
 * therefore *cannot* be asked to evaluate a candidate that was refused
 * authorization, and `viable ⊆ authorized` cannot be broken by a caller — only by
 * editing the authorization boundary.
 *
 * The value carries a set that [CandidateAuthorization] has already materialized,
 * so a caller cannot widen authorization by mutating a set it owns.
 */
@JvmInline
value class AuthorizedCandidates internal constructor(
    val candidates: Set<ProviderCandidate>,
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
 * The order is structural. [viableCandidates] and [decisions] accept
 * [AuthorizedCandidates], which only [CandidateAuthorization] can produce, so
 * there is no way to evaluate viability for a candidate that was not authorized,
 * and no way to express a viable candidate outside the authorized set.
 *
 * Runtime constraints are supplied as a function rather than modelled here,
 * because the repository already owns the facts and this boundary must not become
 * a second source of them:
 *
 * - **capability** — the provider contract's `supportsCapability(ProviderCapability)`,
 *   with `VISION` / `STREAMING`, and `StreamCapable`;
 * - **availability** — `ProviderCircuitBreaker`'s `CircuitBreakerAdmission.Rejected`,
 *   meaning the deployment is blocked from being called right now.
 *
 * The function returns `null` when the candidate satisfies every runtime
 * constraint it checks. Whatever it cannot establish it must report as a refusal
 * rather than as satisfaction: viability has no authority to widen anything, and
 * an unestablished constraint is not evidence of availability either.
 *
 * This is operational only. It does not rank, score, prefer, select, fall back,
 * retry, invoke, or perform I/O, and it attaches no precedence to its results.
 */
class CandidateViability(
    private val runtimeConstraintRefusal: (ProviderCandidate) -> ViabilityRefusal? = { null },
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
     * The authorized candidates that are viable right now — possibly empty, which
     * means nothing may be used at this moment, not that the caller should reach
     * outside the authorized set.
     */
    fun viableCandidates(authorized: AuthorizedCandidates): Set<ProviderCandidate> =
        decisions(authorized)
            .filterValues { it is CandidateViabilityDecision.Viable }
            .keys
            .toSet()
}
