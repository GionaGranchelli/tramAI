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
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.GovernedRunContinuityException
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadata
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadataContext
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadataFactory
import dev.tramai.spring.sovereign.ops.outbox.GovernedSovereignOpsApprovalRequestMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
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
 * **Governed runs:** an active [GovernedRunScope] is the creation authority, mirroring
 * `DefaultApprovalGateway`: the gateway resolves the canonical identity, validates any caller-supplied
 * run id against it, builds the identity-blind factory request, checks the factory's binding against
 * the canonical run id, and persists through [GovernedSovereignOpsApprovalRequestMutationStore] so the
 * approval attribution and the suspension identity come from one identity in one transaction. A
 * governed request against a configured store without that capability fails closed with
 * [ConfigurationException] before the factory runs and before anything is written.
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
        // THE canonical value for this request: a governed execution carries its identity, an
        // ungoverned one carries nothing. Everything below derives from this one value.
        val governedIdentity = resolveGovernedIdentity(workflowRunId)

        // Capability discovery is a precondition, not a per-store surprise: a governed request
        // against a deployment that cannot persist governed records fails before the factory runs,
        // so a partially governed wiring can never write some records and then fail on the rest.
        val governedStore = if (governedIdentity != null) requireGovernedStore() else null

        val request =
            requestFactory.createRequest(
                subject = subject,
                recommendation = recommendation,
                requiredRole = requiredRole,
                workflowRunId = effectiveWorkflowRunId(governedIdentity, workflowRunId),
            )

        // The factory is untrusted for identity: it was handed the canonical run id above, and a
        // payload that re-points the binding must abort before anything durable exists.
        requireFactoryBindingMatches(request, governedIdentity)

        // Optional artifacts for this one request. Assembled by a helper so the decision path
        // above — resolve identity, validate, dispatch — stays the readable security-relevant part.
        val artifacts = artifactsFor(request, subject, recommendation, requiredRole)

        return try {
            val result =
                if (governedIdentity == null) {
                    mutationStore.createApprovalRequest(
                        request,
                        artifacts.auditIntent,
                        artifacts.inboxMetadata,
                        artifacts.resumeCredential,
                    )
                } else {
                    // Non-null by construction: resolved above, before the factory ran.
                    checkNotNull(governedStore)
                        .createGovernedApprovalRequest(
                            request = request,
                            identity = governedIdentity,
                            auditIntent = artifacts.auditIntent,
                            inboxMetadata = artifacts.inboxMetadata,
                            resumeCredential = artifacts.resumeCredential,
                        )
                }

            when (result) {
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

    /**
     * Resolves the canonical governed identity for this request, or null when the execution is ungoverned.
     *
     * A caller may name the run it believes it is suspending, but it may never nominate one run's id
     * together with another run's identity. When an active scope exists the scope is authoritative, and a
     * disagreement aborts here — before the factory and before any store.
     */
    private suspend fun resolveGovernedIdentity(workflowRunId: WorkflowRunId?): GovernedRunIdentity? {
        val identity = GovernedRunScope.resolve(currentCoroutineContext()) ?: return null
        if (workflowRunId != null && workflowRunId.value != identity.runId.value) {
            throw GovernedRunContinuityException(
                "Caller supplied workflow run id '${workflowRunId.value}' does not match the active " +
                    "governed run '${identity.runId.value}': a governed suspension cannot pair one " +
                    "run's id with another run's identity",
            )
        }
        return identity
    }

    /**
     * The effective run id for the factory call: for a governed request the canonical identity supplies
     * it, so an omitted argument is derived rather than left absent.
     */
    private fun effectiveWorkflowRunId(
        governedIdentity: GovernedRunIdentity?,
        callerRunId: WorkflowRunId?,
    ): WorkflowRunId? = governedIdentity?.let { WorkflowRunId(it.runId.value) } ?: callerRunId

    /**
     * Requires the governed mutation capability up front, so a partially governed wiring cannot commit
     * the approval and then refuse the attribution.
     */
    private fun requireGovernedStore(): GovernedSovereignOpsApprovalRequestMutationStore =
        mutationStore as? GovernedSovereignOpsApprovalRequestMutationStore
            ?: throw ConfigurationException(
                "Governed approval requests require a SovereignOpsApprovalRequestMutationStore " +
                    "implementing GovernedSovereignOpsApprovalRequestMutationStore; the configured " +
                    "store '${mutationStore::class.simpleName}' does not",
            )

    /**
     * The approval binding is the run-id carrier the gateway owns; the store validates the remaining
     * carriers against the same identity before its transaction opens.
     */
    private fun requireFactoryBindingMatches(
        request: ApprovalGatewayPersistenceRequest,
        governedIdentity: GovernedRunIdentity?,
    ) {
        if (governedIdentity == null) return
        val bindingRunId = request.approvalRequest.binding.workflowRunId
        if (bindingRunId != governedIdentity.runId.value) {
            throw GovernedRunContinuityException(
                "Approval request factory produced a binding for run '$bindingRunId' while the active " +
                    "governed run is '${governedIdentity.runId.value}': a governed request must keep " +
                    "the canonical run id",
            )
        }
    }

    /**
     * Builds the optional inbox metadata, audit intent and resume credential for one request. These
     * are description, not authority: none of them can nominate identity.
     */
    private fun artifactsFor(
        request: ApprovalGatewayPersistenceRequest,
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
    ): ApprovalRequestArtifacts =
        ApprovalRequestArtifacts(
            auditIntent =
                auditIntentFactory?.approvalRequested(
                    request = request,
                    subject = subject,
                    recommendation = recommendation,
                    requiredRole = requiredRole,
                ),
            inboxMetadata =
                inboxMetadataFactory?.create(
                    ApprovalInboxMetadataContext(
                        approvalId = request.approvalRequest.approvalId,
                        workflowRunId = request.approvalRequest.binding.workflowRunId,
                        toolName = request.approvalRequest.binding.toolName,
                        requestedBy = request.approvalRequest.requestedBy,
                        requiredRole = requiredRole,
                        correlationId = request.suspendedInvocationMetadata.correlationId,
                    ),
                ),
            resumeCredential =
                ApprovalResumeCredentialRecord(
                    approvalId = ApprovalId(request.approvalRequest.approvalId),
                    workflowRunId = WorkflowRunId(request.approvalRequest.binding.workflowRunId),
                    resumeToken = SealedResumeToken.seal(request.resumeToken),
                    createdAt = request.approvalRequest.requestedAt,
                    expiresAt = request.approvalRequest.expiresAt,
                    version = 1L,
                ),
        )
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

/** The optional creation artifacts for one approval request. */
private data class ApprovalRequestArtifacts(
    val auditIntent: SovereignOpsAuditOutboxRecord?,
    val inboxMetadata: ApprovalInboxMetadata?,
    val resumeCredential: ApprovalResumeCredentialRecord?,
)
