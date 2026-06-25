package dev.tramai.engine.approval

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalRequestResult
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.AuditStreamId
import dev.tramai.core.approval.gateway.HumanApprovalDecision
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.engine.SuspendedInvocationStore
import java.time.Clock
import kotlinx.coroutines.CancellationException

/**
 * Minimal Preview adapter for [ApprovalGateway].
 *
 * Writes the existing approval, suspended invocation, and continuation stores
 * in order. Intended for ergonomic integration tests and Preview usage.
 *
 * **Limitations:**
 * - Does not provide a single transactional boundary across all three stores.
 *   If step 2 or 3 fails after step 1 succeeds, the stores are left in an
 *   inconsistent state. A future PR should harden the transaction boundary.
 * - Does not emit audit-requested outbox intent.
 * - Does not implement workflow resume.
 *
 * @param approvalStore         The store for approval request lifecycle.
 * @param continuationStore     The store for approval continuation records.
 * @param suspendedInvocationStore The store for suspended invocation metadata and replay envelope.
 * @param requestFactory        Internal seam that translates high-level SPI input into low-level
 *                              persistence records.
 * @param clock                 Clock for time-based checks. Defaults to system UTC.
 */
class DefaultApprovalGateway(
    private val approvalStore: ApprovalStore,
    private val continuationStore: ApprovalContinuationStore,
    private val suspendedInvocationStore: SuspendedInvocationStore,
    private val requestFactory: ApprovalGatewayRequestFactory,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalGateway {

    override suspend fun requestApproval(
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: WorkflowRunId?,
    ): ApprovalRequestResult {
        val request = requestFactory.createRequest(
            subject = subject,
            recommendation = recommendation,
            requiredRole = requiredRole,
            workflowRunId = workflowRunId,
        )

        return try {
            val existing = approvalStore.get(request.approvalRequest.approvalId)
            if (existing != null) {
                return existing.toGatewayResult(clock)
            }

            approvalStore.create(request.approvalRequest)

            suspendedInvocationStore.create(
                metadata = request.suspendedInvocationMetadata,
                replayEnvelope = request.replayEnvelope,
            )

            continuationStore.create(
                continuation = request.continuation,
                arguments = request.sensitiveArguments,
            )

            ApprovalRequestResult.Suspended(
                approvalId = ApprovalId(request.approvalRequest.approvalId),
                workflowRunId = WorkflowRunId(request.approvalRequest.binding.workflowRunId),
                auditStreamId = AuditStreamId(request.suspendedInvocationMetadata.correlationId),
                resumeToken = ResumeToken(request.approvalRequest.binding.approvalTokenDigest.value),
            )
        } catch (e: CancellationException) {
            throw e
        }
    }

    /**
     * Maps an existing [dev.tramai.core.approval.ApprovalRequest] to a gateway result.
     */
    private fun dev.tramai.core.approval.ApprovalRequest.toGatewayResult(
        clock: Clock,
    ): ApprovalRequestResult {
        val approvalId = ApprovalId(approvalId)
        val now = clock.instant()

        return when {
            status == ApprovalStatus.APPROVED -> ApprovalRequestResult.AlreadyApproved(
                decision = HumanApprovalDecision.Approved(
                    approvalId = approvalId,
                    decidedBy = requireNotNull(decidedBy) {
                        "approved request must have a decider"
                    },
                    decidedAt = requireNotNull(decidedAt) {
                        "approved request must have a decision timestamp"
                    },
                    comment = decisionComment,
                ),
            )

            status == ApprovalStatus.DENIED -> ApprovalRequestResult.AlreadyDenied(
                decision = HumanApprovalDecision.Denied(
                    approvalId = approvalId,
                    decidedBy = requireNotNull(decidedBy) {
                        "denied request must have a decider"
                    },
                    decidedAt = requireNotNull(decidedAt) {
                        "denied request must have a decision timestamp"
                    },
                    reason = decisionComment ?: "approval-denied",
                ),
            )

            status == ApprovalStatus.TIMED_OUT || !expiresAt.isAfter(now) ->
                ApprovalRequestResult.Expired(
                    approvalId = approvalId,
                    expiredAt = expiresAt,
                    reason = "approval-expired",
                )

            else -> ApprovalRequestResult.Suspended(
                approvalId = approvalId,
                workflowRunId = WorkflowRunId(binding.workflowRunId),
                auditStreamId = AuditStreamId(binding.workflowRunId),
                resumeToken = ResumeToken(binding.approvalTokenDigest.value),
            )
        }
    }
}
