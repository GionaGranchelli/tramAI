package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalDecisionValidator

val AllowAnyApprovalDecisionValidator: ApprovalDecisionValidator = ApprovalDecisionValidator { _, _ -> Unit }
