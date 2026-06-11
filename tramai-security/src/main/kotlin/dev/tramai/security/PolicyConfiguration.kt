package dev.tramai.security

import dev.tramai.core.policy.DataClassification
import dev.tramai.core.policy.RiskLevel

/**
 * Configuration for [DefaultPolicyEngine].
 *
 * All sets are empty by default, producing full deny-by-default behavior.
 * Add entries to explicitly allow specific tools, models, or providers.
 *
 * ## Classification-Aware Provider Routing (Epic 2.3 / 2.4)
 *
 * The [providerRouting] field provides a matrix-based routing model where
 * each [DataClassification] maps to allowed [ProviderTrustZone] sets for
 * primary and fallback invocation. When [ProviderRoutingConfiguration.enabled]
 * is true, this matrix is authoritative for classification-based routing.
 *
 * **Backward compatibility:** [trustedLocalProviders] and
 * [allowCloudForClassifications] are retained as deprecated compatibility
 * fields. When [providerRouting.enabled] is true, the matrix overrides them.
 * When false (default), the legacy classification egress logic in
 * [DefaultPolicyEngine] uses the old fields.
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
    /**
     * Data classifications permitted for non-local providers.
     *
     * @deprecated Use [providerRouting] with classification-aware routing matrix instead.
     * When [providerRouting.enabled] is true, this field is ignored for routing decisions.
     */
    @Deprecated("Use providerRouting instead", ReplaceWith("providerRouting"))
    val allowCloudForClassifications: Set<DataClassification> = setOf(DataClassification.PUBLIC),
    /**
     * Providers treated as inside the local trust boundary.
     *
     * @deprecated Use [providerRouting] with [ProviderTrustZone.LOCAL] zone mapping instead.
     * When [providerRouting.enabled] is true, this field is ignored for routing decisions.
     */
    @Deprecated("Use providerRouting instead", ReplaceWith("providerRouting"))
    val trustedLocalProviders: Set<String> = emptySet(),
    /**
     * Classification-aware provider routing matrix.
     *
     * When [ProviderRoutingConfiguration.enabled] is true, this matrix
     * overrides the legacy [trustedLocalProviders] and
     * [allowCloudForClassifications] fields for routing decisions.
     * Disabled by default for backward compatibility.
     */
    /** Whether workflow resume is allowed (secure default: denied). */
    val allowWorkflowResume: Boolean = false,
    val providerRouting: ProviderRoutingConfiguration = ProviderRoutingConfiguration(),
) {
    companion object {
        /**
         * Permissive preset for 0.4.x migration and testing.
         * Wildcard allowlists bypass most registry checks.
         * CRITICAL-risk tools still require approval.
         * RESTRICTED data remains limited to trusted local providers when
         * classification context is available.
         *
         * The [providerRouting] matrix is **not** enabled in preview mode
         * to preserve 0.4.x behavior. Legacy [trustedLocalProviders] and
         * [allowCloudForClassifications] remain authoritative.
         */
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
            providerRouting = ProviderRoutingConfiguration(
                providerZones = mapOf(
                    "ollama" to ProviderTrustZone.LOCAL,
                    "vllm" to ProviderTrustZone.LOCAL,
                    "llama.cpp" to ProviderTrustZone.LOCAL,
                    "local" to ProviderTrustZone.LOCAL,
                ),
                enabled = false,
            ),
        )

        /** Deny-by-default with no exclusions. */
        fun secure() = PolicyConfiguration()
    }
}
