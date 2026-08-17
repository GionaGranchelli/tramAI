package dev.tramai.engine.approval

import dev.tramai.core.approval.*
import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.NestedApprovalNotSupportedException
import dev.tramai.core.model.*
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.engine.*
import dev.tramai.engine.planning.ServiceDefinition
import dev.tramai.engine.tool.ToolApprovalGate
import dev.tramai.engine.tool.ToolExecutionRequest
import kotlinx.coroutines.CancellationException
import java.time.Clock

/**
 * Implements [ToolApprovalGate]: the suspension saga for a tool that requires
 * approval. Validates the approval binding, creates the challenge + PENDING
 * continuation, persists the suspended invocation (redacted replay envelope),
 * emits the suspension audit, then throws [ApprovalSuspendedException].
 *
 * Compensation runs in reverse order on ordinary failures and NEVER replaces
 * the initiating failure; [ApprovalSuspendedException] itself never
 * compensates (the suspension is the intended outcome).
 */
internal class ApprovalSuspensionCoordinator(
    private val approvalGateCoordinator: ApprovalGateCoordinator?,
    private val approvalContinuationStore: ApprovalContinuationStore?,
    private val suspendedInvocationStore: SuspendedInvocationStore,
    private val resumeOperationRegistry: ResumeOperationRegistry,
    private val serviceDefinition: ServiceDefinition,
    private val resumeExecutor: ClaimedResumeExecutor,
    private val toolArgumentsDigester: ToolArgumentsDigester?,
    private val clock: Clock,
    private val approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter,
) : ToolApprovalGate {

    override suspend fun requireApproval(
        request: ToolExecutionRequest,
        policyDecision: PolicyDecision.RequireApproval,
        input: String,
    ) {
        if (request.resumingApproval) {
            validateRenewedApprovalRequirement(request, policyDecision, input)
            return
        }
        val rawDigest = validateInitialApprovalRequirement(request, policyDecision, input)
        suspendToolExecution(
            SuspendToolExecutionRequest(
                tool = request.tool,
                toolCall = request.toolCall,
                operation = request.operation,
                correlationId = request.correlationId,
                input = input,
                identity = request.identity,
                toolCallIndex = request.toolCallIndex,
                messages = request.messages,
                argumentsDigest = rawDigest,
                timeoutMillis = policyDecision.requirement.timeoutMillis,
                securityContext = request.securityContext,
                tokenBudgetTracker = request.tokenBudgetTracker,
                conversationId = request.conversationId,
                historySize = request.historySize,
            ),
        )
    }

    private fun validateRenewedApprovalRequirement(
        request: ToolExecutionRequest,
        policyDecision: PolicyDecision.RequireApproval,
        input: String,
    ) {
        if (!request.allowRenewedApprovedBindingDuringResume) {
            throw NestedApprovalNotSupportedException(
                request.parentApprovalId ?: "unknown",
                "Nested approval not supported in v1: tool '${request.tool.name}' requires approval during a resumed workflow",
            )
        }
        val requirement = policyDecision.requirement
        val digester = toolArgumentsDigester
            ?: throw ConfigurationException("ToolArgumentsDigester is required for renewed approval validation")
        val renewedDigest = digester.digest(SensitiveToolArguments.of(input))
        require(requirement.toolName == request.tool.name) {
            "Renewed approval requirement tool name mismatch: '${requirement.toolName}' != '${request.tool.name}'"
        }
        require(requirement.argumentsDigest.isEmpty() || Sha256Digest.of(requirement.argumentsDigest) == renewedDigest) {
            "Renewed approval requirement digest mismatch"
        }
        require(requirement.timeoutMillis > 0) { "Renewed approval requirement must have positive timeout" }
    }

    private fun validateInitialApprovalRequirement(
        request: ToolExecutionRequest,
        policyDecision: PolicyDecision.RequireApproval,
        input: String,
    ): Sha256Digest {
        val requirement = policyDecision.requirement
        require(requirement.toolName == request.tool.name) {
            "Approval requirement tool binding mismatch: expected '${request.tool.name}', got '${requirement.toolName}'"
        }
        val digester = toolArgumentsDigester
            ?: throw ConfigurationException("ToolArgumentsDigester is required for approval binding validation")
        val rawDigest = digester.digest(SensitiveToolArguments.of(input))
        if (requirement.argumentsDigest.isNotEmpty()) {
            require(Sha256Digest.of(requirement.argumentsDigest) == rawDigest) {
                "Approval requirement argument binding mismatch"
            }
        }
        require(requirement.timeoutMillis > 0) { "Approval requirement timeout must be positive" }
        return rawDigest
    }

    private suspend fun suspendToolExecution(request: SuspendToolExecutionRequest): Nothing {
        val approvalGateCoordinator = approvalGateCoordinator
            ?: throw ConfigurationException("ApprovalGateCoordinator is required for tool execution suspension")
        val approvalContinuationStore = approvalContinuationStore
            ?: throw ConfigurationException("ApprovalContinuationStore is required for tool execution suspension")
        val sensitiveArgs = SensitiveToolArguments.of(request.input)
        val expiresAt = clock.instant().plusMillis(request.timeoutMillis)
        var createdChallengeId: String? = null
        var createdContinuationVersion = 0L
        try {
            val challenge = approvalGateCoordinator.createApproval(
                CreateApprovalCommand(
                    workflowRunId = request.identity.workflowRunId,
                    toolName = request.tool.name,
                    argumentsDigest = request.argumentsDigest,
                    policyVersion = request.identity.policyVersion,
                    workflowDigest = request.identity.workflowDigest,
                    requestedBy = request.identity.actorId,
                    expiresAt = expiresAt,
                ),
            )
            createdChallengeId = challenge.approvalId
            val continuation = approvalContinuationStore.create(
                ApprovalContinuation(
                    approvalId = challenge.approvalId,
                    workflowRunId = request.identity.workflowRunId,
                    correlationId = request.correlationId,
                    toolCallId = request.toolCall.id,
                    toolName = request.tool.name,
                    argumentsDigest = request.argumentsDigest,
                    policyVersion = request.identity.policyVersion,
                    workflowDigest = request.identity.workflowDigest,
                    status = ApprovalContinuationStatus.PENDING,
                    createdAt = clock.instant(),
                    approvalExpiresAt = challenge.expiresAt,
                    claimedBy = null,
                    claimedAt = null,
                    completedAt = null,
                    version = 0L,
                ),
                sensitiveArgs,
            )
            createdContinuationVersion = continuation.version
            val budgetSnapshot = request.tokenBudgetTracker?.snapshot()
            val opRef = resumeOperationRegistry.register(serviceDefinition, request.operation, resumeExecutor)
            val prepared = ReplayEnvelopeFactory.prepareForSuspension(
                opRef, request.messages, request.toolCall.id, request.tool.name, request.toolCallIndex,
            )
            val toolRef = ResumeToolReference(
                request.tool.name,
                ResumeToolDeclarationDigestHelper.compute(request.tool),
            )
            suspendedInvocationStore.create(
                SuspendedInvocationMetadata(
                    approvalId = challenge.approvalId,
                    toolCallId = request.toolCall.id,
                    toolName = request.tool.name,
                    toolCallIndex = request.toolCallIndex,
                    correlationId = request.correlationId,
                    identity = request.identity,
                    securityContext = request.securityContext,
                    operationReference = opRef,
                    replayEnvelopeDigest = prepared.digest,
                    conversationId = request.conversationId,
                    historySize = request.historySize,
                    tokenBudgetSnapshot = budgetSnapshot,
                    toolReference = toolRef,
                    toolSecurity = request.tool.security,
                ),
                prepared.envelope,
            )
            approvalLifecycleAuditEmitter.onToolExecutionSuspended(
                challenge.approvalId,
                request.identity.workflowRunId,
                request.tool.name,
                request.toolCall.id,
                request.correlationId,
                request.argumentsDigest,
                challenge.expiresAt,
            )
            throw ApprovalSuspendedException(
                challenge,
                challenge.approvalId,
                request.identity.workflowRunId,
                request.toolCall.id,
                request.tool.name,
                continuation.version,
            )
        } catch (failure: Exception) {
            failure.rethrowIfCancellation()
            if (failure is ApprovalSuspendedException) throw failure
            createdChallengeId?.let { approvalId ->
                try {
                    suspendedInvocationStore.remove(approvalId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                }
                try {
                    approvalContinuationStore.cancel(approvalId, createdContinuationVersion)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    // best-effort cleanup
                }
                try {
                    approvalGateCoordinator.cancelApproval(approvalId, 0L, "suspension-compensation")
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    // best-effort cleanup
                }
            }
            throw failure
        }
    }
}
