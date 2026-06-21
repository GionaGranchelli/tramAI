package dev.tramai.spring.sovereign

import dev.tramai.security.ProviderTrustZone
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Externalized configuration for TramAI sovereign runtime.
 *
 * Usage:
 * ```
 * tramai:
 *   sovereign:
 *     enabled: true
 *     allowed-models:
 *       - local-invoice-model
 *     allowed-providers:
 *       - local-provider
 *     allowed-tools:
 *       - schedule-payment
 *     allowed-permissions:
 *       - payment.schedule
 *     provider-zones:
 *       local-provider: LOCAL
 *     models:
 *       local-invoice-model: local-provider
 * ```
 */
@ConfigurationProperties(prefix = "tramai.sovereign")
data class SovereignTramaiProperties(
    /**
     * Enables sovereign profile auto-configuration. When false, no sovereign
     * beans are created (respects [ConditionalOnProperty] semantics).
     */
    var enabled: Boolean = true,

    /**
     * Models whose invocation is permitted. Must be non-empty when enabled.
     * Each entry must appear as a key in [models].
     */
    var allowedModels: Set<String> = emptySet(),

    /**
     * Providers that may be used. Must be non-empty when enabled.
     * Each entry must have a corresponding [providerZones] mapping.
     */
    var allowedProviders: Set<String> = emptySet(),

    /**
     * Tools whose execution is permitted.
     */
    var allowedTools: Set<String> = emptySet(),

    /**
     * Tool permissions that are granted.
     */
    var allowedPermissions: Set<String> = emptySet(),

    /**
     * Explicit trust-zone mapping for every allowed provider.
     * Keys must be a subset of [allowedProviders].
     */
    var providerZones: Map<String, String> = emptyMap(),

    /**
     * Maps each logical model name to its provider.
     * Keys must be a subset of [allowedModels].
     */
    var models: Map<String, String> = emptyMap(),
) {
    /** Validates this configuration and returns a resolved [ProviderTrustZone] map. */
    internal fun resolveProviderTrustZones(): Map<String, ProviderTrustZone> {
        val zones = providerZones.mapValues { (_, zone) ->
            try {
                ProviderTrustZone.valueOf(zone.uppercase())
            } catch (_: IllegalArgumentException) {
                throw IllegalStateException(
                    "tramai-sovereign-spring-invalid-provider-zone: '$zone' is not a valid ProviderTrustZone " +
                        "(expected one of: ${ProviderTrustZone.entries.joinToString(", ")})",
                )
            }
        }

        // enabled=true but no allowed models
        check(allowedModels.isNotEmpty()) {
            "tramai-sovereign-spring-missing-allowed-models"
        }

        // enabled=true but no allowed providers
        check(allowedProviders.isNotEmpty()) {
            "tramai-sovereign-spring-missing-allowed-providers"
        }

        // Every allowed provider must have an explicit provider zone
        for (provider in allowedProviders) {
            check(provider in zones) {
                    "tramai-sovereign-spring-provider-zone-missing: provider '$provider' has no configured trust zone"
            }
        }

        // Every provider zone must reference an allowed provider
        for (provider in zones.keys) {
            check(provider in allowedProviders) {
                    "tramai-sovereign-spring-provider-zone-unknown-provider: zone configured for " +
                        "provider '$provider' which is not in allowedProviders"
            }
        }

        // Every model in models map must be in allowedModels
        for (modelName in models.keys) {
            check(modelName in allowedModels) {
                    "tramai-sovereign-spring-model-route-unknown-model: model '$modelName' has a route " +
                        "but is not in allowedModels"
            }
        }

        // Every allowed model must have a route
        for (modelName in allowedModels) {
            check(modelName in models) {
                    "tramai-sovereign-spring-missing-model-route: allowed model '$modelName' has no configured provider route"
            }
        }

        // Every model route must target an allowed provider
        for ((modelName, providerName) in models) {
            check(providerName in allowedProviders) {
                    "tramai-sovereign-spring-model-route-unknown-provider: model '$modelName' routes to " +
                        "provider '$providerName' which is not in allowedProviders"
            }
        }

        return zones
    }
}
