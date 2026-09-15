@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalRequestResult
import dev.tramai.core.approval.gateway.ApprovalResumeCredentialRecord
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.AuditStreamId
import dev.tramai.core.approval.gateway.HumanApprovalDecision
import dev.tramai.core.approval.gateway.SealedResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.core.exception.GovernedRunContinuityException
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadataContext
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadataFactory
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import java.time.Clock

/**
 * Transactional [ApprovalGateway] implementation backed by
 * [SovereignOpsApprovalRequestMutationStore].
 *
 * This gateway writes approval, suspended invocation, continuation, and (optionally)
 * approval-requested audit outbox records in a single database transaction.
 *
 * When an [ApprovalGatewayAuditIntentFactory] is provided, the gateway creates a
 * durable "approval-requested" outbox intent during request creation and passes it
 * to the mutation store for atomic persistence alongside the core records.
 *
 * **Governed runs:** [ApprovalGatewayPersistenceRequest] carries no governed identity, so this
 * gateway cannot persist run attribution through it. [requestApproval] fails closed with
 * [GovernedRunContinuityException] when invoked inside an active [GovernedRunScope] instead of
 * writing a request whose identity is silently absent.
 *
 * @param mutationStore atomic creation store
 * @param requestFactory builds low-level persistence records from the ergonomic SPI input
 * @param auditIntentFactory optional factory for approval-requested audit outbox intent
 * @param inboxMetadataFactory optional factory for safe inbox metadata labels
 * @param clock clock for temporal decisions
 */
class SovereignOpsTransactionalApprovalGateway(
    private val mutationStore: SovereignOpsApprovalRequestMutationStore,
    private val requestFactory: ApprovalGatewayRequestFactory,
    private val auditIntentFactory: ApprovalGatewayAuditIntentFactory? = null,
    private val inboxMetadataFactory: ApprovalInboxMetadataFactory? = null,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalGateway {
    override suspend fun requestApproval(
        subject: ApprovalSubject,
        recommendation: dev.tramai.core.approval.gateway.ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: WorkflowRunId?,
    ): ApprovalRequestResult {
        // [ApprovalGatewayPersistenceRequest] carries no governed identity, so this path cannot
        // persist one: a governed execution must fail closed here rather than durably recording a
        // run whose canonical identity is silently absent (the downgrade 0.7.1d forbids). Nothing is
        // written and the mutation store is never called before this check.
        rejectGovernedRun()

        val request =
            requestFactory.createRequest(
                subject = subject,
                recommendation = recommendation,
                requiredRole = requiredRole,
                workflowRunId = workflowRunId,
            )

        val auditIntent =
            auditIntentFactory?.approvalRequested(
                request = request,
                subject = subject,
                recommendation = recommendation,
                requiredRole = requiredRole,
            )

        val inboxMetadata =
            inboxMetadataFactory?.create(
                ApprovalInboxMetadataContext(
                    approvalId = request.approvalRequest.approvalId,
                    workflowRunId = request.approvalRequest.binding.workflowRunId,
                    toolName = request.approvalRequest.binding.toolName,
                    requestedBy = request.approvalRequest.requestedBy,
                    requiredRole = requiredRole,
                    correlationId = request.suspendedInvocationMetadata.correlationId,
                ),
            )

        val resumeCredential =
            ApprovalResumeCredentialRecord(
                approvalId = ApprovalId(request.approvalRequest.approvalId),
                workflowRunId = WorkflowRunId(request.approvalRequest.binding.workflowRunId),
                resumeToken = SealedResumeToken.seal(request.resumeToken),
                createdAt = request.approvalRequest.requestedAt,
                expiresAt = request.approvalRequest.expiresAt,
                version = 1L,
            )

        return try {
            when (val result = mutationStore.createApprovalRequest(request, auditIntent, inboxMetadata, resumeCredential)) {
                is SovereignOpsApprovalRequestMutationResult.Created -> {
                    ApprovalRequestResult.Suspended(
                        approvalId = ApprovalId(result.approvalId),
                        workflowRunId = WorkflowRunId(request.approvalRequest.binding.workflowRunId),
                        auditStreamId = AuditStreamId(result.correlationId),
                        resumeToken = result.resumeToken,
                    )
                }

                is SovereignOpsApprovalRequestMutationResult.Existing -> {
                    result.approval.toGatewayResult(request, clock)
                }
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
        status == ApprovalStatus.APPROVED -> {
            ApprovalRequestResult.AlreadyApproved(
                decision =
                    HumanApprovalDecision.Approved(
                        approvalId = approvalId,
                        decidedBy =
                            requireNotNull(decidedBy) {
                                "approved request must have a decider"
                            },
                        decidedAt =
                            requireNotNull(decidedAt) {
                                "approved request must have a decision timestamp"
                            },
                        comment = decisionComment,
                    ),
            )
        }

        status == ApprovalStatus.DENIED -> {
            ApprovalRequestResult.AlreadyDenied(
                decision =
                    HumanApprovalDecision.Denied(
                        approvalId = approvalId,
                        decidedBy =
                            requireNotNull(decidedBy) {
                                "denied request must have a decider"
                            },
                        decidedAt =
                            requireNotNull(decidedAt) {
                                "denied request must have a decision timestamp"
                            },
                        reason = decisionComment ?: "approval-denied",
                    ),
            )
        }

        status == ApprovalStatus.TIMED_OUT || !expiresAt.isAfter(now) -> {
            ApprovalRequestResult.Expired(
                approvalId = approvalId,
                expiredAt = expiresAt,
                reason = "approval-expired",
            )
        }

        else -> {
            ApprovalRequestResult.Suspended(
                approvalId = approvalId,
                workflowRunId = WorkflowRunId(binding.workflowRunId),
                auditStreamId = AuditStreamId(binding.workflowRunId),
                resumeToken = request.resumeToken,
            )
        }
    }
}

/**
 * Rejects a governed execution before any persistence happens.
 *
 * [ApprovalGatewayPersistenceRequest] carries no governed identity, so a governed run cannot be
 * suspended through this gateway without silently losing its attribution — the downgrade 0.7.1d
 * exists to prevent. [GovernedRunScope.resolve] reads the identity in force for the caller's
 * coroutine, mirroring the guarded suspension path.
 */
private suspend fun rejectGovernedRun() {
    val governedRun = GovernedRunScope.resolve(currentCoroutineContext())
    if (governedRun != null) {
        throw GovernedRunContinuityException(
            "Cannot suspend governed run '${governedRun.runId.value}' through the transactional " +
                "approval gateway: the approval-request persistence contract carries no governed " +
                "identity, so continuing would silently drop the run's attribution",
        )
    }
}
