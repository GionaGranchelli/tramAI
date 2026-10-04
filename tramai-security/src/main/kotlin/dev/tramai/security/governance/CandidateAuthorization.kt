package dev.tramai.security.governance

import dev.tramai.core.policy.DataClassification
import dev.tramai.security.ProviderTrustZone

/**
 * One authorizable provider/model candidate: an identity plus the concrete
 * deployment that would serve it.
 *
 * An identity alone cannot be authorized. Trust follows from where a deployment
 * actually runs, not from who publishes the model, and one provider brand has
 * many deployments in different trust zones. Pairing the identity with exactly
 * one [ProviderDeployment] is therefore the minimum this boundary needs.
 *
 * The pair may be *inconsistent*: [providerId] and [deployment] can name
 * different providers. That is representable on purpose. An inconsistent
 * candidate is a fact to be refused, not a construction error to be thrown, so no
 * caller can turn a malformed candidate into an authorization by catching an
 * exception.
 *
 * An identity that is blank or carries surrounding whitespace is rejected at
 * construction, because it names nothing usable or names something ambiguous.
 * Nothing else about a candidate throws.
 *
 * This type states candidacy and nothing else. Whether the candidate is
 * authorized is decided by [CandidateAuthorization]; whether it is viable is a
 * later stage; whether it is selected, ranked, or invoked is not modelled here.
 */
data class ProviderCandidate(
    val providerId: String,
    val modelId: String,
    val deployment: ProviderDeployment,
) {
    init {
        require(providerId.isNotBlank()) { "ProviderCandidate providerId must not be blank" }
        require(providerId == providerId.trim()) {
            "ProviderCandidate providerId must not contain leading or trailing whitespace"
        }
        require(modelId.isNotBlank()) { "ProviderCandidate modelId must not be blank" }
        require(modelId == modelId.trim()) {
            "ProviderCandidate modelId must not contain leading or trailing whitespace"
        }
    }
}

/**
 * The outcome of the authorization stage: exactly [Authorized] or
 * [NotAuthorized].
 *
 * There is no third outcome and no Boolean form. A refusal always carries an
 * [AuthorizationRefusal], so "denied" never has to be re-derived from a false
 * value, and an authorization never has to be re-derived from a reason being
 * absent. Viability is a separate stage with its own vocabulary
 * ([CandidateViabilityDecision]) and is deliberately not expressible here.
 */
sealed interface CandidateAuthorizationDecision {
    /** Every required restriction permitted this candidate. */
    data object Authorized : CandidateAuthorizationDecision

    /** At least one required restriction refused this candidate, for [reason]. */
    data class NotAuthorized(
        val reason: AuthorizationRefusal,
    ) : CandidateAuthorizationDecision
}

/**
 * Stable, closed reason family for [CandidateAuthorizationDecision.NotAuthorized].
 *
 * Each member names one restriction this boundary consults, and nothing else can
 * produce a refusal. The family is exhaustive: a caller can branch on it without
 * a fallback, and adding a member is a compile error everywhere it must be
 * handled.
 */
enum class AuthorizationRefusal {
    /** The candidate's identity names a different provider than its deployment. */
    IDENTITY_DEPLOYMENT_MISMATCH,

    /** The workload-to-deployment zone pair is not an explicitly listed pair. */
    ZONE_PAIR_NOT_ALLOWED,

    /** The classification's routing rule does not permit the deployment's zone. */
    CLASSIFICATION_ZONE_NOT_PERMITTED,
}

/**
 * Vocabulary for the viability stage, which applies runtime constraints *after*
 * authorization.
 *
 * Declared in 0.7.3b and produced by [CandidateViability] since 0.7.3d. The
 * authorization boundary still cannot express these values — it returns
 * [CandidateAuthorizationDecision] — so a refusal always says which stage refused
 * it and the two stages stay separable.
 *
 * [ViabilityRefusal] names the constraint families a producer in this repository
 * can actually report. Optimization signals — cost, latency, preference — are
 * deliberately absent: they may rank candidates but may never remove one from the
 * viable set.
 */
sealed interface CandidateViabilityDecision {
    /** The candidate satisfies every required runtime constraint. */
    data object Viable : CandidateViabilityDecision

    /** The candidate failed the runtime constraint named by [reason]. */
    data class NotViable(
        val reason: ViabilityRefusal,
    ) : CandidateViabilityDecision
}

/**
 * Constraint families that can make an authorized candidate non-viable.
 *
 * The epic fixes the boundary these stages sit on:
 *
 * ```text
 * authorized = policy ∩ classification ∩ trust ∩ capability ∩ registration
 * viable     = authorized ∩ required runtime constraints
 * ```
 *
 * Capability is therefore **not** here. A candidate that cannot perform a required
 * capability is not temporarily unusable — it is not an eligible authorized
 * candidate for that request. `CAPABILITY` was declared here in 0.7.3b and removed
 * in 0.7.3d for that reason; wiring capability facts into the authorization
 * boundary is a separate, smaller correction.
 *
 * `HEALTH` was removed in the same slice: the first producer landed and could not
 * produce it, because nothing in the repository reports provider health. It was a
 * hypothetical state rather than an unexpressed one (ADR-020).
 *
 * Cost and latency are absent for a third reason: they are selection signals, not
 * runtime constraints, and admitting them would let an optimization signal remove
 * governance authority.
 */
enum class ViabilityRefusal {
    /** The deployment is not reachable right now. */
    AVAILABILITY,
}

/**
 * Which candidates belong to the authorized set for one workload and
 * classification.
 *
 * A candidate is authorized only when every required restriction permits it:
 *
 * ```text
 * candidate identity agrees with its deployment      consistency
 * workload zone -> deployment zone                   TrustZonePolicy
 * classification -> deployment zone                  ClassificationRoutingRule
 *                          |
 *                          v
 *                  authorized only if all agree
 * ```
 *
 * The two governance authorities are consumed through [ProviderInputRelease],
 * which is already their conjunction, rather than restated here: a second copy of
 * the same predicate is a second thing to keep in agreement. Its refusal is
 * mapped member-for-member onto [AuthorizationRefusal], so the two families cannot
 * drift apart without a compile error.
 *
 * Refusal is the default and has no exceptions. An unknown or unlisted zone pair
 * refuses. A classification with no rule refuses. A candidate whose identity
 * disagrees with its deployment refuses. An authorization holding no policy and no
 * rules refuses everything, so permission has to be stated to exist — nothing
 * becomes authorized through absence of policy, and no candidate can be widened
 * by another candidate's authorization.
 *
 * The refusal reported for a candidate failing more than one restriction is
 * deterministic and ordered: identity first, then the zone-pair authority, then
 * the classification rule. The order is part of the contract and is asserted.
 *
 * The authorized set is a [Set], so candidate ordering cannot influence it and a
 * candidate evaluated twice cannot add authority. This is a decision only: it does
 * not evaluate viability, rank, score, prefer, select, fall back, retry, invoke,
 * or perform I/O.
 *
 * Authorization currently coincides with the release predicate, because
 * provider-input minimization does not exist yet. When it does, release becomes
 * strictly narrower than authorization, and this boundary must consult the
 * authorization facts directly instead of the release decision.
 */
class CandidateAuthorization(
    private val release: ProviderInputRelease = ProviderInputRelease(),
) {
    /**
     * The decision for one candidate. Refusal order is identity, then zone pair,
     * then classification rule.
     */
    fun decisionFor(
        candidate: ProviderCandidate,
        workloadZone: ProviderTrustZone,
        classification: DataClassification,
    ): CandidateAuthorizationDecision {
        if (candidate.deployment.providerId != candidate.providerId) {
            return CandidateAuthorizationDecision.NotAuthorized(
                AuthorizationRefusal.IDENTITY_DEPLOYMENT_MISMATCH,
            )
        }
        val refusal =
            release.refusalFor(workloadZone, classification, candidate.deployment.trustZone.category)
        return when (refusal) {
            null -> {
                CandidateAuthorizationDecision.Authorized
            }

            ReleaseRefusal.ZONE_PAIR_NOT_ALLOWED -> {
                CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED)
            }

            ReleaseRefusal.CLASSIFICATION_ZONE_NOT_PERMITTED -> {
                CandidateAuthorizationDecision.NotAuthorized(
                    AuthorizationRefusal.CLASSIFICATION_ZONE_NOT_PERMITTED,
                )
            }
        }
    }

    /**
     * The authorized subset of [candidates] for this workload and
     * classification. Order-independent, duplicate-insensitive, and possibly
     * empty — an empty result means no candidate may be used, not that the
     * caller should look elsewhere or fall back to something outside the set.
     */
    fun authorizedSet(
        candidates: Collection<ProviderCandidate>,
        workloadZone: ProviderTrustZone,
        classification: DataClassification,
    ): Set<ProviderCandidate> =
        candidates
            .filter { decisionFor(it, workloadZone, classification) is CandidateAuthorizationDecision.Authorized }
            .toSet()

    /**
     * The same decision as [authorizedSet], as the value the viability stage
     * requires. One implementation, two views: the set and the
     * [AuthorizedCandidates] value cannot disagree, and viability can only ever be
     * asked about candidates this boundary authorized.
     */
    fun authorizedCandidates(
        candidates: Collection<ProviderCandidate>,
        workloadZone: ProviderTrustZone,
        classification: DataClassification,
    ): AuthorizedCandidates = AuthorizedCandidates(authorizedSet(candidates, workloadZone, classification))
}
