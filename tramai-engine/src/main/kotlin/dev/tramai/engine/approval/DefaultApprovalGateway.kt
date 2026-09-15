@file:OptIn(ExperimentalTramaiInternalApi::class)

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
import dev.tramai.core.exception.GovernedRunContinuityException
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.engine.SuspendedInvocationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import java.time.Clock

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
 * - Does not support governed runs: it persists an un-attributed suspension, so
 *   [requestApproval] fails closed with [GovernedRunContinuityException] when invoked inside an
 *   active [GovernedRunScope] rather than silently dropping the run's canonical identity.
 * - Existing pending requests cannot recover the original suspended invocation
 *   correlation ID from [ApprovalStore] alone, so the adapter currently uses
 *   the workflow run ID as a temporary audit stream identifier until the
 *   resume/gateway state model is hardened.
 *
 * @param approvalStore         The store for approval request lifecycle.
 * @param continuationStore     The store for approval continuation records.
 * @param suspendedInvocationStore The store for suspended invocation metadata and replay envelope.
 * @param requestFactory        Internal seam that translates high-level SPI input into low-level
 *                              persistence records. Must provide a stable [ResumeToken] — the
 *                              gateway never derives it from [dev.tramai.core.approval.ApprovalBinding.approvalTokenDigest].
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
        // This adapter persists an un-attributed suspension through the released `create` path and
        // cannot carry a governed run's canonical identity, so a governed execution must fail closed
        // here instead of durably recording an un-attributed suspension (0.7.1d forbids that silent
        // downgrade to legacy attribution). Nothing is written before this check.
        val governedRun = GovernedRunScope.resolve(currentCoroutineContext())
        if (governedRun != null) {
            throw GovernedRunContinuityException(
                "Cannot suspend governed run '${governedRun.runId.value}' through the preview " +
                    "approval gateway: this path writes an un-attributed suspension and cannot " +
                    "carry the governed identity",
            )
        }

        val request =
            requestFactory.createRequest(
                subject = subject,
                recommendation = recommendation,
                requiredRole = requiredRole,
                workflowRunId = workflowRunId,
            )

        return try {
            val existing = approvalStore.get(request.approvalRequest.approvalId)
            if (existing != null) {
                return existing.toGatewayResult(request, clock)
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
                resumeToken = request.resumeToken,
            )
        } catch (e: CancellationException) {
            throw e
        }
    }

    /**
     * Maps an existing [dev.tramai.core.approval.ApprovalRequest] to a gateway result.
     *
     * For the existing-PENDING case, the factory's [resumeToken] is used because the stored
     * approval binding only contains the digest of the nonce, not the credential itself.
     * The factory is expected to provide a deterministic token for idempotent calls.
     */
    @Suppress("unused")
    private fun dev.tramai.core.approval.ApprovalRequest.toGatewayResult(
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
                    // Existing pending cannot recover the original correlation ID from
                    // ApprovalStore alone, so workflowRunId serves as a temporary audit
                    // stream identifier until the resume/gateway state model is hardened.
                    auditStreamId = AuditStreamId(binding.workflowRunId),
                    resumeToken = request.resumeToken,
                )
            }
        }
    }
}
