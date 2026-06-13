package dev.tramai.sovereign

import dev.tramai.core.policy.RiskLevel
import dev.tramai.security.PolicyConfiguration
import dev.tramai.security.ProviderRoutingConfiguration
import dev.tramai.security.ProviderTrustZone

/**
 * Immutable configuration for the sovereign embedded runtime profile.
 *
 * All allowlists require explicit entries. Wildcards are rejected. Every
 * allowed provider must have an explicit [providerZones] mapping. Fallback
 * providers must be a subset of [allowedProviders].
 *
 * @property allowedModels Models whose invocation is permitted. Must not be empty.
 * @property allowedProviders Providers that may be used. Must not be empty.
 * @property allowedFallbackProviders Providers that may be used as fallback destinations.
 *   Must be a subset of [allowedProviders].
 * @property allowedTools Tools whose execution is permitted.
 * @property allowedPermissions Tool permissions that are granted.
 * @property providerZones Explicit trust-zone mapping for every allowed provider.
 * @property deploymentMode Deployment connectivity contract (default: STANDARD).
 */
data class SovereignProfileConfiguration(
    val allowedModels: Set<String>,
    val allowedProviders: Set<String>,
    val allowedFallbackProviders: Set<String> = emptySet(),
    val allowedTools: Set<String> = emptySet(),
    val allowedPermissions: Set<String> = emptySet(),
    val providerZones: Map<String, ProviderTrustZone>,
    val deploymentMode: SovereignDeploymentMode = SovereignDeploymentMode.STANDARD,
) {
    init {
        require(allowedModels.isNotEmpty()) { "allowedModels must not be empty" }
        require(allowedProviders.isNotEmpty()) { "allowedProviders must not be empty" }

        allowedModels.forEach { validateNonBlank("allowedModels", it) }
        allowedProviders.forEach { validateNonBlank("allowedProviders", it) }
        allowedFallbackProviders.forEach { validateNonBlank("allowedFallbackProviders", it) }
        allowedTools.forEach { validateNonBlank("allowedTools", it) }
        allowedPermissions.forEach { validateNonBlank("allowedPermissions", it) }
        providerZones.keys.forEach { validateNonBlank("providerZones key", it) }

        require(allowedFallbackProviders.all { it in allowedProviders }) {
            "allowedFallbackProviders must be a subset of allowedProviders"
        }
        require(providerZones.keys.all { it in allowedProviders }) {
            "All providerZones keys must appear in allowedProviders"
        }
        require(allowedProviders.all { it in providerZones.keys }) {
            "All allowed providers must have an explicit provider zone"
        }

        // Reject wildcards
        val wildcardPattern = Regex("^\\*+$")
        fun rejectWildcard(label: String, value: String) {
            require(!wildcardPattern.matches(value)) {
                "Wildcard entries are not allowed in $label"
            }
        }
        allowedModels.forEach { rejectWildcard("allowedModels", it) }
        allowedProviders.forEach { rejectWildcard("allowedProviders", it) }
        allowedFallbackProviders.forEach { rejectWildcard("allowedFallbackProviders", it) }
        allowedTools.forEach { rejectWildcard("allowedTools", it) }
        allowedPermissions.forEach { rejectWildcard("allowedPermissions", it) }
    }

    /** Converts this sovereign configuration into a [PolicyConfiguration] for the runtime. */
    fun toPolicyConfiguration(): PolicyConfiguration = PolicyConfiguration.secure().copy(
        allowedTools = allowedTools,
        allowedModels = allowedModels,
        allowedProviders = allowedProviders,
        allowedFallbackProviders = allowedFallbackProviders,
        allowedPermissions = allowedPermissions,
        allowLegacyToolsWithoutSecurityMetadata = false,
        allowWorkflowResume = true,
        requireApprovalForRiskLevel = setOf(RiskLevel.HIGH, RiskLevel.CRITICAL),
        providerRouting = ProviderRoutingConfiguration(
            providerZones = providerZones,
            rules = ProviderRoutingConfiguration.sovereignDefaults(),
            enabled = true,
        ),
    )

    companion object {
        private fun validateNonBlank(label: String, value: String) {
            require(value.isNotBlank()) { "$label must not be blank" }
            require(value == value.trim()) { "$label must not have surrounding whitespace" }
        }
    }
}
