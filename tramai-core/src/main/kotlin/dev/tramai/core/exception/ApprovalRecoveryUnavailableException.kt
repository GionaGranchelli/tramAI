package dev.tramai.core.exception

/**
 * Raised when the recovery coordinator cannot determine stale-claim availability
 * due to a dependency failure (store read, infrastructure error, etc.).
 *
 * Has a fixed safe message and no cause chain.
 */
class ApprovalRecoveryUnavailableException() : ApprovalException(
    "Approval recovery is unavailable"
) {
    constructor(cause: Throwable?) : this()
}
