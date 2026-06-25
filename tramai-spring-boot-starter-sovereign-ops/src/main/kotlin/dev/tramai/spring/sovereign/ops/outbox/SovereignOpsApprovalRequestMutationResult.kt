package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.gateway.ResumeToken

sealed interface SovereignOpsApprovalRequestMutationResult {
    data class Created(
        val approvalId: String,
        val correlationId: String,
        val resumeToken: ResumeToken,
    ) : SovereignOpsApprovalRequestMutationResult

    data class Existing(
        val approval: ApprovalRequest,
    ) : SovereignOpsApprovalRequestMutationResult
}
