@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.engine.approval

import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ClaimedApprovalContinuation
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ApprovalNotFoundException
import dev.tramai.core.exception.ApprovalTokenRejectedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.NestedApprovalNotSupportedException
import dev.tramai.core.exception.StructuredOutputException
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.core.observation.secondary.SecondaryEffectAuthority
import dev.tramai.core.observation.secondary.SecondaryFailureDiagnostic
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.GovernedSuspendedInvocation
import dev.tramai.engine.GovernedSuspendedInvocationStore
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

/**
 * Orchestrates the resume state machine for a suspended approval.
 *
 * All authorization (token validation, policy evaluation, token consumption)
 * is owned by [ReplayAuthorizationService]; this coordinator composes it with
 * continuation claiming, replay-envelope verification, and the claimed-resume
 * dispatch. The executor used for the claimed resume is resolved from the
 * [ResumeOperationRegistry] — the registry knows how to dispatch.
 */
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
        val result =
            context.registered.resumeExecutor.execute(
                ClaimedResumeExecutionRequest(
                    command,
                    metadata,
                    context.registered,
                    context.resolvedTool,
                    rehydratedPayload,
                    validatedInput,
                    expectedArgsDigest,
                    emitter,
                ),
            )
        completeClaimedResume(command, metadata, claimed, store)
        return result
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
