package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalDecisionValidator
import dev.tramai.core.approval.ApprovalRequest

object AllowAnyApprovalDecisionValidator : ApprovalDecisionValidator {
    override fun validate(request: ApprovalRequest, consumedBy: String) = Unit
}
