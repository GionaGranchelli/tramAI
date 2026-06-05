package dev.tramai.core.policy

/**
 * Result of a single [PolicyEngine.evaluate] call.
 */
sealed interface PolicyDecision {
    /** The operation is allowed to proceed. */
    data object Allow : PolicyDecision

    /** The operation is denied with a reason code. */
    data class Deny(
        val reason: String,
        val reasonCode: String,
    ) : PolicyDecision

    /**
     * The operation requires human approval before it can proceed.
     *
     * The [requirement] carries binding metadata (tool, arguments, timeout).
     * The approval subsystem generates the actual approval ID, nonce, and expiry.
     */
    data class RequireApproval(
        val requirement: ApprovalRequirement,
    ) : PolicyDecision
}

/**
 * Metadata that binds an approval requirement to the exact action.
 *
 * The [PolicyEngine] returns this — it does NOT generate security tokens.
 * The approval subsystem creates [dev.tramai.core.approval.ApprovalRequest] with
 * generated identifiers.
 */
data class ApprovalRequirement(
    /** Name of the tool requiring approval. */
    val toolName: String,
    /** Digest of the exact tool arguments being approved. */
    val argumentsDigest: String,
    /** Human-readable reason for the approval requirement. */
    val reason: String,
    /** Maximum time in milliseconds before the requirement auto-expires. */
    val timeoutMillis: Long,
)
