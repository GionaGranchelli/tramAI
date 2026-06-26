package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ResumeToken

/**
 * Preview resume control-plane API for resuming approved, suspended workflow executions.
 *
 * This is the application-facing boundary between the approval decision control plane
 * and the engine-level workflow resume runtime.
 *
 * Implementations must compose the existing [dev.tramai.engine.ResumeApprovalCommand]
 * and [dev.tramai.standalone.TramaiRuntime.resumeApproval] rather than reimplementing resume logic.
 */
interface ApprovalResumeControlPlane {

    /**
     * Resume a suspended workflow execution after its approval was approved.
     * @return typed [ApprovalResumeResult] indicating outcome.
     */
    suspend fun resume(command: ApprovalResumeCommand): ApprovalResumeResult
}

/**
 * Command to resume a suspended workflow execution after approval.
 *
 * @param approvalId The approval that was approved.
 * @param resumeToken The resume token returned from [dev.tramai.core.approval.gateway.ApprovalRequestResult.Suspended.resumeToken].
 * @param resumedBy The identity initiating the resume.
 * @param expectedApprovalVersion Expected version of the approval for optimistic concurrency (null = auto-detect from store).
 * @param expectedContinuationVersion Expected version of the continuation for optimistic concurrency (null = auto-detect from store).
 */
data class ApprovalResumeCommand(
    val approvalId: ApprovalId,
    val resumeToken: ResumeToken,
    val resumedBy: String,
    val expectedApprovalVersion: Long? = null,
    val expectedContinuationVersion: Long? = null,
)

/**
 * Typed result from an approval resume operation.
 */
sealed interface ApprovalResumeResult {

    /** The workflow was successfully resumed and produced a result. */
    data class Resumed(
        val approvalId: ApprovalId,
        val resumedBy: String,
        val result: Any?,
    ) : ApprovalResumeResult

    /** The approval was not found. */
    data class NotFound(
        val approvalId: ApprovalId,
    ) : ApprovalResumeResult

    /** The approval exists but is not in APPROVED status (e.g. PENDING, DENIED, EXPIRED). */
    data class NotApproved(
        val approvalId: ApprovalId,
        val status: ApprovalStatus,
    ) : ApprovalResumeResult

    /** The continuation was already completed (resume already happened). */
    data class AlreadyCompleted(
        val approvalId: ApprovalId,
    ) : ApprovalResumeResult

    /** Version conflict, invalid token, or state mismatch. */
    data class Conflict(
        val approvalId: ApprovalId,
        val reason: String,
    ) : ApprovalResumeResult

    /** The engine-level resume failed with an exception. */
    data class Failed(
        val approvalId: ApprovalId,
        val reason: String,
    ) : ApprovalResumeResult
}
