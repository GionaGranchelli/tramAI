package dev.tramai.core.exception

class ApprovalRecoveryAuditUnavailableException : RuntimeException {
    constructor() : super("Approval recovery audit is unavailable")
    constructor(cause: Throwable?) : this()
}
