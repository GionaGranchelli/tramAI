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

// ── Java-friendly factories ──
//
// These use String parameters instead of inline value class types to avoid
// Kotlin JVM name mangling. From Java, value class types (ApprovalId, etc.)
// erase to String at the JVM level, so the underlying representation is
// already String — these factories just wrap them in the proper Kotlin types
// so the require(value.isNotBlank()) init blocks are executed.

/**
 * Java-friendly factory for [ApprovalId].
 *
 * Preview API.
 */
object ApprovalIds {
    @JvmStatic
    fun of(value: String): ApprovalId = ApprovalId(value)
}

/**
 * Java-friendly factory for [WorkflowRunId].
 *
 * Preview API.
 */
object WorkflowRunIds {
    @JvmStatic
    fun of(value: String): WorkflowRunId = WorkflowRunId(value)
}

/**
 * Java-friendly factory for [AuditStreamId].
 *
 * Preview API.
 */
object AuditStreamIds {
    @JvmStatic
    fun of(value: String): AuditStreamId = AuditStreamId(value)
}

/**
 * Java-friendly factory for [ResumeToken].
 *
 * Preview API.
 */
object ResumeTokens {
    @JvmStatic
    fun of(value: String): ResumeToken = ResumeToken(value)
}

/**
 * Java-friendly factory for [ApprovalRequestResult] variants.
 *
 * Parameters use Kotlin inline value class types' underlying JVM types
 * (String) so the methods have clean JVM names.
 *
 * Preview API.
 */
object ApprovalRequestResults {

    /**
     * Creates a [Suspended] result for the given identifiers.
     * @param approvalId [ApprovalId.value] — the approval request identifier
     * @param workflowRunId [WorkflowRunId.value] — the workflow run identifier
     * @param auditStreamId [AuditStreamId.value] — the audit stream identifier
     * @param resumeToken [ResumeToken.value] — the resume credential token
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
     * @param approvalId [ApprovalId.value] — the expired approval identifier
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
 * Parameters for inline value class types use their JVM underlying types (String).
 *
 * Preview API.
 */
object HumanApprovalDecisions {

    /**
     * Creates an [Approved] decision.
     * @param approvalId [ApprovalId.value] — the approval identifier
     */
    @JvmStatic
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
     * @param approvalId [ApprovalId.value] — the approval identifier
     */
    @JvmStatic
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
