package dev.tramai.engine.approval

import dev.tramai.core.approval.*
import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.NestedApprovalNotSupportedException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.engine.*
import kotlinx.coroutines.CancellationException

private fun PolicyContextBuilder.applyApprovalSecurityContext(
    securityContext: ExecutionSecurityContext,
): PolicyContextBuilder = dataClassification(securityContext.dataClassification)
    .classificationSource(securityContext.classificationSource)

internal class ReplayAuthorizationService(
    private val approvalGateCoordinator: ApprovalGateCoordinator?, private val suspendedInvocationStore: SuspendedInvocationStore,
    private val approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter, private val policyHelper: PolicyEnforcementHelper,
    private val engineEventObserver: EngineEventObserver,
) {
    suspend fun validateToken(command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata, continuation: ApprovalContinuation) {
        requireApprovalGateCoordinator().validateResume(ValidateResumeCommand(command.approvalId, command.approvalExpectedVersion, command.presentedToken, command.resumedBy, metadata.identity.workflowRunId, metadata.toolName, continuation.argumentsDigest, metadata.identity.policyVersion, metadata.identity.workflowDigest))
    }
    suspend fun decideResumePolicy(command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata, resolvedTool: dev.tramai.core.model.ResolvedTool): ReplayAuthorizationDecision = when (val decision = policyHelper.evaluate(policyHelper.buildContext(EnforcementPoint.BEFORE_WORKFLOW_RESUME, metadata.correlationId).toolName(metadata.toolName).toolSecurity(resolvedTool.security).applyApprovalSecurityContext(metadata.securityContext).workflowRunId(metadata.identity.workflowRunId).workflowDigest(metadata.identity.workflowDigest.value).actorId(command.resumedBy).build())) {
        is PolicyDecision.Deny -> ReplayAuthorizationDecision.Denied(decision)
        is PolicyDecision.RequireApproval -> ReplayAuthorizationDecision.RequiresNestedApproval
        PolicyDecision.Allow -> ReplayAuthorizationDecision.Allowed(replayed = false)
    }
    suspend fun authorize(command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata, continuation: ApprovalContinuation): ApprovalAuthorization = requireApprovalGateCoordinator().authorizeResume(AuthorizeResumeCommand(command.approvalId, command.approvalExpectedVersion, command.presentedToken, command.resumedBy, metadata.identity.workflowRunId, metadata.toolName, continuation.argumentsDigest, metadata.identity.policyVersion, metadata.identity.workflowDigest))
    suspend fun denyAndCancel(command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata, decision: PolicyDecision.Deny, store: ApprovalContinuationStore): Nothing { cancelState(command, metadata, store, "workflow-resume-denied: ${decision.reasonCode}"); throw PolicyViolationException(decision) }
    suspend fun cancelForNestedApproval(command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata, store: ApprovalContinuationStore): Nothing { cancelState(command, metadata, store, "nested-approval-not-supported"); throw NestedApprovalNotSupportedException(command.approvalId, "Nested approval not supported") }
    suspend fun cancelState(command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata, store: ApprovalContinuationStore, reason: String) { store.cancel(command.approvalId, command.continuationExpectedVersion); suspendedInvocationStore.remove(command.approvalId); approvalLifecycleAuditEmitter.onSuspensionCancelled(command.approvalId, metadata.identity.workflowRunId, metadata.toolName, reason) }
    fun emitAuthorizationReplayed(replayed: Boolean, command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata) {
        if (!replayed) return
        try {
            engineEventObserver.onEngineEvent(
                name = "tramai.approval.authorization_replayed",
                attributes = mapOf(
                    "approvalId" to command.approvalId,
                    "workflowRunId" to metadata.identity.workflowRunId,
                    "toolName" to metadata.toolName,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            // Engine-event observer failures must not prevent resume completion.
        }
    }
    private fun requireApprovalGateCoordinator(): ApprovalGateCoordinator = approvalGateCoordinator ?: throw ConfigurationException("ApprovalGateCoordinator is required for resume")
}
