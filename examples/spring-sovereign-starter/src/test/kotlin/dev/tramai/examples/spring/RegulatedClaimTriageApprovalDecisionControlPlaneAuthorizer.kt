package dev.tramai.examples.spring

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.spring.sovereign.ops.ApprovalDecisionAuthorizer
import dev.tramai.spring.sovereign.ops.ApprovalDecisionType

/**
 * Example authorizer for the regulated claim triage scenario.
 * Allows decisions from known reviewer actors.
 * For Preview: permits all decisions (permissive default).
 */
class RegulatedClaimTriageApprovalDecisionControlPlaneAuthorizer : ApprovalDecisionAuthorizer {
    override fun canDecide(
        approval: ApprovalRequest,
        actorId: String,
        actorRole: ApproverRole,
        decisionType: ApprovalDecisionType,
    ): Boolean = true
}
