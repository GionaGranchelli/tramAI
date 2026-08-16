package dev.tramai.engine.approval

import dev.tramai.core.approval.*
import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.*
import dev.tramai.core.model.ResolvedTool
import dev.tramai.engine.*
import kotlinx.coroutines.CancellationException

internal class ApprovalResumeCoordinator(
    private val approvalContinuationStore: ApprovalContinuationStore?, private val suspendedInvocationStore: SuspendedInvocationStore,
    private val resumeOperationRegistry: ResumeOperationRegistry, private val approvalGateCoordinator: ApprovalGateCoordinator?,
    private val toolRegistry: ToolRegistry, private val toolArgumentsDigester: ToolArgumentsDigester?,
    private val approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter, private val engineEventObserver: EngineEventObserver,
    private val claimService: ContinuationClaimService, private val authorizationService: ReplayAuthorizationService,
) {
    suspend fun resume(command: ResumeApprovalCommand): Any? {
        val store = requireApprovalContinuationStore(); val continuationSnapshot = store.get(command.approvalId)
        if (continuationSnapshot != null && continuationSnapshot.status == ApprovalContinuationStatus.COMPLETED) throw ApprovalTokenRejectedException(command.approvalId)
        val metadata = suspendedInvocationStore.get(command.approvalId) ?: throw ApprovalNotFoundException(command.approvalId)
        val registered = resumeOperationRegistry.resolve(metadata.operationReference)
        val existingContinuation = claimService.loadPendingForResume(store, command, metadata, registered)
        val resolvedTool = resolveAndValidateResumeTool(command, metadata, existingContinuation)
        authorizationService.validateToken(command, metadata, existingContinuation)
        when (val decision = authorizationService.decideResumePolicy(command, metadata, resolvedTool)) {
            is ReplayAuthorizationDecision.Denied -> authorizationService.denyAndCancel(command, metadata, decision.decision, store)
            ReplayAuthorizationDecision.RequiresNestedApproval -> authorizationService.cancelForNestedApproval(command, metadata, store)
            is ReplayAuthorizationDecision.Allowed -> Unit
        }
        val authorization = authorizationService.authorize(command, metadata, existingContinuation); authorizationService.emitAuthorizationReplayed(authorization.replayed, command, metadata)
        val claimed = claimService.claim(command.approvalId, command.continuationExpectedVersion, command.resumedBy)
        val uncertainOutcome = ResumeUncertainOutcome(); val context = ResumeExecutionContext(command, metadata, registered, resolvedTool, uncertainOutcome)
        return try { executeClaimedResume(context, claimed, store) } catch (e: NestedApprovalNotSupportedException) { emitResumeUncertainOutcomeOnce(uncertainOutcome, command, metadata, "nested-approval-not-supported"); throw e } catch (e: StructuredOutputException) { emitResumeUncertainOutcomeOnce(uncertainOutcome, command, metadata, "structured-parse-failed: ${e::class.simpleName ?: "unknown"}"); throw e } catch (e: CancellationException) { throw e } catch (e: Exception) { e.rethrowIfCancellation(); emitResumeUncertainOutcomeOnce(uncertainOutcome, command, metadata, "resume-failed: ${e::class.simpleName ?: "unknown"}"); throw e }
    }
    private suspend fun executeClaimedResume(context: ResumeExecutionContext, claimed: ClaimedApprovalContinuation, store: ApprovalContinuationStore): Any? {
        val command = context.command; val metadata = context.metadata
        val replayPayload = revealAndValidateReplayPayload(context.uncertainOutcome, command, metadata)
        val expectedArgsDigest = validateClaimedResumeArguments(context.uncertainOutcome, command, metadata, claimed, requireToolArgumentsDigester())
        val validatedInput = claimed.arguments.reveal(); val rehydratedPayload = ReplayEnvelopeFactory.rehydrateAfterClaim(replayPayload, metadata, validatedInput)
        val emitter: suspend (String) -> Unit = { reason -> emitResumeUncertainOutcomeOnce(context.uncertainOutcome, command, metadata, reason) }
        val result = context.registered.resumeExecutor.execute(ClaimedResumeExecutionRequest(command, metadata, context.registered, context.resolvedTool, rehydratedPayload, validatedInput, expectedArgsDigest, emitter))
        completeClaimedResume(command, metadata, claimed, store); return result
    }
    private suspend fun revealAndValidateReplayPayload(marker: ResumeUncertainOutcome, command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata): ReplayPayload {
        val replayEnvelope = suspendedInvocationStore.revealReplayEnvelope(command.approvalId) ?: throw ConfigurationException("replay-envelope-not-found")
        val replayPayload = replayEnvelope.revealForResume(); val actualDigest = ReplayEnvelopeDigestHelper.compute(metadata.operationReference, replayPayload.messages)
        if (actualDigest != metadata.replayEnvelopeDigest) { emitResumeUncertainOutcomeOnce(marker, command, metadata, "replay-envelope-digest-mismatch"); throw ConfigurationException("Replay envelope digest mismatch") }; return replayPayload
    }
    private suspend fun validateClaimedResumeArguments(marker: ResumeUncertainOutcome, command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata, claimed: ClaimedApprovalContinuation, digester: ToolArgumentsDigester): Sha256Digest {
        val actualArgsDigest = digester.digest(claimed.arguments); val expectedArgsDigest = claimed.continuation.argumentsDigest
        if (actualArgsDigest != expectedArgsDigest) { emitResumeUncertainOutcomeOnce(marker, command, metadata, "payload-integrity-mismatch"); throw ConfigurationException("Claimed continuation payload integrity mismatch") }; return expectedArgsDigest
    }
    private suspend fun completeClaimedResume(command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata, claimed: ClaimedApprovalContinuation, store: ApprovalContinuationStore) { store.complete(command.approvalId, claimed.continuation.version, command.resumedBy); removeSuspendedInvocationAfterResume(command, metadata); emitResumeCompletionAudit(command, metadata) }
    private suspend fun removeSuspendedInvocationAfterResume(command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata) { try { suspendedInvocationStore.remove(command.approvalId) } catch (e: CancellationException) { throw e } catch (e: Exception) { e.rethrowIfCancellation(); runCatching { engineEventObserver.onEngineEvent("resume-suspended-context-cleanup-failure", mapOf("approvalId" to command.approvalId, "toolName" to metadata.toolName)) } } }
    private suspend fun emitResumeCompletionAudit(command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata) { try { approvalLifecycleAuditEmitter.onToolExecutionCompleted(command.approvalId, metadata.identity.workflowRunId, metadata.toolName, command.resumedBy) } catch (e: CancellationException) { throw e } catch (e: Exception) { e.rethrowIfCancellation(); runCatching { engineEventObserver.onEngineEvent("resume-completion-audit-failure", mapOf("approvalId" to command.approvalId, "toolName" to metadata.toolName)) } } }
    private suspend fun emitResumeUncertainOutcomeOnce(marker: ResumeUncertainOutcome, command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata, reason: String) { if (marker.emitted) return; marker.emitted = true; approvalLifecycleAuditEmitter.onUncertainOutcome(command.approvalId, metadata.identity.workflowRunId, metadata.toolName, reason) }
    private fun requireApprovalContinuationStore(): ApprovalContinuationStore = approvalContinuationStore ?: throw ConfigurationException("ApprovalContinuationStore is required for resume")
    private fun requireToolArgumentsDigester(): ToolArgumentsDigester = requireNotNull(toolArgumentsDigester) { "ToolArgumentsDigester is required for payload integrity verification" }
    private fun resolveAndValidateResumeTool(command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata, continuation: ApprovalContinuation): ResolvedTool { val resolvedTool = toolRegistry.resolve(metadata.toolName) ?: throw ConfigurationException("approved-tool-not-registered"); require(ResumeToolDeclarationDigestHelper.compute(resolvedTool) == metadata.toolReference.declarationDigest) { "resume-tool-declaration-drift" }; require(metadata.toolReference.toolName == metadata.toolName) { "resume-tool-reference-name-mismatch" }; require(metadata.toolReference.toolName == resolvedTool.name) { "resume-tool-reference-active-name-mismatch" }; require(metadata.toolSecurity == resolvedTool.security) { "resume-tool-security-metadata-drift" }; require(continuation.approvalId == command.approvalId) { "continuation-approval-id-mismatch" }; return resolvedTool }
}
