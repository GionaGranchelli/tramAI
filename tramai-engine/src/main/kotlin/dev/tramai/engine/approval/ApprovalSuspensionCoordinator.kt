package dev.tramai.engine.approval

import dev.tramai.core.approval.*
import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.model.*
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.engine.*
import dev.tramai.engine.planning.ServiceDefinition
import dev.tramai.engine.tool.ToolApprovalGate
import dev.tramai.engine.tool.ToolExecutionRequest
import kotlinx.coroutines.CancellationException
import java.time.Clock

internal class ApprovalSuspensionCoordinator(
    private val approvalGateCoordinator: ApprovalGateCoordinator?, private val approvalContinuationStore: ApprovalContinuationStore?,
    private val suspendedInvocationStore: SuspendedInvocationStore, private val resumeOperationRegistry: ResumeOperationRegistry,
    private val serviceDefinition: ServiceDefinition, private val resumeExecutor: ClaimedResumeExecutor,
    private val toolArgumentsDigester: ToolArgumentsDigester?, private val clock: Clock,
    private val approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter,
) : ToolApprovalGate {
    override suspend fun requireApproval(request: ToolExecutionRequest, policyDecision: PolicyDecision.RequireApproval, input: String) {
        if (request.resumingApproval) { validateRenewedApprovalRequirement(request, policyDecision, input); return }
        val rawDigest = validateInitialApprovalRequirement(request, policyDecision, input)
        suspendToolExecution(SuspendToolExecutionRequest(request.tool, request.toolCall, request.operation, request.correlationId, input, request.identity, request.toolCallIndex, request.messages, rawDigest, policyDecision.requirement.timeoutMillis, request.securityContext, request.tokenBudgetTracker, request.conversationId, request.historySize))
    }
    private fun validateRenewedApprovalRequirement(request: ToolExecutionRequest, policyDecision: PolicyDecision.RequireApproval, input: String) {
        if (!request.allowRenewedApprovedBindingDuringResume) throw dev.tramai.core.exception.NestedApprovalNotSupportedException(request.parentApprovalId ?: "unknown", "Nested approval not supported in v1: tool '${request.tool.name}' requires approval during a resumed workflow")
        val requirement = policyDecision.requirement
        val digester = toolArgumentsDigester ?: throw ConfigurationException("ToolArgumentsDigester is required for renewed approval validation")
        val renewedDigest = digester.digest(SensitiveToolArguments.of(input))
        require(requirement.toolName == request.tool.name) { "Renewed approval requirement tool name mismatch: '${requirement.toolName}' != '${request.tool.name}'" }
        require(requirement.argumentsDigest.isEmpty() || Sha256Digest.of(requirement.argumentsDigest) == renewedDigest) { "Renewed approval requirement digest mismatch" }
        require(requirement.timeoutMillis > 0) { "Renewed approval requirement must have positive timeout" }
    }
    private fun validateInitialApprovalRequirement(request: ToolExecutionRequest, policyDecision: PolicyDecision.RequireApproval, input: String): Sha256Digest {
        val requirement = policyDecision.requirement
        require(requirement.toolName == request.tool.name) { "Approval requirement tool binding mismatch: expected '${request.tool.name}', got '${requirement.toolName}'" }
        val rawDigest = (toolArgumentsDigester ?: throw ConfigurationException("ToolArgumentsDigester is required for approval binding validation")).digest(SensitiveToolArguments.of(input))
        if (requirement.argumentsDigest.isNotEmpty()) require(Sha256Digest.of(requirement.argumentsDigest) == rawDigest) { "Approval requirement argument binding mismatch" }
        require(requirement.timeoutMillis > 0) { "Approval requirement timeout must be positive" }
        return rawDigest
    }
    private suspend fun suspendToolExecution(request: SuspendToolExecutionRequest): Nothing {
        val approvalGateCoordinator = approvalGateCoordinator ?: throw ConfigurationException("ApprovalGateCoordinator is required for tool execution suspension")
        val approvalContinuationStore = approvalContinuationStore ?: throw ConfigurationException("ApprovalContinuationStore is required for tool execution suspension")
        val sensitiveArgs = SensitiveToolArguments.of(request.input); val expiresAt = clock.instant().plusMillis(request.timeoutMillis)
        var createdChallengeId: String? = null; var createdContinuationVersion = 0L
        try {
            val challenge = approvalGateCoordinator.createApproval(CreateApprovalCommand(request.identity.workflowRunId, request.tool.name, request.argumentsDigest, request.identity.policyVersion, request.identity.workflowDigest, request.identity.actorId, expiresAt)); createdChallengeId = challenge.approvalId
            val continuation = approvalContinuationStore.create(ApprovalContinuation(challenge.approvalId, request.identity.workflowRunId, request.correlationId, request.toolCall.id, request.tool.name, request.argumentsDigest, request.identity.policyVersion, request.identity.workflowDigest, ApprovalContinuationStatus.PENDING, clock.instant(), challenge.expiresAt, null, null, null, version = 0L), sensitiveArgs); createdContinuationVersion = continuation.version
            val budgetSnapshot = request.tokenBudgetTracker?.snapshot()
            val opRef = resumeOperationRegistry.register(serviceDefinition, request.operation, resumeExecutor)
            val prepared = ReplayEnvelopeFactory.prepareForSuspension(opRef, request.messages, request.toolCall.id, request.tool.name, request.toolCallIndex)
            val toolRef = ResumeToolReference(request.tool.name, ResumeToolDeclarationDigestHelper.compute(request.tool))
            suspendedInvocationStore.create(SuspendedInvocationMetadata(challenge.approvalId, request.toolCall.id, request.tool.name, request.toolCallIndex, request.correlationId, request.identity, request.securityContext, opRef, prepared.digest, request.conversationId, request.historySize, budgetSnapshot, toolRef, request.tool.security), prepared.envelope)
            approvalLifecycleAuditEmitter.onToolExecutionSuspended(challenge.approvalId, request.identity.workflowRunId, request.tool.name, request.toolCall.id, request.correlationId, request.argumentsDigest, challenge.expiresAt)
            throw ApprovalSuspendedException(challenge, challenge.approvalId, request.identity.workflowRunId, request.toolCall.id, request.tool.name, continuation.version)
        } catch (failure: Exception) {
            failure.rethrowIfCancellation(); if (failure is ApprovalSuspendedException) throw failure
            createdChallengeId?.let { approvalId ->
                try { suspendedInvocationStore.remove(approvalId) } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { e.rethrowIfCancellation() }
                runCatching { approvalContinuationStore.cancel(approvalId, createdContinuationVersion) }
                runCatching { approvalGateCoordinator.cancelApproval(approvalId, 0L, "suspension-compensation") }
            }; throw failure
        }
    }
}
