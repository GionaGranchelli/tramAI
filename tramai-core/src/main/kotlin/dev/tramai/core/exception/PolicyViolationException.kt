package dev.tramai.core.exception

/**
 * Raised when the policy engine denies an operation.
 */
class PolicyViolationException(
    val reason: String,
    val reasonCode: String,
) : TramaiException("Policy violation: $reason (code: $reasonCode)")
