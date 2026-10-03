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
     */
    fun releases(
        workloadZone: ProviderTrustZone,
        classification: DataClassification,
        providerZone: ProviderTrustZone,
    ): Boolean =
        trustZonePolicy.allows(workloadZone, providerZone) &&
            rules[classification]?.allowedZones?.contains(providerZone) == true
}
