@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.engine.approval

import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ApprovalRecoveryCoordinator
import dev.tramai.core.approval.ApprovalResumeAdmission
import dev.tramai.core.approval.ApprovalResumeAdmissionRequest
import dev.tramai.core.approval.ApprovalResumeEvidenceHandle
import dev.tramai.core.approval.ApprovalResumePermit
import dev.tramai.core.approval.ClaimedApprovalContinuation
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.ForceCancelClaimedCommand
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.approval.WorkflowEvidenceDiagnosticCategory
import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ApprovalNotFoundException
import dev.tramai.core.exception.ApprovalResumeAdmissionRefusedException
import dev.tramai.core.exception.ApprovalTokenRejectedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.NestedApprovalNotSupportedException
import dev.tramai.core.exception.StructuredOutputException
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.identity.SuspensionBindingProvenance
import dev.tramai.core.identity.WorkflowCheckpointBinding
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.core.observation.secondary.SecondaryEffectAuthority
import dev.tramai.core.observation.secondary.SecondaryFailureDiagnostic
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.GovernedSuspendedInvocation
import dev.tramai.engine.GovernedSuspendedInvocationStore
import dev.tramai.engine.RehydratedReplayPayload
import dev.tramai.engine.ReplayEnvelopeDigestHelper
import dev.tramai.engine.ReplayEnvelopeFactory
import dev.tramai.engine.ReplayPayload
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.ResumeToolDeclarationDigestHelper
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Reason codes for a refused post-claim resume; all match the privileged-recovery pattern. */
private const val REASON_NO_AUTHORITY: String = "resume-admission-authority-missing"
private const val REASON_AUTHORITY_REFUSED: String = "resume-admission-refused"
private const val REASON_IDENTITY_MISMATCH: String = "resume-admission-identity-mismatch"
private const val REASON_NO_BINDING: String = "resume-admission-binding-missing"
private const val REASON_LEGACY_PROVENANCE: String = "resume-admission-provenance-unknown"

/**
 * Orchestrates the resume state machine for a suspended approval.
 *
 * All authorization (token validation, policy evaluation, token consumption)
 * is owned by [ReplayAuthorizationService]; this coordinator composes it with
 * continuation claiming, replay-envelope verification, and the claimed-resume
 * dispatch. The executor used for the claimed resume is resolved from the
 * [ResumeOperationRegistry] — the registry knows how to dispatch.
 */
@Suppress("LongParameterList")
internal class ApprovalResumeCoordinator(
    private val approvalContinuationStore: ApprovalContinuationStore?,
    private val suspendedInvocationStore: SuspendedInvocationStore,
    private val resumeOperationRegistry: ResumeOperationRegistry,
    private val toolRegistry: ToolRegistry,
    private val toolArgumentsDigester: ToolArgumentsDigester?,
    private val approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter,
    private val engineEventObserver: EngineEventObserver,
    private val claimService: ContinuationClaimService,
    private val authorizationService: ReplayAuthorizationService,
    /**
     * E9: the workflow-bound resume authority, or null when this deployment claims no workflow-level admission. Absent
     * means a standalone engine; a suspension that a workflow claims to own is then refused rather than waved through.
     */
    private val approvalResumeAdmission: ApprovalResumeAdmission? = null,
    /**
     * The audited CLAIMED → CANCELLED_UNCERTAIN transition. Required to close a continuation whose resume was admitted
     * and then refused; without it the claim is left for the operator recovery sweep, and said so out loud.
     */
    private val approvalRecoveryCoordinator: ApprovalRecoveryCoordinator? = null,
    private val evidenceRequired: Boolean = false,
) {
    suspend fun resume(command: ResumeApprovalCommand): Any? {
        val prepared = prepareResume(command)
        val authorization = authorizeResume(command, prepared)
        authorizationService.emitAuthorizationReplayed(authorization.replayed, command, prepared.metadata)
        val claimed = claimService.claim(command.approvalId, command.continuationExpectedVersion, command.resumedBy)
        val uncertainOutcome = ResumeUncertainOutcome()
        val context =
            ResumeExecutionContext(
                command,
                prepared.metadata,
                prepared.registered,
                prepared.resolvedTool,
                uncertainOutcome,
                prepared.persistedGoverned,
            )

        return try {
            if (prepared.persistedGoverned == null) {
                executeClaimedResume(context, claimed, prepared.store)
            } else {
                // Install the RECOVERED identity around the resumed execution, so the engine
                // invocation that continues this run carries its canonical run id.
                withContext(GovernedRunScope(prepared.persistedGoverned)) {
                    executeClaimedResume(context, claimed, prepared.store)
                }
            }
        } catch (e: NestedApprovalNotSupportedException) {
            emitResumeUncertainOutcomeOnce(
                uncertainOutcome,
                command,
                prepared.metadata,
                "nested-approval-not-supported",
            )
            throw e
        } catch (e: ApprovalResumeAdmissionRefusedException) {
            // Not an uncertain outcome: nothing was dispatched. Reporting it as one would claim a tool may have run.
            throw e
        } catch (e: StructuredOutputException) {
            emitResumeUncertainOutcomeOnce(
                uncertainOutcome,
                command,
                prepared.metadata,
                "structured-parse-failed: ${e::class.simpleName ?: "unknown"}",
            )
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            emitResumeUncertainOutcomeOnce(
                uncertainOutcome,
                command,
                prepared.metadata,
                "resume-failed: ${e::class.simpleName ?: "unknown"}",
            )
            throw e
        }
    }

    private suspend fun prepareResume(command: ResumeApprovalCommand): ResumePreparation {
        val store = requireApprovalContinuationStore()
        val continuationSnapshot = store.get(command.approvalId)
        if (continuationSnapshot != null && continuationSnapshot.status == ApprovalContinuationStatus.COMPLETED) {
            throw ApprovalTokenRejectedException(command.approvalId)
        }
        val metadata =
            suspendedInvocationStore.get(command.approvalId)
                ?: throw ApprovalNotFoundException(command.approvalId)
        // 0.7.1d: recover the persisted identity before claim or execution.
        val persistedGoverned =
            (suspendedInvocationStore as? GovernedSuspendedInvocationStore)
                ?.governedRunIdentity(command.approvalId)
        requireGovernedContinuity(
            approvalId = command.approvalId,
            persisted = persistedGoverned,
            requested = GovernedRunScope.resolve(currentCoroutineContext()),
        )
        val registered = resumeOperationRegistry.resolve(metadata.operationReference)
        val continuation = claimService.loadPendingForResume(store, command, metadata, registered)
        val resolvedTool = resolveAndValidateResumeTool(command, metadata, continuation)
        authorizationService.validateToken(command, metadata, continuation)
        return ResumePreparation(store, metadata, persistedGoverned, registered, continuation, resolvedTool)
    }

    private suspend fun authorizeResume(
        command: ResumeApprovalCommand,
        prepared: ResumePreparation,
    ): ApprovalAuthorization {
        val decision = authorizationService.decideResumePolicy(command, prepared.metadata, prepared.resolvedTool)
        when (decision) {
            is ReplayAuthorizationDecision.Denied -> {
                authorizationService.denyAndCancel(command, prepared.metadata, decision.decision, prepared.store)
            }

            ReplayAuthorizationDecision.RequiresNestedApproval -> {
                authorizationService.cancelForNestedApproval(command, prepared.metadata, prepared.store)
            }

            is ReplayAuthorizationDecision.Allowed -> {
                Unit
            }
        }
        return authorizationService.authorize(command, prepared.metadata, prepared.continuation)
    }

    private data class ResumePreparation(
        val store: ApprovalContinuationStore,
        val metadata: SuspendedInvocationMetadata,
        val persistedGoverned: dev.tramai.core.identity.GovernedRunIdentity?,
        val registered: RegisteredResumeOperation,
        val continuation: ApprovalContinuation,
        val resolvedTool: ResolvedTool,
    )

    private suspend fun executeClaimedResume(
        context: ResumeExecutionContext,
        claimed: ClaimedApprovalContinuation,
        store: ApprovalContinuationStore,
    ): Any? {
        val command = context.command
        val metadata = context.metadata
        val replayPayload = revealAndValidateReplayPayload(context.uncertainOutcome, command, metadata)
        val expectedArgsDigest =
            validateClaimedResumeArguments(
                context.uncertainOutcome,
                command,
                metadata,
                claimed,
                requireToolArgumentsDigester(),
            )
        val validatedInput = claimed.arguments.reveal()
        val rehydratedPayload = ReplayEnvelopeFactory.rehydrateAfterClaim(replayPayload, metadata, validatedInput)
        val emitter: suspend (String) -> Unit = { reason ->
            emitResumeUncertainOutcomeOnce(context.uncertainOutcome, command, metadata, reason)
        }
        val permit = admitResumeBeforeDispatch(context, claimed)
        val handle = permit?.evidenceHandle
        if (evidenceRequired && permit != null && handle == null) {
            error(
                "Approval resume for '${context.command.approvalId}' requires durable evidence tracking, " +
                    "but admission authority returned permit without evidenceHandle",
            )
        }
        val result =
            dispatchClaimedResume(
                context = context,
                rehydratedPayload = rehydratedPayload,
                validatedInput = validatedInput,
                expectedArgsDigest = expectedArgsDigest,
                emitter = emitter,
                handle = handle,
            )
        completeClaimedResume(command, metadata, claimed, store)
        return result
    }

    private suspend fun dispatchClaimedResume(
        context: ResumeExecutionContext,
        rehydratedPayload: RehydratedReplayPayload,
        validatedInput: String,
        expectedArgsDigest: Sha256Digest,
        emitter: suspend (String) -> Unit,
        handle: ApprovalResumeEvidenceHandle?,
    ): Any? =
        try {
            val executed =
                context.registered.resumeExecutor.execute(
                    ClaimedResumeExecutionRequest(
                        context.command,
                        context.metadata,
                        context.registered,
                        context.resolvedTool,
                        rehydratedPayload,
                        validatedInput,
                        expectedArgsDigest,
                        emitter,
                    ),
                )
            handle?.recordDispatched()
            handle?.recordCompleted()
            executed
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            recordUncertainSafely(handle, e, WorkflowEvidenceDiagnosticCategory.TOOL_EXECUTION_TIMEOUT)
            throw e
        } catch (e: CancellationException) {
            recordUncertainSafely(handle, e, WorkflowEvidenceDiagnosticCategory.TOOL_EXECUTION_CANCELLED)
            throw e
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            recordUncertainAndSuppress(handle, e, WorkflowEvidenceDiagnosticCategory.TOOL_EXECUTION_FAILED)
            throw e
        }

    private suspend fun recordUncertainSafely(
        handle: ApprovalResumeEvidenceHandle?,
        error: Throwable,
        category: WorkflowEvidenceDiagnosticCategory,
    ) {
        try {
            handle?.recordUncertain(error, category)
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Throwable,
        ) {
            // Cancellation must never be suppressed or obscured
        }
    }

    private suspend fun recordUncertainAndSuppress(
        handle: ApprovalResumeEvidenceHandle?,
        error: Exception,
        category: WorkflowEvidenceDiagnosticCategory,
    ) {
        try {
            handle?.recordUncertain(error, category)
        } catch (
            @Suppress("TooGenericExceptionCaught") suppressed: Throwable,
        ) {
            if (error !== suppressed) {
                error.addSuppressed(suppressed)
            }
        }
    }

    /**
     * E9: the owning workflow's durable authority must admit this resumed invocation, after the claim and immediately
     * before dispatch.
     *
     * Three provenances, three outcomes, and no inferred fourth:
     * - [SuspensionBindingProvenance.WORKFLOW_BOUND] — the record names its checkpoint, so a fresh durable grant is
     *   required. Without the capability or without the binding, the resume is refused.
     * - [SuspensionBindingProvenance.STANDALONE] — the suspension declared at creation that no workflow owns it. No
     *   workflow cancellation guarantee applies, and this release does not invent one.
     * - [SuspensionBindingProvenance.LEGACY_UNKNOWN] — the record states nothing. A deployment that claims
     *   workflow-level admission (that is, one that installed this capability) must not treat silence as
     *   standalone, so it refuses.
     */
    private suspend fun admitResumeBeforeDispatch(
        context: ResumeExecutionContext,
        claimed: ClaimedApprovalContinuation,
    ): ApprovalResumePermit? {
        val suspension =
            (suspendedInvocationStore as? GovernedSuspendedInvocationStore)
                ?.governedSuspension(context.command.approvalId)
        val runIdentity = suspension?.runIdentity ?: context.persistedGovernedRunIdentity ?: return null
        requireAdmissionIdentityContinuity(context, claimed, runIdentity)
        val authority = approvalResumeAdmission
        return when (suspension?.provenance ?: SuspensionBindingProvenance.LEGACY_UNKNOWN) {
            SuspensionBindingProvenance.STANDALONE -> {
                null
            }

            SuspensionBindingProvenance.WORKFLOW_BOUND -> {
                admitWorkflowBound(context, claimed, authority, suspension?.checkpointBinding, runIdentity)
            }

            SuspensionBindingProvenance.LEGACY_UNKNOWN -> {
                if (authority == null) {
                    null
                } else {
                    refuseClaimedResume(
                        context,
                        claimed,
                        REASON_LEGACY_PROVENANCE,
                        "the suspension records no binding provenance, so workflow authority cannot be established",
                    )
                }
            }
        }
    }

    /**
     * The identity offered for admission must be the one recovered before the claim and installed in the resumed scope.
     * A store answering two identities for one run would otherwise have one authorized and another executed.
     */
    private suspend fun requireAdmissionIdentityContinuity(
        context: ResumeExecutionContext,
        claimed: ClaimedApprovalContinuation,
        offered: GovernedRunIdentity,
    ) {
        val persisted = context.persistedGovernedRunIdentity ?: return
        if (persisted != offered) {
            refuseClaimedResume(
                context,
                claimed,
                REASON_IDENTITY_MISMATCH,
                "the governed identity offered for admission is not the one persisted for this continuation",
            )
        }
    }

    private suspend fun admitWorkflowBound(
        context: ResumeExecutionContext,
        claimed: ClaimedApprovalContinuation,
        authority: ApprovalResumeAdmission?,
        checkpointBinding: WorkflowCheckpointBinding?,
        runIdentity: GovernedRunIdentity,
    ): ApprovalResumePermit {
        if (authority == null) {
            refuseClaimedResume(
                context,
                claimed,
                REASON_NO_AUTHORITY,
                "no workflow admission authority is wired",
            )
        }
        if (checkpointBinding == null) {
            refuseClaimedResume(context, claimed, REASON_NO_BINDING, "the suspension names no checkpoint")
        }
        val permit =
            try {
                authority.admitResume(
                    ApprovalResumeAdmissionRequest(
                        approvalId = context.command.approvalId,
                        toolName = context.metadata.toolName,
                        runIdentity = runIdentity,
                        checkpointBinding = checkpointBinding,
                        continuationVersion = claimed.continuation.version,
                    ),
                )
            } catch (refused: ApprovalResumeAdmissionRefusedException) {
                // The authority said no after the claim. The claim must not be left behind, and the refusal must not be
                // reclassified: it goes through the same audited terminal transition as every other refusal.
                refuseClaimedResume(context, claimed, REASON_AUTHORITY_REFUSED, refused.message ?: "refused")
            }
        permit.tryConsume()
        return permit
    }

    /**
     * Refuse a resume whose continuation is already claimed, and close the claim through the audited privileged
     * transition so it cannot sit CLAIMED forever.
     *
     * The recovery coordinator, not the raw store, is the only sanctioned path: it emits the durable
     * request-before-mutation audit, validates the reason code, and maps store failures. If it is unavailable or fails,
     * the continuation stays CLAIMED — which is safe, because a CLAIMED continuation is rejected by every later resume
     * (`continuation-not-pending`) and is swept by the existing stale-claim recovery — and the exception says so rather
     * than pretending the claim was closed.
     */
    private suspend fun refuseClaimedResume(
        context: ResumeExecutionContext,
        claimed: ClaimedApprovalContinuation,
        reasonCode: String,
        detail: String,
    ): Nothing {
        val command = context.command
        val recovery = approvalRecoveryCoordinator
        var closed = false
        if (recovery != null) {
            closed =
                try {
                    recovery.forceCancelClaimed(
                        ForceCancelClaimedCommand(
                            approvalId = command.approvalId,
                            expectedVersion = claimed.continuation.version,
                            operatorId = command.resumedBy,
                            reasonCode = reasonCode,
                        ),
                    )
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Exception,
                ) {
                    // The audit or the store may fail in any way; none of them may leave the claim looking closed.
                    failure.rethrowIfCancellation()
                    false
                }
        }
        throw ApprovalResumeAdmissionRefusedException(
            "approval '${command.approvalId}' cannot be resumed: $detail" +
                if (closed) {
                    "; the claimed continuation was cancelled through the audited recovery transition"
                } else {
                    "; the continuation remains CLAIMED and only the stale-claim recovery may reconcile it"
                },
        )
    }

    private suspend fun revealAndValidateReplayPayload(
        marker: ResumeUncertainOutcome,
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
    ): ReplayPayload {
        val replayEnvelope =
            suspendedInvocationStore.revealReplayEnvelope(command.approvalId)
                ?: throw ConfigurationException("replay-envelope-not-found")
        val replayPayload = replayEnvelope.revealForResume()
        val actualDigest = ReplayEnvelopeDigestHelper.compute(metadata.operationReference, replayPayload.messages)
        if (actualDigest != metadata.replayEnvelopeDigest) {
            emitResumeUncertainOutcomeOnce(marker, command, metadata, "replay-envelope-digest-mismatch")
            throw ConfigurationException("Replay envelope digest mismatch")
        }
        return replayPayload
    }

    private suspend fun validateClaimedResumeArguments(
        marker: ResumeUncertainOutcome,
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        claimed: ClaimedApprovalContinuation,
        digester: ToolArgumentsDigester,
    ): Sha256Digest {
        val actualArgsDigest = digester.digest(claimed.arguments)
        val expectedArgsDigest = claimed.continuation.argumentsDigest
        if (actualArgsDigest != expectedArgsDigest) {
            emitResumeUncertainOutcomeOnce(marker, command, metadata, "payload-integrity-mismatch")
            throw ConfigurationException("Claimed continuation payload integrity mismatch")
        }
        return expectedArgsDigest
    }

    private suspend fun completeClaimedResume(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        claimed: ClaimedApprovalContinuation,
        store: ApprovalContinuationStore,
    ) {
        store.complete(command.approvalId, claimed.continuation.version, command.resumedBy)
        removeSuspendedInvocationAfterResume(command, metadata)
        emitResumeCompletionAudit(command, metadata)
    }

    private suspend fun removeSuspendedInvocationAfterResume(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
    ) {
        try {
            suspendedInvocationStore.remove(command.approvalId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            try {
                engineEventObserver.onEngineEvent(
                    name = "resume-suspended-context-cleanup-failure",
                    attributes = mapOf("approvalId" to command.approvalId, "toolName" to metadata.toolName),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (observerError: Exception) {
                observerError.rethrowIfCancellation()
                // best-effort observer cleanup failure
            }
        }
    }

    private suspend fun emitResumeCompletionAudit(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
    ) {
        try {
            approvalLifecycleAuditEmitter.onToolExecutionCompleted(
                approvalId = command.approvalId,
                workflowRunId = metadata.identity.workflowRunId,
                toolName = metadata.toolName,
                completedBy = command.resumedBy,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            // Epic 5.3: post-side-effect completion audit. The tool side effect
            // and the COMPLETED transition have already happened — fail-closed
            // is physically impossible here. The audit failure is recorded with
            // explicit terminal semantics (authoritative, declared FAIL_CLOSED,
            // disposition terminal-recorded), NEVER silently converted to
            // telemetry as if nothing happened.
            SecondaryFailureDiagnostic.report(
                extensionPoint = "approval_lifecycle_audit",
                callback = "onToolExecutionCompleted",
                errorType = e.javaClass.simpleName,
                failurePolicy = "FAIL_CLOSED",
                authority = SecondaryEffectAuthority.AUTHORITATIVE.name,
            )
        }
    }

    private suspend fun emitResumeUncertainOutcomeOnce(
        marker: ResumeUncertainOutcome,
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        reason: String,
    ) {
        if (marker.emitted) return
        marker.emitted = true
        try {
            approvalLifecycleAuditEmitter.onUncertainOutcome(
                command.approvalId,
                metadata.identity.workflowRunId,
                metadata.toolName,
                reason,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            // Epic 5.3: this audit reports an ALREADY-FAILED resume. The
            // underlying business failure is primary; an audit failure here must
            // be recorded (authoritative, declared FAIL_CLOSED, disposition
            // terminal-recorded) and NEVER substitute the primary failure.
            SecondaryFailureDiagnostic.report(
                extensionPoint = "approval_lifecycle_audit",
                callback = "onUncertainOutcome",
                errorType = e.javaClass.simpleName,
                failurePolicy = "FAIL_CLOSED",
                authority = SecondaryEffectAuthority.AUTHORITATIVE.name,
            )
        }
    }

    private fun requireApprovalContinuationStore(): ApprovalContinuationStore =
        approvalContinuationStore ?: throw ConfigurationException("ApprovalContinuationStore is required for resume")

    private fun requireToolArgumentsDigester(): ToolArgumentsDigester =
        requireNotNull(toolArgumentsDigester) { "ToolArgumentsDigester is required for payload integrity verification" }

    private fun resolveAndValidateResumeTool(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        continuation: ApprovalContinuation,
    ): ResolvedTool {
        val resolvedTool =
            toolRegistry.resolve(metadata.toolName)
                ?: throw ConfigurationException("approved-tool-not-registered")
        require(ResumeToolDeclarationDigestHelper.compute(resolvedTool) == metadata.toolReference.declarationDigest) {
            "resume-tool-declaration-drift"
        }
        require(metadata.toolReference.toolName == metadata.toolName) { "resume-tool-reference-name-mismatch" }
        require(metadata.toolReference.toolName == resolvedTool.name) { "resume-tool-reference-active-name-mismatch" }
        require(metadata.toolSecurity == resolvedTool.security) { "resume-tool-security-metadata-drift" }
        require(continuation.approvalId == command.approvalId) { "continuation-approval-id-mismatch" }
        return resolvedTool
    }
}
