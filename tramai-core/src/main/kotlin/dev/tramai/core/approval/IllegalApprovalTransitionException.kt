package dev.tramai.core.approval

import dev.tramai.core.exception.TramaiException

class IllegalApprovalTransitionException(
    val approvalId: String,
    val from: ApprovalStatus,
    val to: ApprovalStatus,
    val reason: String,
) : TramaiException(
    "Illegal approval transition for '$approvalId': $from -> $to - $reason"
)
