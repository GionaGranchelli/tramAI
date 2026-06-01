package dev.tramai.engine

import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine

/**
 * Compatibility [PolicyEngine] that allows all operations.
 *
 * Used in 0.4.x when no explicit [PolicyEngine] is configured.
 * Logs one migration warning on first use. Will be removed or replaced
 * in TramAI Enterprise 1.0.
 */
object LegacyPermissivePolicyEngine : PolicyEngine {
    override suspend fun evaluate(context: PolicyContext): PolicyDecision =
        PolicyDecision.Allow
}
