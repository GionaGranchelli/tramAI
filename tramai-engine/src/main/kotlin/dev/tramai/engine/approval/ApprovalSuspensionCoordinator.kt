package dev.tramai.engine.approval

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
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ApprovalTokenRejectedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.NestedApprovalNotSupportedException
import dev.tramai.core.exception.StructuredOutputException
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.policy.PolicyDecision
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
import dev.tramai.engine.planning.ServiceDefinition
import dev.tramai.engine.tool.ToolApprovalGate
import dev.tramai.engine.tool.ToolExecutionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
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
        val digester =
            toolArgumentsDigester
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
        val digester =
            toolArgumentsDigester
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
        val approvalGateCoordinator = requireApprovalGateCoordinator()
        val approvalContinuationStore = requireApprovalContinuationStore()
        val governedSuspension = resolveGovernedSuspension()
        val sensitiveArgs = SensitiveToolArguments.of(request.input)
        val expiresAt = clock.instant().plusMillis(request.timeoutMillis)
        var createdChallengeId: String? = null
        var createdContinuationVersion = 0L
        try {
            val challenge =
                approvalGateCoordinator.createApproval(
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
            val continuation =
                approvalContinuationStore.create(
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
            val prepared =
                ReplayEnvelopeFactory.prepareForSuspension(
                    opRef,
                    request.messages,
                    request.toolCall.id,
                    request.tool.name,
                    request.toolCallIndex,
                )
            val toolRef =
                ResumeToolReference(
                    request.tool.name,
                    ResumeToolDeclarationDigestHelper.compute(request.tool),
                )
            val suspendedMetadata =
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
                )
            persistSuspendedInvocation(governedSuspension, suspendedMetadata, prepared.envelope)
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
            compensateSuspension(
                createdChallengeId,
                createdContinuationVersion,
                approvalContinuationStore,
                approvalGateCoordinator,
            )
            throw failure
        }
    }

    private fun requireApprovalGateCoordinator(): ApprovalGateCoordinator =
        approvalGateCoordinator
            ?: throw ConfigurationException("ApprovalGateCoordinator is required for tool execution suspension")

    private fun requireApprovalContinuationStore(): ApprovalContinuationStore =
        approvalContinuationStore
            ?: throw ConfigurationException("ApprovalContinuationStore is required for tool execution suspension")

    private suspend fun resolveGovernedSuspension(): GovernedSuspension {
        val identity =
            GovernedRunScope.resolve(currentCoroutineContext())
                ?: return GovernedSuspension(null, null)
        val store =
            suspendedInvocationStore as? GovernedSuspendedInvocationStore
                ?: throw ConfigurationException(
                    "Governed approval suspension requires a SuspendedInvocationStore implementing " +
                        "GovernedSuspendedInvocationStore; the configured store " +
                        "'${suspendedInvocationStore::class.simpleName}' does not",
                )
        return GovernedSuspension(identity, store)
    }

    private suspend fun persistSuspendedInvocation(
        governedSuspension: GovernedSuspension,
        metadata: SuspendedInvocationMetadata,
        envelope: SensitiveReplayEnvelope,
    ) {
        val identity = governedSuspension.identity
        val store = governedSuspension.store
        if (identity != null && store != null) {
            // ONE durable record: metadata + replay envelope + canonical identity.
            store.createGoverned(GovernedSuspendedInvocation(metadata, identity), envelope)
        } else {
            suspendedInvocationStore.create(metadata, envelope)
        }
    }

    private suspend fun compensateSuspension(
        approvalId: String?,
        continuationVersion: Long,
        continuationStore: ApprovalContinuationStore,
        gateCoordinator: ApprovalGateCoordinator,
    ) {
        approvalId?.let { id ->
            compensateStep { suspendedInvocationStore.remove(id) }
            compensateStep { continuationStore.cancel(id, continuationVersion) }
            compensateStep { gateCoordinator.cancelApproval(id, 0L, "suspension-compensation") }
        }
    }

    private suspend fun compensateStep(action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            e.rethrowIfCancellation()
        }
    }

    private data class GovernedSuspension(
        val identity: dev.tramai.core.identity.GovernedRunIdentity?,
        val store: GovernedSuspendedInvocationStore?,
    )
}
