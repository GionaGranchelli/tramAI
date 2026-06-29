package dev.tramai.core.workflow

import dev.tramai.core.approval.gateway.ApprovalRequestResult

/**
 * Maps a Preview [ApprovalRequestResult] into a workflow-level [SovereignWorkflowResult].
 *
 * Application workflows use this to avoid repeating the sealed-class mapping
 * from gateway outcomes to workflow-level results.
 *
 * ### Safety
 *
 * The [approvedValue] lambda is **only** invoked for [ApprovalRequestResult.AlreadyApproved].
 * Terminal states ([Suspended], [AlreadyDenied], [Expired]) never execute the lambda,
 * preventing accidental side effects.
 *
 * ### Usage
 *
 * ```kotlin
 * return gateway.requestApproval(
 *     subject = ApprovalSubject(input.claimId),
 *     recommendation = ApprovalRecommendation(
 *         type = "claim-triage",
 *         summary = "Claim requires medical review",
 *     ),
 *     requiredRole = ApproverRole("medical-reviewer"),
 *     workflowRunId = WorkflowRunId(input.workflowRunId),
 * ).toWorkflowResult { "approved-continue" }
 * ```
 *
 * Preview API. See docs/architecture/sovereign-api-stability-boundary.md.
 */
inline fun <T> ApprovalRequestResult.toWorkflowResult(
    approvedValue: () -> T,
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
            SovereignWorkflowResult.Completed(approvedValue())

        is ApprovalRequestResult.AlreadyDenied ->
            SovereignWorkflowResult.Rejected(decision.reason)

        is ApprovalRequestResult.Expired ->
            SovereignWorkflowResult.Expired(reason)
    }
