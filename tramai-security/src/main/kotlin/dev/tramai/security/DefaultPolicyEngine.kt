package dev.tramai.security

import dev.tramai.core.policy.*

/**
 * Deny-by-default [PolicyEngine] implementation.
 *
 * Every enforcement point is evaluated against [PolicyConfiguration].
 * Unknown tools, models, and providers are denied. HIGH/CRITICAL-risk
 * tools require human approval. RESTRICTED data is blocked from cloud providers.
 *
 * This engine is intended as the secure default for 1.0+. In 0.4.x preview,
 * pass an explicit [PolicyConfiguration] or use [PolicyConfiguration.preview].
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
        EnforcementPoint.BEFORE_WORKFLOW_RESUME -> PolicyDecision.Deny(
            "Workflow resume enforcement is not yet implemented",
            "workflow-resume-unimplemented",
        )
    }

    // ─── Provider resolution ───────────────────────────────────────────────

    private fun evaluateProviderResolution(ctx: PolicyContext): PolicyDecision {
        val modelName = ctx.modelName
        if (modelName != null && modelName !in config.allowedModels && "*" !in config.allowedModels) {
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
        if (providerId != null && providerId !in config.allowedProviders && "*" !in config.allowedProviders) {
            return PolicyDecision.Deny(
                "Provider '$providerId' is not in the allowed-providers registry",
                "unknown-provider",
            )
        }
        return PolicyDecision.Allow
    }

    // ─── Fallback ───────────────────────────────────────────────────────────

    private fun evaluateFallback(ctx: PolicyContext): PolicyDecision {
        val fallbackId = ctx.fallbackProviderId
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
        if (metadata != null) {
            val risk = metadata.risk
            if (risk in config.requireApprovalForRiskLevel) {
                return PolicyDecision.Deny(
                    "Tool '$toolName' (risk=$risk) cannot be exposed — requires approval",
                    "tool-exposure-risk-denied",
                )
            }
            if (metadata.permission !in config.allowedPermissions && "*" !in config.allowedPermissions) {
                return PolicyDecision.Deny(
                    "Tool '$toolName' requires permission '${metadata.permission}' which is not granted for exposure",
                    "tool-exposure-permission-denied",
                )
            }
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
        if (metadata != null) {
            // Check risk-based approval requirement
            val risk = metadata.risk
            if (risk in config.requireApprovalForRiskLevel) {
                return PolicyDecision.RequireApproval(
                    ApprovalRequirement(
                        toolName = toolName,
                        argumentsDigest = "", // TODO(phase-2): populate from actual tool arguments when approval subsystem lands
                        reason = "Tool '$toolName' (risk=$risk) requires human approval",
                        timeoutMillis = 30_000,
                    ),
                )
            }

            // Check permission
            if (metadata.permission !in config.allowedPermissions && "*" !in config.allowedPermissions) {
                return PolicyDecision.Deny(
                    "Tool '$toolName' requires permission '${metadata.permission}' which is not granted",
                    "tool-permission-denied",
                )
            }
        }

        return PolicyDecision.Allow
    }

    // ─── Response return ────────────────────────────────────────────────────

    private fun evaluateResponseReturn(ctx: PolicyContext): PolicyDecision {
        val classification = ctx.dataClassification ?: return PolicyDecision.Allow

        if (classification == DataClassification.RESTRICTED) {
            val providerId = ctx.providerId
            // RESTRICTED data must not leave local trust boundary
            if (providerId != null && providerId !in LOCAL_PROVIDER_IDS) {
                return PolicyDecision.Deny(
                    "RESTRICTED data may not be sent to provider '$providerId'",
                    "restricted-data-egress-blocked",
                )
            }
        }

        if (classification != DataClassification.PUBLIC &&
            classification !in config.allowCloudForClassifications
        ) {
            val providerId = ctx.providerId
            val isLocal = providerId == null || providerId in LOCAL_PROVIDER_IDS
            if (!isLocal) {
                return PolicyDecision.Deny(
                    "Data classification '$classification' is not allowed for provider '$providerId'",
                    "classification-egress-blocked",
                )
            }
        }

        return PolicyDecision.Allow
    }

    companion object {
        /** Provider IDs that are considered local/within trust boundary. */
        private val LOCAL_PROVIDER_IDS = setOf("ollama", "vllm", "llama.cpp", "local")
    }
}
