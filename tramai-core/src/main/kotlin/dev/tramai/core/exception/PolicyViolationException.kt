package dev.tramai.core.exception

import dev.tramai.core.policy.PolicyDecision

/**
 * Raised when the policy engine denies an operation.
 */
class PolicyViolationException(
    val decision: PolicyDecision.Deny,
) : TramaiException("Policy violation: ${decision.reason} (code: ${decision.reasonCode})")
