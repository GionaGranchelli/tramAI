package dev.tramai.security

import dev.tramai.core.policy.DataClassification

/**
 * Defines the trust zone of a provider — used by the classification-aware
 * routing matrix to decide which providers are eligible for a given
 * [DataClassification].
 */
enum class ProviderTrustZone {
    /** Same-host or isolated network boundary (Ollama, vLLM, llama.cpp). */
    LOCAL,
    /** Cloud providers hosted within the EU trust boundary. */
    EU_CLOUD,
    /** Global cloud providers outside EU jurisdiction. */
    GLOBAL_CLOUD,
}

/**
 * Routing rule for a single [DataClassification].
 *
 * [allowedZones] defines which provider trust zones may handle this
 * classification for primary (non-fallback) invocation.
 *
 * [allowedFallbackZones] defines which provider trust zones may handle
 * this classification during fallback (when the primary provider fails).
 * Can be a subset of [allowedZones] — or empty to deny all fallback for
 * sensitive classifications.
 */
data class ClassificationRoutingRule(
    val allowedZones: Set<ProviderTrustZone>,
    val allowedFallbackZones: Set<ProviderTrustZone>,
)

/**
 * Configuration for classification-aware provider routing.
 *
 * When [enabled] is true, [DefaultPolicyEngine] evaluates every
 * [DataClassification]-bearing request against the rule matrix before
 * allowing provider invocation or fallback.
 *
 * @param providerZones maps provider IDs (e.g., "openai", "ollama") to
 *   their trust zone.
 * @param rules maps each [DataClassification] to its routing constraints.
 *   Defaults to sovereign defaults via [sovereignDefaults].
 * @param enabled when false (default), routing matrix checks are skipped
 *   and the engine falls back to the legacy [PolicyConfiguration.trustedLocalProviders]
 *   and [PolicyConfiguration.allowCloudForClassifications] logic.
 */
data class ProviderRoutingConfiguration(
    val providerZones: Map<String, ProviderTrustZone> = emptyMap(),
    val rules: Map<DataClassification, ClassificationRoutingRule> = sovereignDefaults(),
    val enabled: Boolean = false,
) {
    companion object {
        /**
         * Sovereign defaults for classification-aware routing.
         *
         * - RESTRICTED: local only, no fallback to any cloud
         * - CONFIDENTIAL: local or EU cloud, fallback to local or EU cloud only
         * - INTERNAL: any zone, fallback to any zone
         * - PUBLIC: any zone, fallback to any zone
         */
        fun sovereignDefaults(): Map<DataClassification, ClassificationRoutingRule> {
            val allZones = ProviderTrustZone.entries.toSet()
            val localOnly = setOf(ProviderTrustZone.LOCAL)
            val localAndEu = setOf(ProviderTrustZone.LOCAL, ProviderTrustZone.EU_CLOUD)
            return mapOf(
                DataClassification.RESTRICTED to ClassificationRoutingRule(
                    allowedZones = localOnly,
                    allowedFallbackZones = emptySet(),
                ),
                DataClassification.CONFIDENTIAL to ClassificationRoutingRule(
                    allowedZones = localAndEu,
                    allowedFallbackZones = localAndEu,
                ),
                DataClassification.INTERNAL to ClassificationRoutingRule(
                    allowedZones = allZones,
                    allowedFallbackZones = allZones,
                ),
                DataClassification.PUBLIC to ClassificationRoutingRule(
                    allowedZones = allZones,
                    allowedFallbackZones = allZones,
                ),
            )
        }
    }
}
