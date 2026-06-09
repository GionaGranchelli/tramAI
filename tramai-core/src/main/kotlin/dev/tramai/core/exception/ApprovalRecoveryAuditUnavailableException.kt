package dev.tramai.core.exception

class ApprovalRecoveryAuditUnavailableException() : ApprovalException(
    "Approval recovery audit is unavailable"
) {
    constructor(cause: Throwable?) : this()
}
