package dev.tramai.core.exception

import dev.tramai.core.policy.ApprovalRequirement

/**
 * Raised when [dev.tramai.core.policy.PolicyDecision.RequireApproval] is returned by the policy engine.
 *
 * This is a placeholder in the 0.4.x preview. Approval persistence, nonce generation,
 * suspension, and resume will be implemented in a later PR.
 */
class ApprovalRequiredException(
    val requirement: ApprovalRequirement,
) : TramaiException(
    "Approval required for tool '${requirement.toolName}': ${requirement.reason} " +
        "(timeout: ${requirement.timeoutMillis}ms). " +
        "Approval suspension/resume is not yet implemented."
)
