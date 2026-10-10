package dev.tramai.security.governance

import dev.tramai.core.policy.DataClassification
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone

/**
 * Whether this specific provider-bound input may be released to a deployment.
 *
 * A compatible trust zone is necessary and never sufficient. Release requires
 * BOTH existing authorities to agree, for the same input:
 *
 * ```text
 * workload zone -> provider deployment zone     TrustZonePolicy
 * classification -> provider deployment zone    ClassificationRoutingRule.allowedZones
 *                          |
 *                          v
 *                   released only if both agree
 * ```
 *
 * Anything else withholds, and no claim can widen the other: a compatible zone
 * pair cannot release a classification that has no release rule, and a
 * classification that permits a zone cannot release across incompatible zones.
 *
 * The classification side reuses [ClassificationRoutingRule.allowedZones] — the
 * existing rule for which provider trust zones may handle a classification —
 * rather than restating it. [ClassificationRoutingRule.allowedFallbackZones] is
 * deliberately NOT consulted: a fallback concession is not a release permission
 * for a specific input.
 *
 * This is a decision only. It does not select a provider, invoke one, transform
 * the input, or minimize it, and it performs no I/O.
 */
class ProviderInputRelease(
    private val trustZonePolicy: TrustZonePolicy = TrustZonePolicy(),
    private val rules: Map<DataClassification, ClassificationRoutingRule> = emptyMap(),
) {
    /**
     * True only when the zones are compatible AND the classification's rule
     * permits that zone. A classification with no rule releases nothing.
     *
     * Defined as "no refusal", so this view and [refusalFor] cannot disagree.
     */
    fun releases(
        workloadZone: ProviderTrustZone,
        classification: DataClassification,
        providerZone: ProviderTrustZone,
    ): Boolean = refusalFor(workloadZone, classification, providerZone) == null

    /**
     * Why this input is withheld, or `null` when it is released.
     *
     * A classification with no rule and a rule that omits the zone are
     * deliberately the same refusal. The configured rule matrix does not record
     * which of the two an operator intended, so reporting a distinction here
     * would be a claim the facts do not support.
     */
    fun refusalFor(
        workloadZone: ProviderTrustZone,
        classification: DataClassification,
        providerZone: ProviderTrustZone,
    ): ReleaseRefusal? =
        when {
            !trustZonePolicy.allows(workloadZone, providerZone) -> {
                ReleaseRefusal.ZONE_PAIR_NOT_ALLOWED
            }

            rules[classification]?.allowedZones?.contains(providerZone) != true -> {
                ReleaseRefusal.CLASSIFICATION_ZONE_NOT_PERMITTED
            }

            else -> {
                null
            }
        }
}

/**
 * Stable reason why a provider-bound input was withheld from a deployment.
 *
 * Two members only: the two authorities this boundary consults can each refuse,
 * and nothing else in the configured facts can.
 */
enum class ReleaseRefusal {
    /** The workload-to-deployment zone pair is not an explicitly listed pair. */
    ZONE_PAIR_NOT_ALLOWED,

    /** The classification's routing rule does not permit the deployment's zone. */
    CLASSIFICATION_ZONE_NOT_PERMITTED,
}
