package dev.tramai.orchestration

/**
 * Raised when a workflow exceeds an explicit execution bound.
 */
class WorkflowLimitExceededException(
    message: String,
) : RuntimeException(message)

/**
 * Raised when a branch step selects a branch that is not configured.
 */
class WorkflowBranchSelectionException(
    message: String,
) : RuntimeException(message)

/**
 * Raised when an explicit workflow gate rejects further execution.
 */
class WorkflowGateRejectedException(
    message: String,
) : RuntimeException(message)

/**
 * Raised when a workflow has durably checkpointed itself and yielded execution
 * so it can be resumed by an external scheduler or caller.
 */
class WorkflowSuspendedException(
    message: String,
) : RuntimeException(message)

/**
 * Result returned by a first-class gate step.
 */
data class GateDecision(
    val allowed: Boolean,
    val reason: String? = null,
) {
    init {
        require(allowed || !reason.isNullOrBlank()) {
            "GateDecision.reason must be provided when a gate rejects execution"
        }
    }

    companion object {
        fun allow(): GateDecision = GateDecision(allowed = true)

        fun reject(reason: String): GateDecision = GateDecision(
            allowed = false,
            reason = reason,
        )
    }
}
