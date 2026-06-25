package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalRequestResult
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.AuditStreamId
import dev.tramai.core.approval.gateway.HumanApprovalDecision
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationStore
import java.time.Clock
import kotlinx.coroutines.CancellationException

class SovereignOpsTransactionalApprovalGateway(
    private val mutationStore: SovereignOpsApprovalRequestMutationStore,
    private val requestFactory: ApprovalGatewayRequestFactory,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalGateway {

    override suspend fun requestApproval(
        subject: ApprovalSubject,
        recommendation: dev.tramai.core.approval.gateway.ApprovalRecommendation,
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
            when (val result = mutationStore.createApprovalRequest(request)) {
                is SovereignOpsApprovalRequestMutationResult.Created ->
                    ApprovalRequestResult.Suspended(
                        approvalId = ApprovalId(result.approvalId),
                        workflowRunId = WorkflowRunId(request.approvalRequest.binding.workflowRunId),
                        auditStreamId = AuditStreamId(result.correlationId),
                        resumeToken = result.resumeToken,
                    )

                is SovereignOpsApprovalRequestMutationResult.Existing ->
                    result.approval.toGatewayResult(request, clock)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}

private fun ApprovalRequest.toGatewayResult(
    request: ApprovalGatewayPersistenceRequest,
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
            resumeToken = request.resumeToken,
        )
    }
}
