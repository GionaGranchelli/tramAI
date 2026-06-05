package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalDecisionValidator
import dev.tramai.core.approval.ApprovalRequest

object RequireDistinctRequesterAndConsumer : ApprovalDecisionValidator {
    override fun validate(request: ApprovalRequest, consumedBy: String) {
        require(request.requestedBy != consumedBy) {
            "Approval requester and consumer must be different actors"
        }
    }
}
