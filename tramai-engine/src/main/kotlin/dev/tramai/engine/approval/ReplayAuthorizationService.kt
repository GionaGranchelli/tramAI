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

/**
 * Owns the pre-claim authorization protocol for a suspended approval:
 * validate the presented token (non-destructive), evaluate the
 * BEFORE_WORKFLOW_RESUME policy, then authorize (one-time consumption).
 *
 * The deny / nested-approval paths cancel durable state before throwing.
 * The authorization-replay event is emitted fail-open so an observer failure
 * never prevents resume completion.
 */
internal class ReplayAuthorizationService(
    private val approvalGateCoordinator: ApprovalGateCoordinator?,
    private val suspendedInvocationStore: SuspendedInvocationStore,
    private val approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter,
    private val policyHelper: PolicyEnforcementHelper,
    private val engineEventObserver: EngineEventObserver,
) {
    suspend fun validateToken(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        continuation: ApprovalContinuation,
    ) {
        requireApprovalGateCoordinator().validateResume(
            ValidateResumeCommand(
                approvalId = command.approvalId,
                expectedVersion = command.approvalExpectedVersion,
                presentedToken = command.presentedToken,
                consumedBy = command.resumedBy,
                workflowRunId = metadata.identity.workflowRunId,
                toolName = metadata.toolName,
                argumentsDigest = continuation.argumentsDigest,
                policyVersion = metadata.identity.policyVersion,
                workflowDigest = metadata.identity.workflowDigest,
            ),
        )
    }

    suspend fun decideResumePolicy(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        resolvedTool: dev.tramai.core.model.ResolvedTool,
    ): ReplayAuthorizationDecision {
        val context = policyHelper.buildContext(
            EnforcementPoint.BEFORE_WORKFLOW_RESUME,
            metadata.correlationId,
        )
            .toolName(metadata.toolName)
            .toolSecurity(resolvedTool.security)
            .applyApprovalSecurityContext(metadata.securityContext)
            .workflowRunId(metadata.identity.workflowRunId)
            .workflowDigest(metadata.identity.workflowDigest.value)
            .actorId(command.resumedBy)
            .build()
        return when (val decision = policyHelper.evaluate(context)) {
            is PolicyDecision.Deny -> ReplayAuthorizationDecision.Denied(decision)
            is PolicyDecision.RequireApproval -> ReplayAuthorizationDecision.RequiresNestedApproval
            PolicyDecision.Allow -> ReplayAuthorizationDecision.Allowed(replayed = false)
        }
    }

    suspend fun authorize(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        continuation: ApprovalContinuation,
    ): ApprovalAuthorization {
        return requireApprovalGateCoordinator().authorizeResume(
            AuthorizeResumeCommand(
                approvalId = command.approvalId,
                expectedVersion = command.approvalExpectedVersion,
                presentedToken = command.presentedToken,
                consumedBy = command.resumedBy,
                workflowRunId = metadata.identity.workflowRunId,
                toolName = metadata.toolName,
                argumentsDigest = continuation.argumentsDigest,
                policyVersion = metadata.identity.policyVersion,
                workflowDigest = metadata.identity.workflowDigest,
            ),
        )
    }

    suspend fun denyAndCancel(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        decision: PolicyDecision.Deny,
        store: ApprovalContinuationStore,
    ): Nothing {
        cancelState(command, metadata, store, "workflow-resume-denied: ${decision.reasonCode}")
        throw PolicyViolationException(decision)
    }

    suspend fun cancelForNestedApproval(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        store: ApprovalContinuationStore,
    ): Nothing {
        cancelState(command, metadata, store, "nested-approval-not-supported")
        throw NestedApprovalNotSupportedException(command.approvalId, "Nested approval not supported")
    }

    suspend fun cancelState(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        store: ApprovalContinuationStore,
        reason: String,
    ) {
        store.cancel(command.approvalId, command.continuationExpectedVersion)
        suspendedInvocationStore.remove(command.approvalId)
        approvalLifecycleAuditEmitter.onSuspensionCancelled(
            command.approvalId,
            metadata.identity.workflowRunId,
            metadata.toolName,
            reason,
        )
    }

    fun emitAuthorizationReplayed(
        replayed: Boolean,
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
    ) {
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

    private fun requireApprovalGateCoordinator(): ApprovalGateCoordinator =
        approvalGateCoordinator ?: throw ConfigurationException("ApprovalGateCoordinator is required for resume")
}
