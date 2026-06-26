package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.gateway.ApproverRole

/**
 * Policy interface for authorizing approval decisions.
 *
 * Implementations decide whether a given actor is allowed to approve or deny
 * a specific approval request.
 */
fun interface ApprovalDecisionAuthorizer {

    /**
     * Check whether [actorId] with [actorRole] may perform [decisionType]
     * on the given [approval].
     *
     * @return `true` if the decision is allowed, `false` to reject.
     */
    fun canDecide(
        approval: ApprovalRequest,
        actorId: String,
        actorRole: ApproverRole,
        decisionType: ApprovalDecisionType,
    ): Boolean
}

/** Type of approval decision for authorization. */
enum class ApprovalDecisionType { APPROVE, DENY }

/**
 * Permissive default authorizer — allows any actor to decide any approval.
 * Intended for Preview; replace with real authorization for production.
 */
object AllowAllApprovalDecisionAuthorizer : ApprovalDecisionAuthorizer {
    override fun canDecide(
        approval: ApprovalRequest,
        actorId: String,
        actorRole: ApproverRole,
        decisionType: ApprovalDecisionType,
    ): Boolean = true
}
