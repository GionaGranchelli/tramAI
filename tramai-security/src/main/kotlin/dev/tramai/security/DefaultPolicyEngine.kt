package dev.tramai.security

import dev.tramai.core.policy.*

/**
 * Deny-by-default [PolicyEngine] implementation.
 *
 * Every enforcement point is evaluated against [PolicyConfiguration].
 * Unknown tools, models, and providers are denied. HIGH/CRITICAL-risk
 * tools and tools with non-AUTO approval modes require human approval.
 *
 * ## Classification-aware provider routing (Epic 2.3 / 2.4)
 *
 * When [PolicyConfiguration.providerRouting] is enabled, the routing
 * matrix determines which provider trust zones are allowed for each
 * [DataClassification] at primary invocation and fallback. This
 * replaces legacy classification-egress fields when enabled;
 * registry allowlists remain independent.
 *
 * When the matrix is disabled (default), the legacy classification
 * egress logic in [evaluateClassificationEgress] is used.
 */
class DefaultPolicyEngine(
    private val config: PolicyConfiguration,
) : PolicyEngine {

    override suspend fun evaluate(context: PolicyContext): PolicyDecision = when (context.enforcementPoint) {
        EnforcementPoint.BEFORE_PROVIDER_RESOLUTION -> evaluateProviderResolution(context)
        EnforcementPoint.BEFORE_PROVIDER_INVOCATION -> evaluateProviderInvocation(context)
        EnforcementPoint.BEFORE_FALLBACK -> evaluateFallback(context)
        EnforcementPoint.BEFORE_TOOL_EXPOSURE -> evaluateToolExposure(context)
        EnforcementPoint.BEFORE_TOOL_EXECUTION -> evaluateToolExecution(context)
        EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION -> PolicyDecision.Allow
        EnforcementPoint.BEFORE_RESPONSE_RETURN -> evaluateResponseReturn(context)
        EnforcementPoint.BEFORE_WORKFLOW_RESUME ->
            if (config.allowWorkflowResume) {
                PolicyDecision.Allow
            } else {
                PolicyDecision.Deny(
                    "Workflow resume is not enabled",
                    "workflow-resume-disabled",
                )
            }
    }

    // ─── Provider resolution ───────────────────────────────────────────────

    private fun evaluateProviderResolution(ctx: PolicyContext): PolicyDecision {
        val modelName = ctx.modelName
        if (modelName == null) {
            return PolicyDecision.Deny(
                "Provider resolution requires a model name",
                "model-name-missing",
            )
        }
        if (modelName !in config.allowedModels && "*" !in config.allowedModels) {
            return PolicyDecision.Deny(
                "Model '$modelName' is not in the allowed-models registry",
                "unknown-model",
            )
        }
        return PolicyDecision.Allow
    }

    // ─── Provider invocation ───────────────────────────────────────────────

    private fun evaluateProviderInvocation(ctx: PolicyContext): PolicyDecision {
        val providerId = ctx.providerId

        if (!config.providerRouting.enabled) {
            // Legacy mode: classification egress first, then registry checks
            evaluateClassificationEgress(
                classification = ctx.dataClassification,
                providerId = providerId,
            )?.let { return it }

            if (providerId == null) {
                return PolicyDecision.Deny(
                    "Provider invocation requires a provider ID",
                    "provider-id-missing",
                )
            }
            if (providerId !in config.allowedProviders && "*" !in config.allowedProviders) {
                return PolicyDecision.Deny(
                    "Provider '$providerId' is not in the allowed-providers registry",
                    "unknown-provider",
                )
            }
            return PolicyDecision.Allow
        }

        // Matrix-enabled mode: provider ID check -> allowlist -> routing matrix (Epic 2.3)
        if (providerId == null) {
            return PolicyDecision.Deny(
                "Provider invocation requires a provider ID",
                "provider-id-missing",
            )
        }
        if (providerId !in config.allowedProviders && "*" !in config.allowedProviders) {
            return PolicyDecision.Deny(
                "Provider '$providerId' is not in the allowed-providers registry",
                "unknown-provider",
            )
        }
        evaluateProviderRouting(
            classification = ctx.dataClassification,
            providerId = providerId,
            isFallback = false,
        )?.let { return it }

        return PolicyDecision.Allow
    }

    // ─── Fallback ───────────────────────────────────────────────────────────

    private fun evaluateFallback(ctx: PolicyContext): PolicyDecision {
        val fallbackId = ctx.fallbackProviderId

        if (!config.providerRouting.enabled) {
            // Legacy mode: registry checks only, no routing matrix
            if (fallbackId == null) {
                return PolicyDecision.Deny(
                    "No fallback provider specified",
                    "no-fallback-provider",
                )
            }
            if (fallbackId !in config.allowedFallbackProviders && "*" !in config.allowedFallbackProviders) {
                return PolicyDecision.Deny(
                    "Fallback provider '$fallbackId' is not authorized",
                    "fallback-not-authorized",
                )
            }
            return PolicyDecision.Allow
        }

        // Matrix-enabled mode: registry checks + routing matrix (Epic 2.4)
        if (fallbackId == null) {
            return PolicyDecision.Deny(
                "No fallback provider specified",
                "no-fallback-provider",
            )
        }
        if (fallbackId !in config.allowedFallbackProviders && "*" !in config.allowedFallbackProviders) {
            return PolicyDecision.Deny(
                "Fallback provider '$fallbackId' is not authorized",
                "fallback-not-authorized",
            )
        }
        // 3. Classification-aware routing matrix (Epic 2.4)
        evaluateProviderRouting(
            classification = ctx.dataClassification,
            providerId = fallbackId,
            isFallback = true,
        )?.let { return it }

        return PolicyDecision.Allow
    }

    // ─── Tool exposure ─────────────────────────────────────────────────────

    private fun evaluateToolExposure(ctx: PolicyContext): PolicyDecision {
        val toolName = ctx.toolName
        if (toolName == null) {
            return PolicyDecision.Deny(
                "Tool exposure evaluated without a tool name",
                "tool-exposure-no-name",
            )
        }
        if (toolName !in config.allowedTools && "*" !in config.allowedTools) {
            return PolicyDecision.Deny(
                "Tool '$toolName' is not in the allowed-tools registry",
                "unknown-tool",
            )
        }

        val metadata = ctx.toolSecurity
        if (!config.allowLegacyToolsWithoutSecurityMetadata) {
            if (metadata == null) {
                return PolicyDecision.Deny(
                    "Tool '$toolName' has no security metadata",
                    "tool-metadata-missing",
                )
            }
            if (metadata.compatibilityMode == CompatibilityMode.LEGACY_PERMISSIVE) {
                return PolicyDecision.Deny(
                    "Tool '$toolName' uses LEGACY_PERMISSIVE compatibility mode",
                    "tool-metadata-legacy-permissive",
                )
            }
        }

        if (metadata != null && metadata.permission !in config.allowedPermissions && "*" !in config.allowedPermissions) {
            return PolicyDecision.Deny(
                "Tool '$toolName' requires permission '${metadata.permission}' which is not granted for exposure",
                "tool-exposure-permission-denied",
            )
        }

        return PolicyDecision.Allow
    }

    // ─── Tool execution ─────────────────────────────────────────────────────

    private fun evaluateToolExecution(ctx: PolicyContext): PolicyDecision {
        val toolName = ctx.toolName
        if (toolName == null) {
            return PolicyDecision.Deny(
                "Tool execution evaluated without a tool name",
                "tool-execution-no-name",
            )
        }
        if (toolName !in config.allowedTools && "*" !in config.allowedTools) {
            return PolicyDecision.Deny(
                "Tool '$toolName' is not in the allowed-tools registry",
                "unknown-tool",
            )
        }

        val metadata = ctx.toolSecurity
        if (!config.allowLegacyToolsWithoutSecurityMetadata) {
            if (metadata == null) {
                return PolicyDecision.Deny(
                    "Tool '$toolName' has no security metadata",
                    "tool-metadata-missing",
                )
            }
            if (metadata.compatibilityMode == CompatibilityMode.LEGACY_PERMISSIVE) {
                return PolicyDecision.Deny(
                    "Tool '$toolName' uses LEGACY_PERMISSIVE compatibility mode",
                    "tool-metadata-legacy-permissive",
                )
            }
        }

        if (metadata != null) {
            // Check permission before risk-based approval requirements.
            if (metadata.permission !in config.allowedPermissions && "*" !in config.allowedPermissions) {
                return PolicyDecision.Deny(
                    "Tool '$toolName' requires permission '${metadata.permission}' which is not granted",
                    "tool-permission-denied",
                )
            }

            val risk = metadata.risk
            if (metadata.approval != ApprovalMode.AUTO || risk in config.requireApprovalForRiskLevel) {
                return PolicyDecision.RequireApproval(
                    ApprovalRequirement(
                        toolName = toolName,
                        argumentsDigest = "", // Engine derives and binds the actual digest before creating the approval challenge.
                        reason = "Tool '$toolName' (risk=$risk, approval=${metadata.approval}) requires human approval",
                        timeoutMillis = 30_000,
                    ),
                )
            }
        }

        return PolicyDecision.Allow
    }

    // ─── Response return ────────────────────────────────────────────────────

    private fun evaluateResponseReturn(ctx: PolicyContext): PolicyDecision {
        if (!config.providerRouting.enabled) {
            // Legacy mode: only classification egress
            return evaluateClassificationEgress(
                classification = ctx.dataClassification,
                providerId = ctx.providerId,
            ) ?: PolicyDecision.Allow
        }
        // Matrix-enabled mode: provider routing check (Epic 2.3)
        evaluateProviderRouting(
            classification = ctx.dataClassification,
            providerId = ctx.providerId,
            isFallback = false,
        )?.let { return it }
        return PolicyDecision.Allow
    }

    // ─── Classification-aware provider routing matrix (Epic 2.3 / 2.4) ─────

    /**
     * Evaluates the classification-aware routing matrix for the given
     * [classification], [providerId], and fallback context.
     *
     * Returns a [PolicyDecision.Deny] when routing is violated, or `null`
     * to indicate the check is inconclusive (either no classification is
     * present, or routing is disabled) — the caller should then fall back
     * to legacy checks.
     *
     * **Reason codes:**
     * - `classification-provider-missing`: classified request without provider ID
     * - `provider-zone-missing`: classified request where the provider has no zone mapping
     * - `classification-routing-rule-missing`: classification has no routing rule defined
     * - `classification-routing-blocked`: provider zone not in [allowedZones]
     * - `classification-fallback-blocked`: provider zone not in [allowedFallbackZones]
     */
    private fun evaluateProviderRouting(
        classification: DataClassification?,
        providerId: String?,
        isFallback: Boolean,
    ): PolicyDecision? {
        // Routing matrix disabled — defer to legacy checks
        if (!config.providerRouting.enabled) return null

        // No classification — preserve current registry behavior
        if (classification == null) return null

        // Classified request without a provider ID
        if (providerId == null) {
            return PolicyDecision.Deny(
                "Classified request ($classification) requires a provider ID for routing",
                "classification-provider-missing",
            )
        }

        val zone = config.providerRouting.providerZones[providerId]
        if (zone == null) {
            return PolicyDecision.Deny(
                "Provider '$providerId' has no trust zone configured for classification routing",
                "provider-zone-missing",
            )
        }

        val rule = config.providerRouting.rules[classification]
        if (rule == null) {
            return PolicyDecision.Deny(
                "No routing rule defined for classification '$classification'",
                "classification-routing-rule-missing",
            )
        }

        val allowedSet = if (isFallback) rule.allowedFallbackZones else rule.allowedZones
        val reasonCode = if (isFallback) "classification-fallback-blocked" else "classification-routing-blocked"

        if (zone !in allowedSet) {
            val direction = if (isFallback) "fallback" else "invocation"
            return PolicyDecision.Deny(
                "Provider '$providerId' (zone=$zone) is not allowed for $direction of '$classification' data",
                reasonCode,
            )
        }

        return null
    }

    // ─── Legacy classification egress ───────────────────────────────────────

    private fun evaluateClassificationEgress(
        classification: DataClassification?,
        providerId: String?,
    ): PolicyDecision? {
        if (classification == null) return null

        if (providerId == null) {
            return PolicyDecision.Deny(
                "Classified request ($classification) requires a provider ID for egress control",
                "classification-provider-missing",
            )
        }

        if (classification == DataClassification.RESTRICTED) {
            if (providerId !in config.trustedLocalProviders) {
                return PolicyDecision.Deny(
                    "RESTRICTED data may not be sent to provider '$providerId'",
                    "restricted-data-egress-blocked",
                )
            }
            return null
        }

        if (providerId in config.trustedLocalProviders) return null

        if (classification !in config.allowCloudForClassifications) {
            return PolicyDecision.Deny(
                "Data classification '$classification' is not allowed for provider '$providerId'",
                "classification-egress-blocked",
            )
        }

        return null
    }
}
