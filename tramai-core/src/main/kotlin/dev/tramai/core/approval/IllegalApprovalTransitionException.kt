package dev.tramai.core.approval

class IllegalApprovalTransitionException(
    approvalId: String,
    from: ApprovalStatus,
    to: ApprovalStatus,
    reason: String,
) : IllegalArgumentException(
    "Illegal approval transition for '$approvalId': $from -> $to - $reason"
)
