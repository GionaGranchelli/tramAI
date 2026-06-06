package dev.tramai.core.approval

fun interface ApprovalDecisionValidator {
    fun validate(request: ApprovalRequest, consumedBy: String)
}
