package dev.tramai.security.governance

import dev.tramai.security.ProviderTrustZone

/**
 * Whether a workload in one trust zone may use a provider deployment in another.
 *
 * The rule is an explicit allow-list of ORDERED pairs:
 *
 * ```text
 * listed pair      -> allowed
 * everything else  -> denied
 * ```
 *
 * "Everything else" is deliberate and total. A pair listed in the opposite order
 * is denied. A workload and a deployment in the same zone are denied unless that
 * pair is listed — same-zone is not an implicit exception. A policy holding no
 * pairs denies every pair, so the default is the closed one and permission has to
 * be stated to exist.
 *
 * Only the portable [ProviderTrustZone] category is compared. Organization-defined
 * names describe one organization's topology; the category is the vocabulary two
 * sides can agree on.
 *
 * This answers compatibility and nothing else. Whether a deployment is selected,
 * eligible, or reachable is a different question, and is not modelled here.
 */
class TrustZonePolicy(
    allowedPairs: Set<Pair<ProviderTrustZone, ProviderTrustZone>> = emptySet(),
) {
    // Copied so a caller cannot widen a policy by mutating the set it passed in.
    private val allowed: Set<Pair<ProviderTrustZone, ProviderTrustZone>> = allowedPairs.toSet()

    /**
     * True only when `workloadZone -> providerZone` is explicitly allowed.
     */
    fun allows(
        workloadZone: ProviderTrustZone,
        providerZone: ProviderTrustZone,
    ): Boolean = (workloadZone to providerZone) in allowed
}
