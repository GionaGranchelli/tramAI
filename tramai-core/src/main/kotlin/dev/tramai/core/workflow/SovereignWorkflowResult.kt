package dev.tramai.core.workflow

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.AuditStreamId
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId

/**
 * Top-level result type for sovereign workflows that may suspend for human approval.
 *
 * Preview API. See docs/architecture/sovereign-api-stability-boundary.md.
 */
sealed interface SovereignWorkflowResult<out T> {

    data class Completed<T>(
        val value: T,
    ) : SovereignWorkflowResult<T>

    data class SuspendedForApproval(
        @get:JvmName("getApprovalId")
        val approvalId: ApprovalId,
        @get:JvmName("getWorkflowRunId")
        val workflowRunId: WorkflowRunId,
        @get:JvmName("getAuditStreamId")
        val auditStreamId: AuditStreamId,
        @get:JvmName("getResumeToken")
        val resumeToken: ResumeToken,
    ) : SovereignWorkflowResult<Nothing>

    data class Rejected(
        val reason: String,
    ) : SovereignWorkflowResult<Nothing>

    data class Expired(
        val reason: String,
    ) : SovereignWorkflowResult<Nothing>
}
