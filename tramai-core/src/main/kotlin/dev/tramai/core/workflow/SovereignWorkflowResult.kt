package dev.tramai.core.workflow

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.AuditStreamId
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId

/**
 * Top-level result type for sovereign workflows that may suspend for human approval.
 *
 * This SPI is [Preview] — the API shape is usable but may evolve.
 * See [docs/architecture/sovereign-api-stability-boundary.md](../../../../../docs/architecture/sovereign-api-stability-boundary.md).
 */
sealed interface SovereignWorkflowResult<out T> {

    data class Completed<T>(
        val value: T,
    ) : SovereignWorkflowResult<T>

    data class SuspendedForApproval(
        val approvalId: ApprovalId,
        val workflowRunId: WorkflowRunId,
        val auditStreamId: AuditStreamId,
        val resumeToken: ResumeToken,
    ) : SovereignWorkflowResult<Nothing>

    data class Rejected(
        val reason: String,
    ) : SovereignWorkflowResult<Nothing>

    data class Expired(
        val reason: String,
    ) : SovereignWorkflowResult<Nothing>
}
