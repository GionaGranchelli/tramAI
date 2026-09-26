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
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.GovernedRunContinuityException
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.engine.GovernedSuspendedInvocation
import dev.tramai.engine.GovernedSuspendedInvocationStore
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
 * **Governed runs:** an execution inside an active [GovernedRunScope] persists its canonical
 * identity rather than an un-attributed suspension. The gateway resolves the scope once, requires
 * both governed store capabilities before invoking the request factory, and then derives the
 * approval attribution at the governed store boundary. A governed request never falls back to the
 * legacy `create` path, and a deployment whose stores lack the governed capability fails closed
 * with [ConfigurationException] instead of durably recording a downgraded suspension.
 *
 * **Limitations:**
 * - Does not provide a single transactional boundary across all three stores.
 *   If step 2 or 3 fails after step 1 succeeds, the stores are left in an
 *   inconsistent state. A future PR should harden the transaction boundary.
 * - Does not emit audit-requested outbox intent.
 * - Does not implement workflow resume.
 * - Does not reconcile an existing approval's attribution against its existing suspended-invocation
 *   identity; that cross-record reconstruction continuity is tracked separately.
 * - Existing pending requests cannot recover the original suspended invocation
 *   correlation ID from [ApprovalStore] alone, so the adapter currently uses
 *   the workflow run ID as a temporary audit stream identifier until the
 *   resume/gateway state model is hardened.
 *
 * @param approvalStore         The store for approval request lifecycle.
 * @param continuationStore     The store for approval continuation records.
 * @param suspendedInvocationStore The store for suspended invocation metadata and replay envelope.
 * @param requestFactory        Internal seam that translates high-level SPI input into low-level
 *                              persistence records. It is identity-blind by contract: it never
 *                              receives and never nominates governed attribution. Must provide a
 *                              stable [ResumeToken] — the gateway never derives it from
 *                              [dev.tramai.core.approval.ApprovalBinding.approvalTokenDigest].
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
        // The canonical value for this request: a governed execution carries its identity, an
        // ungoverned one carries nothing. Every persistence path below derives from this one value
        // instead of reconstructing or reinterpreting identity for itself.
        val governedIdentity = resolveGovernedIdentity(workflowRunId)

        // Capability discovery is a single precondition, not a per-store surprise: a governed request
        // against a deployment that cannot persist governed records fails before the factory runs, so
        // a partially governed wiring can never write some records and then fail on the rest.
        if (governedIdentity != null) {
            requireGovernedApprovalStore()
            requireGovernedSuspensionStore()
        }

        val request =
            requestFactory.createRequest(
                subject = subject,
                recommendation = recommendation,
                requiredRole = requiredRole,
                workflowRunId = effectiveWorkflowRunId(governedIdentity, workflowRunId),
            )

        // The factory is untrusted for identity: it is handed the canonical run id above, and a
        // payload that re-points the binding must abort before anything durable exists.
        requireFactoryBindingMatches(request, governedIdentity)

        return try {
            val existing = approvalStore.get(request.approvalRequest.approvalId)
            if (existing != null) {
                requireExistingAttributionMatches(existing, governedIdentity)
                return existing.toGatewayResult(request, clock)
            }

            if (governedIdentity == null) {
                persistUngoverned(request)
            } else {
                persistGoverned(request, governedIdentity)
            }

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
     * Resolves the canonical governed identity for this request, or null when the execution is
     * ungoverned.
     *
     * A caller may name the run it believes it is suspending, but it may never nominate one run's id
     * together with another run's identity. When an active scope exists the scope is authoritative,
     * and a disagreement aborts here — before the factory and before any store.
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
     * Requires both governed capabilities up front. Existing callers resolve each capability lazily
     * at its own use site; here the whole wired store set is checked as one precondition so a partial
     * wiring cannot commit the approval and then refuse the suspension.
     */
    private fun requireGovernedApprovalStore(): GovernedApprovalStore =
        approvalStore as? GovernedApprovalStore
            ?: throw ConfigurationException(
                "Governed approval requests require an ApprovalStore implementing " +
                    "GovernedApprovalStore; the configured store " +
                    "'${approvalStore::class.simpleName}' does not",
            )

    private fun requireGovernedSuspensionStore(): GovernedSuspendedInvocationStore =
        suspendedInvocationStore as? GovernedSuspendedInvocationStore
            ?: throw ConfigurationException(
                "Governed approval suspension requires a SuspendedInvocationStore implementing " +
                    "GovernedSuspendedInvocationStore; the configured store " +
                    "'${suspendedInvocationStore::class.simpleName}' does not",
            )

    /**
     * The effective run id for the factory call: for a governed request the canonical identity
     * supplies it, so an omitted argument is derived rather than left absent.
     */
    private fun effectiveWorkflowRunId(
        governedIdentity: GovernedRunIdentity?,
        callerRunId: WorkflowRunId?,
    ): WorkflowRunId? = governedIdentity?.let { WorkflowRunId(it.runId.value) } ?: callerRunId

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
     * An idempotent call must not accept a durable record whose attribution contradicts the active
     * run: neither an un-attributed (legacy) approval nor one bound to a different identity may be
     * silently returned as if it were this run's suspension.
     *
     * A malformed persisted attribution is NOT re-interpreted here — the store's
     * `ApprovalAttributionCorruptionException` propagates unchanged.
     */
    private suspend fun requireExistingAttributionMatches(
        existing: dev.tramai.core.approval.ApprovalRequest,
        governedIdentity: GovernedRunIdentity?,
    ) {
        if (governedIdentity == null) return
        val store = requireGovernedApprovalStore()
        // A missing attribution record is not reinterpreted here: the store's own not-found
        // failure propagates, because an approval with an unreadable identity must never be
        // adopted by a governed run.
        val persisted = store.attributionOf(existing.approvalId)
        val expected = ApprovalRunAttribution.Governed(governedIdentity)
        if (persisted != expected) {
            throw GovernedRunContinuityException(
                "Existing approval '${existing.approvalId}' does not carry the canonical identity of " +
                    "governed run '${governedIdentity.runId.value}': a governed run must not resume " +
                    "another run's approval",
            )
        }
    }

    /** The legacy path: unchanged, and never reached by a governed request. */
    private suspend fun persistUngoverned(request: ApprovalGatewayPersistenceRequest) {
        approvalStore.create(request.approvalRequest)

        suspendedInvocationStore.create(
            metadata = request.suspendedInvocationMetadata,
            replayEnvelope = request.replayEnvelope,
        )

        continuationStore.create(
            continuation = request.continuation,
            arguments = request.sensitiveArguments,
        )
    }

    /**
     * The governed path. The attribution is derived here, at the governed store boundary, which is
     * what keeps the factory payload identity-blind: the factory never manufactures attribution and
     * never nominates a run.
     *
     * The suspension follows [ApprovalSuspensionCoordinator]'s shape — ONE durable record holding the
     * metadata, the replay envelope and the same canonical identity.
     */
    private suspend fun persistGoverned(
        request: ApprovalGatewayPersistenceRequest,
        governedIdentity: GovernedRunIdentity,
    ) {
        requireGovernedApprovalStore().createGovernedApproval(
            request.approvalRequest,
            ApprovalRunAttribution.Governed(governedIdentity),
        )

        requireGovernedSuspensionStore().createGoverned(
            GovernedSuspendedInvocation(
                metadata = request.suspendedInvocationMetadata,
                runIdentity = governedIdentity,
            ),
            request.replayEnvelope,
        )

        continuationStore.create(
            continuation = request.continuation,
            arguments = request.sensitiveArguments,
        )
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
