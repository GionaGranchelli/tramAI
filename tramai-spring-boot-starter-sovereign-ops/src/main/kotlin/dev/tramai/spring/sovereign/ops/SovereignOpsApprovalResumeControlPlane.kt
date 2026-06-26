package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.exception.ApprovalBindingMismatchException
import dev.tramai.core.exception.ApprovalNotFoundException
import dev.tramai.core.exception.ApprovalTokenRejectedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.engine.ResumeApprovalCommand
import java.time.Clock
import kotlinx.coroutines.CancellationException

/**
 * Preview [ApprovalResumeControlPlane] backed by the existing engine resume runtime.
 *
 * Composes [resumeApproval] rather than duplicating resume logic.
 *
 * Flow:
 * 1. Load approval by approvalId.
 * 2. If missing → NotFound.
 * 3. If status != APPROVED → NotApproved.
 * 4. Load continuation by approvalId.
 * 5. If missing → Conflict("approval-continuation-missing").
 * 6. If continuation already completed → AlreadyCompleted.
 * 7. Compose [ResumeApprovalCommand] with validated versions and token.
 * 8. Call the injected engine/runtime resume function.
 * 9. Return Resumed with the workflow result.
 *
 * @param approvalStore stores approval requests
 * @param approvalContinuationStore stores continuation records
 * @param resumeApproval engine-level runtime for workflow resume
 * @param clock clock for temporal checks
 */
class SovereignOpsApprovalResumeControlPlane(
    private val approvalStore: ApprovalStore,
    private val approvalContinuationStore: ApprovalContinuationStore,
    private val resumeApproval: suspend (ResumeApprovalCommand) -> Any?,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalResumeControlPlane {

    override suspend fun resume(command: ApprovalResumeCommand): ApprovalResumeResult {
        val approval = approvalStore.get(command.approvalId.value)
            ?: return ApprovalResumeResult.NotFound(command.approvalId)

        if (approval.status != ApprovalStatus.APPROVED) {
            return ApprovalResumeResult.NotApproved(command.approvalId, approval.status)
        }

        val continuation = approvalContinuationStore.get(command.approvalId.value)
            ?: return ApprovalResumeResult.Conflict(
                command.approvalId,
                "approval-continuation-missing",
            )

        when (continuation.status) {
            ApprovalContinuationStatus.PENDING -> Unit

            ApprovalContinuationStatus.COMPLETED ->
                return ApprovalResumeResult.AlreadyCompleted(command.approvalId)

            else ->
                return ApprovalResumeResult.Conflict(
                    approvalId = command.approvalId,
                    reason = "approval-continuation-not-pending-${continuation.status.name}",
                )
        }

        if (continuation.approvalExpiresAt.isBefore(clock.instant())) {
            return ApprovalResumeResult.Conflict(
                command.approvalId,
                "approval-continuation-expired",
            )
        }

        val presentedToken = try {
            ApprovalToken.parsePresented(command.resumeToken.value)
        } catch (_: IllegalArgumentException) {
            return ApprovalResumeResult.Conflict(
                command.approvalId,
                "approval-resume-token-invalid",
            )
        }

        val engineCommand = ResumeApprovalCommand(
            approvalId = command.approvalId.value,
            approvalExpectedVersion = command.expectedApprovalVersion ?: approval.version,
            continuationExpectedVersion = command.expectedContinuationVersion ?: continuation.version,
            presentedToken = presentedToken,
            resumedBy = command.resumedBy,
        )

        return try {
            val workflowResult = resumeApproval(engineCommand)
            ApprovalResumeResult.Resumed(
                approvalId = command.approvalId,
                resumedBy = command.resumedBy,
                result = workflowResult,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApprovalNotFoundException) {
            ApprovalResumeResult.NotFound(command.approvalId)
        } catch (e: ApprovalTokenRejectedException) {
            ApprovalResumeResult.Conflict(
                approvalId = command.approvalId,
                reason = "approval-token-rejected",
            )
        } catch (e: ApprovalBindingMismatchException) {
            ApprovalResumeResult.Conflict(
                approvalId = command.approvalId,
                reason = e.message ?: "approval-binding-mismatch",
            )
        } catch (e: ConfigurationException) {
            ApprovalResumeResult.Conflict(
                approvalId = command.approvalId,
                reason = e.message ?: "approval-resume-configuration-conflict",
            )
        } catch (e: IllegalArgumentException) {
            ApprovalResumeResult.Conflict(
                approvalId = command.approvalId,
                reason = e.message ?: "approval-resume-conflict",
            )
        } catch (e: IllegalStateException) {
            ApprovalResumeResult.Conflict(
                approvalId = command.approvalId,
                reason = e.message ?: "approval-resume-conflict",
            )
        } catch (e: Exception) {
            ApprovalResumeResult.Failed(
                approvalId = command.approvalId,
                reason = e.message ?: "approval-resume-failed",
            )
        }
    }
}
