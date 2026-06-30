@file:JvmName("ApprovalWorkflowResults")

package dev.tramai.core.workflow

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalRequestResult
import dev.tramai.core.approval.gateway.AuditStreamId
import dev.tramai.core.approval.gateway.HumanApprovalDecision
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import java.time.Instant

/**
 * Java-friendly entry point for mapping [ApprovalRequestResult] to [SovereignWorkflowResult].
 *
 * Kotlin users should prefer the [ApprovalRequestResult.toWorkflowResult] extension function.
 * Java users call [fromApprovalRequestResult].
 *
 * ### Example (Java)
 *
 * ```java
 * HumanApprovalDecision.Approved decision = HumanApprovalDecisions.approved(
 *     "approval-1", "reviewer-1", Instant.now()
 * );
 *
 * SovereignWorkflowResult<String> result =
 *     ApprovalWorkflowResults.fromApprovalRequestResult(
 *         ApprovalRequestResults.alreadyApproved(decision),
 *         approved -> approved.getDecidedBy() + ":" + approved.getComment()
 *     );
 * ```
 *
 * Preview API. See docs/architecture/sovereign-api-stability-boundary.md.
 */
fun <T> fromApprovalRequestResult(
    result: ApprovalRequestResult,
    approvedValue: (HumanApprovalDecision.Approved) -> T,
): SovereignWorkflowResult<T> =
    result.toWorkflowResult(approvedValue)

/**
 * Java-friendly factory for [ApprovalRequestResult] variants.
 *
 * Parameters use plain [String] — the underlying JVM type of inline value class wrappers —
 * so these methods have clean JVM names and no inline-value-class name mangling.
 *
 * Preview API.
 */
object ApprovalRequestResults {

    /**
     * Creates a [Suspended] result for the given identifiers.
     * @param approvalId the approval request identifier
     * @param workflowRunId the workflow run identifier
     * @param auditStreamId the audit stream identifier
     * @param resumeToken the resume credential token
     */
    @JvmStatic
    fun suspended(
        approvalId: String,
        workflowRunId: String,
        auditStreamId: String,
        resumeToken: String,
    ): ApprovalRequestResult.Suspended =
        ApprovalRequestResult.Suspended(
            ApprovalId(approvalId),
            WorkflowRunId(workflowRunId),
            AuditStreamId(auditStreamId),
            ResumeToken(resumeToken),
        )

    @JvmStatic
    fun alreadyApproved(decision: HumanApprovalDecision.Approved): ApprovalRequestResult.AlreadyApproved =
        ApprovalRequestResult.AlreadyApproved(decision)

    @JvmStatic
    fun alreadyDenied(decision: HumanApprovalDecision.Denied): ApprovalRequestResult.AlreadyDenied =
        ApprovalRequestResult.AlreadyDenied(decision)

    /**
     * Creates an [Expired] result.
     * @param approvalId the expired approval identifier
     */
    @JvmStatic
    fun expired(
        approvalId: String,
        expiredAt: Instant,
        reason: String,
    ): ApprovalRequestResult.Expired =
        ApprovalRequestResult.Expired(
            ApprovalId(approvalId),
            expiredAt,
            reason,
        )
}

/**
 * Java-friendly factory for [HumanApprovalDecision] variants.
 *
 * Parameters use plain [String] — the underlying JVM type of inline value class wrappers.
 *
 * Preview API.
 */
object HumanApprovalDecisions {

    /**
     * Creates an [Approved] decision.
     * @param approvalId the approval identifier
     */
    @JvmStatic
    @JvmOverloads
    fun approved(
        approvalId: String,
        decidedBy: String,
        decidedAt: Instant,
        comment: String? = null,
    ): HumanApprovalDecision.Approved =
        HumanApprovalDecision.Approved(
            ApprovalId(approvalId),
            decidedBy,
            decidedAt,
            comment,
        )

    /**
     * Creates a [Denied] decision.
     * @param approvalId the approval identifier
     */
    @JvmStatic
    @JvmOverloads
    fun denied(
        approvalId: String,
        decidedBy: String,
        decidedAt: Instant,
        reason: String,
        comment: String? = null,
    ): HumanApprovalDecision.Denied =
        HumanApprovalDecision.Denied(
            ApprovalId(approvalId),
            decidedBy,
            decidedAt,
            reason,
            comment,
        )
}
