package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalDecisionValidator

val RequireDistinctRequesterAndConsumer: ApprovalDecisionValidator = ApprovalDecisionValidator { request, consumedBy ->
    require(request.requestedBy != consumedBy) {
        "Approval requester and consumer must be different actors"
    }
}
