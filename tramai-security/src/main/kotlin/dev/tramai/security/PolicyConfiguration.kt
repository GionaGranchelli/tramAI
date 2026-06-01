package dev.tramai.security

import dev.tramai.core.policy.DataClassification
import dev.tramai.core.policy.RiskLevel

/**
 * Configuration for [DefaultPolicyEngine].
 *
 * All sets are empty by default, producing full deny-by-default behavior.
 * Add entries to explicitly allow specific tools, models, or providers.
 */
data class PolicyConfiguration(
    /** Tools whose execution is permitted. Empty = deny all tools. */
    val allowedTools: Set<String> = emptySet(),
    /** Models that may be resolved and invoked. Empty = deny all models. */
    val allowedModels: Set<String> = emptySet(),
    /** Providers that may be used. Empty = deny all providers. */
    val allowedProviders: Set<String> = emptySet(),
    /** Providers that may be used as fallback destinations. */
    val allowedFallbackProviders: Set<String> = emptySet(),
    /** Tool permissions that are granted. Checked in addition to [allowedTools]. */
    val allowedPermissions: Set<String> = emptySet(),
    /** Whether secure-mode metadata requirements are relaxed for legacy tools. */
    val allowLegacyToolsWithoutSecurityMetadata: Boolean = false,
    /** Risk levels that require human approval before tool execution. */
    val requireApprovalForRiskLevel: Set<RiskLevel> = setOf(RiskLevel.HIGH, RiskLevel.CRITICAL),
    /** Data classifications permitted for non-local providers. */
    val allowCloudForClassifications: Set<DataClassification> = setOf(DataClassification.PUBLIC),
    /** Providers treated as inside the local trust boundary. */
    val trustedLocalProviders: Set<String> = emptySet(),
) {
    companion object {
        /** Fully permissive preset — only for 0.4.x preview / testing. */
        fun preview() = PolicyConfiguration(
            allowedTools = setOf("*"),
            allowedModels = setOf("*"),
            allowedProviders = setOf("*"),
            allowedFallbackProviders = setOf("*"),
            allowedPermissions = setOf("*"),
            allowLegacyToolsWithoutSecurityMetadata = true,
            requireApprovalForRiskLevel = setOf(RiskLevel.CRITICAL),
            allowCloudForClassifications = DataClassification.entries.toSet(),
            trustedLocalProviders = setOf("ollama", "vllm", "llama.cpp", "local"),
        )

        /** Deny-by-default with no exclusions. */
        fun secure() = PolicyConfiguration()
    }
}
