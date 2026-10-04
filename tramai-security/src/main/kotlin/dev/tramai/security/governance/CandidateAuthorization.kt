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
 * candidate is a fact to be denied, not a construction error to be thrown, so no
 * caller can turn a malformed candidate into an authorization by catching an
 * exception.
 *
 * An identity that is blank or carries surrounding whitespace is rejected at
 * construction, because it names nothing usable or names something ambiguous.
 * Nothing else about a candidate throws.
 *
 * This type states candidacy and nothing else. Whether the candidate is
 * authorized is decided by [CandidateAuthorization]; whether it is selected,
 * ranked, viable, or invoked is not modelled here or there.
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
 * the same predicate is a second thing to keep in agreement.
 *
 * Denial is the default and has no exceptions. An unknown or unlisted zone pair
 * denies. A classification with no rule denies. A candidate whose identity
 * disagrees with its deployment denies. An authorization holding no policy and no
 * rules denies everything, so permission has to be stated to exist — nothing
 * becomes authorized through absence of policy, and no candidate can be widened
 * by another candidate's authorization.
 *
 * The result is a [Set], so candidate ordering cannot influence it and a
 * candidate evaluated twice cannot add authority. This is a decision only: it
 * does not rank, score, prefer, select, fall back, retry, invoke, or perform I/O.
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
     * True only when the candidate's identity agrees with its deployment AND both
     * governance authorities permit this deployment's zone for this workload and
     * classification.
     */
    fun authorizes(
        candidate: ProviderCandidate,
        workloadZone: ProviderTrustZone,
        classification: DataClassification,
    ): Boolean =
        candidate.deployment.providerId == candidate.providerId &&
            release.releases(workloadZone, classification, candidate.deployment.trustZone.category)

    /**
     * The authorized subset of [candidates] for this workload and
     * classification. Order-independent, duplicate-insensitive, and possibly
     * empty — an empty result means no candidate may be used, not that the
     * caller should look elsewhere.
     */
    fun authorizedSet(
        candidates: Collection<ProviderCandidate>,
        workloadZone: ProviderTrustZone,
        classification: DataClassification,
    ): Set<ProviderCandidate> = candidates.filter { authorizes(it, workloadZone, classification) }.toSet()
}
