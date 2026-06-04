package dev.tramai.core.approval

class IllegalApprovalTransitionException(
    val approvalId: String,
    val from: ApprovalStatus,
    val to: ApprovalStatus,
    val reason: String,
) : IllegalArgumentException(
    "Illegal approval transition for '$approvalId': $from -> $to - $reason"
)
