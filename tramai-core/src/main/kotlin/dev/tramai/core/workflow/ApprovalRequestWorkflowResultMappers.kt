package dev.tramai.core.workflow

import dev.tramai.core.approval.gateway.ApprovalRequestResult
import dev.tramai.core.approval.gateway.HumanApprovalDecision

/**
 * Maps a Preview [ApprovalRequestResult] into a workflow-level [SovereignWorkflowResult].
 *
 * Application workflows use this to avoid repeating the sealed-class mapping
 * from gateway outcomes to workflow-level results.
 *
 * ### Safety
 *
 * The [approvedValue] lambda receives the [HumanApprovalDecision.Approved] decision
 * and is **only** invoked for [ApprovalRequestResult.AlreadyApproved].
 * Terminal states ([Suspended], [AlreadyDenied], [Expired]) never execute the lambda,
 * preventing accidental side effects.
 *
 * ### Simple usage (decision ignored)
 *
 * ```kotlin
 * return gateway.requestApproval(...)
 *     .toWorkflowResult { "approved-continue" }
 * ```
 *
 * ### Decision-aware usage
 *
 * ```kotlin
 * return gateway.requestApproval(...)
 *     .toWorkflowResult { decision ->
 *         ClaimRecommendation.Approved(
 *             approvedBy = decision.decidedBy,
 *             approvedAt = decision.decidedAt,
 *         )
 *     }
 * ```
 *
 * Preview API. See docs/architecture/sovereign-api-stability-boundary.md.
 */
inline fun <T> ApprovalRequestResult.toWorkflowResult(
    approvedValue: (HumanApprovalDecision.Approved) -> T,
): SovereignWorkflowResult<T> =
    when (this) {
        is ApprovalRequestResult.Suspended ->
            SovereignWorkflowResult.SuspendedForApproval(
                approvalId = approvalId,
                workflowRunId = workflowRunId,
                auditStreamId = auditStreamId,
                resumeToken = resumeToken,
            )

        is ApprovalRequestResult.AlreadyApproved ->
            SovereignWorkflowResult.Completed(approvedValue(decision))

        is ApprovalRequestResult.AlreadyDenied ->
            SovereignWorkflowResult.Rejected(decision.reason)

        is ApprovalRequestResult.Expired ->
            SovereignWorkflowResult.Expired(reason)
    }
