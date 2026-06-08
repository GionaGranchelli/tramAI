package dev.tramai.core.exception

/**
 * Raised when a tool execution during a resume workflow requires a new approval.
 *
 * In v1, nested approval (approval during a resumed workflow) is not supported.
 * The calling workflow must use the original approval challenge rather than
 * initiating a new approval cycle while another is already in progress.
 */
class NestedApprovalNotSupportedException(
    val approvalId: String,
    message: String,
) : TramaiException(message)
