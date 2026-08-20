@file:OptIn(ExperimentalTramaiInternalApi::class)
package dev.tramai.engine.invocation


import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.core.approval.IdempotencyKeyUtil
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.ToolInvalidInputException
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolResult
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.PolicyContextBuilder
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.ReturnKind
import dev.tramai.engine.approval.ClaimedResumeExecutionRequest
import dev.tramai.engine.budget.TokenBudgetCoordinator
import dev.tramai.engine.budget.TokenBudgetTracker
import dev.tramai.engine.memory.ConversationMemoryCoordinator
import dev.tramai.engine.memory.PersistConversationTurnRequest
import dev.tramai.engine.provider.ProviderCallResult
import dev.tramai.engine.structured.ResumedStructuredResponseRequest
import dev.tramai.engine.structured.StructuredResponseCoordinator
import dev.tramai.engine.tool.ToolCallBatchRequest
import dev.tramai.engine.tool.ToolExecutionRequest
import dev.tramai.engine.tool.ToolInvocationExecutor
import dev.tramai.engine.tool.ToolReinjectionCoordinator
import dev.tramai.core.observation.secondary.SecondaryEffectAuthority
import dev.tramai.core.observation.secondary.SecondaryFailureDiagnostic
import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CancellationException

/**
 * Executes the post-claim approval-resume path.
 *
 * Owns resumed tool execution, sibling-tool processing, reinjection, memory
 * persistence, policy checks, observation completion, and return-kind
 * finalization after an approval has been claimed. The top-level invocation
 * coordinator only sequences execution; the resumed-approval algorithm lives
 * here.
 */
internal class ClaimedResumeExecutionCoordinator(
    private val tokenBudgetCoordinator: TokenBudgetCoordinator,
    private val toolInvocationExecutor: ToolInvocationExecutor,
    private val toolReinjectionCoordinator: ToolReinjectionCoordinator,
    private val toolLoopCoordinator: ToolLoopCoordinator,
    private val structuredResponseCoordinator: StructuredResponseCoordinator,
    private val conversationMemoryCoordinator: ConversationMemoryCoordinator,
    private val policyHelper: PolicyEnforcementHelper,
    private val approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter,
) {

    suspend fun execute(request: ClaimedResumeExecutionRequest): Any? {
        val metadata = request.metadata
        val registered = request.registered
        val tokenBudgetTracker = tokenBudgetCoordinator.restoreTracker(metadata.tokenBudgetSnapshot)
        val toolResult = executeResumedTool(
            request = request,
            tokenBudgetTracker = tokenBudgetTracker,
        )
        val messages = request.rehydratedPayload.messages.toMutableList()
        val loopResult = continueAfterToolResult(
            ContinueAfterToolResultRequest(
                operation = registered.operation,
                messages = messages,
                toolResult = toolResult,
                toolCallId = metadata.toolCallId,
                toolCallIndex = metadata.toolCallIndex,
                correlationId = metadata.correlationId,
                securityContext = metadata.securityContext,
                identity = metadata.identity,
                tokenBudgetTracker = tokenBudgetTracker,
                suspendedToolName = metadata.toolName,
                approvalId = request.command.approvalId,
                conversationId = metadata.conversationId,
                historySize = metadata.historySize,
                resumingApproval = true,
            ),
        )
        return finalizeResumedOperation(
            operation = registered.operation,
            loopResult = loopResult,
            messages = messages,
            correlationId = metadata.correlationId,
            securityContext = metadata.securityContext,
            conversationId = metadata.conversationId,
            historySize = metadata.historySize,
        )
    }
    private suspend fun executeResumedTool(
        request: ClaimedResumeExecutionRequest,
        tokenBudgetTracker: TokenBudgetTracker,
    ): ToolResult {
        val command = request.command
        val metadata = request.metadata
        val registered = request.registered
        val resolvedTool = request.resolvedTool
        val rehydratedPayload = request.rehydratedPayload
        val validatedInput = request.validatedInput
        val expectedArgsDigest = request.expectedArgsDigest
        val validatedToolCall = dev.tramai.core.model.ToolCall(
            id = metadata.toolCallId,
            name = metadata.toolName,
            argumentsJson = validatedInput,
        )
        approvalLifecycleAuditEmitter.onToolExecutionResumed(
            approvalId = command.approvalId,
            workflowRunId = metadata.identity.workflowRunId,
            toolName = metadata.toolName,
            resumedBy = command.resumedBy,
        )
        return try {
            toolInvocationExecutor.execute(
                ToolExecutionRequest(
                    tool = resolvedTool,
                    toolCall = validatedToolCall,
                    operation = registered.operation,
                    correlationId = metadata.correlationId,
                    securityContext = metadata.securityContext,
                    identity = metadata.identity,
                    messages = rehydratedPayload.messages,
                    tokenBudgetTracker = tokenBudgetTracker,
                    conversationId = metadata.conversationId,
                    historySize = metadata.historySize,
                    resumingApproval = true,
                    parentApprovalId = command.approvalId,
                    idempotencyKey = IdempotencyKeyUtil.deriveApprovalKey(command.approvalId, metadata.toolCallId, expectedArgsDigest),
                    allowRenewedApprovedBindingDuringResume = true,
                ),
            )
        } catch (e: dev.tramai.core.exception.NestedApprovalNotSupportedException) {
            throw e
        } catch (e: ToolInvalidInputException) {
            request.uncertainOutcomeEmitter("tool-execution-failed: ${e::class.simpleName ?: "unknown"}")
            throw e
        }
    }
    /**
     * Continues the provider loop after a suspended tool has been executed on resume.
     *
     * 1. Enforces BEFORE_TOOL_RESULT_REINJECTION policy
     * 2. Formats and sanitizes the tool result message
     * 3. Appends the tool message to the messages list
     * 4. Processes any remaining unprocessed tool calls from the same batch
     * 5. Continues the provider loop via the [ToolLoopCoordinator]
     */
    private suspend fun continueAfterToolResult(request: ContinueAfterToolResultRequest): ProviderCallResult {
        val operation = request.operation
        val messages = request.messages
        val toolResult = request.toolResult
        val toolCallId = request.toolCallId
        val correlationId = request.correlationId
        val securityContext = request.securityContext
        val suspendedToolName = request.suspendedToolName

        toolReinjectionCoordinator.reinjectKnownResult(
            request = ToolCallBatchRequest(
                operation = operation,
                messages = messages,
                toolCalls = emptyList(),
                correlationId = correlationId,
                securityContext = securityContext,
                identity = request.identity,
                tokenBudgetTracker = request.tokenBudgetTracker,
                conversationId = request.conversationId,
                historySize = request.historySize,
                resumingApproval = request.resumingApproval,
                parentApprovalId = request.approvalId,
            ),
            toolCallId = toolCallId,
            toolName = suspendedToolName,
            toolResult = toolResult,
        )
        processRemainingResumeToolCalls(request)

        return toolLoopCoordinator.execute(
            ToolLoopContext(
                operation = operation,
                messages = messages,
                tokenBudgetTracker = request.tokenBudgetTracker,
                correlationId = correlationId,
                securityContext = securityContext,
                identity = request.identity,
                conversationId = request.conversationId,
                historySize = request.historySize,
                resumingApproval = request.resumingApproval,
                parentApprovalId = request.approvalId,
            ),
        )
    }
    private suspend fun processRemainingResumeToolCalls(request: ContinueAfterToolResultRequest) {
        if (request.toolCallIndex < 0) return
        val allToolCalls = request.messages
            .lastOrNull { it.role == MessageRole.ASSISTANT && it.toolCalls != null }
            ?.toolCalls
            ?: emptyList()
        val remainingToolCalls = allToolCalls.drop(request.toolCallIndex + 1)
        for ((remainingIdx, toolCall) in remainingToolCalls.withIndex()) {
            appendRemainingResumeToolResult(
                request = request,
                toolCall = toolCall,
                actualIndex = request.toolCallIndex + 1 + remainingIdx,
            )
        }
    }
    private suspend fun appendRemainingResumeToolResult(
        request: ContinueAfterToolResultRequest,
        toolCall: ToolCall,
        actualIndex: Int,
    ) {
        try {
            toolReinjectionCoordinator.processOne(
                request = ToolCallBatchRequest(
                    operation = request.operation,
                    messages = request.messages,
                    toolCalls = listOf(toolCall),
                    correlationId = request.correlationId,
                    securityContext = request.securityContext,
                    identity = request.identity,
                    tokenBudgetTracker = request.tokenBudgetTracker,
                    conversationId = request.conversationId,
                    historySize = request.historySize,
                    resumingApproval = request.resumingApproval,
                    parentApprovalId = request.approvalId,
                ),
                toolCall = toolCall,
                toolCallIndex = actualIndex,
            )
        } catch (e: dev.tramai.core.exception.NestedApprovalNotSupportedException) {
            throw dev.tramai.core.exception.NestedApprovalNotSupportedException(
                approvalId = request.approvalId,
                message = "Nested approval not supported in v1: sibling tool ${toolCall.name} requires approval",
            )
        } catch (e: ApprovalSuspendedException) {
            try {
                approvalLifecycleAuditEmitter.onUncertainOutcome(
                    approvalId = request.approvalId,
                    workflowRunId = request.identity.workflowRunId,
                    toolName = toolCall.name,
                    reason = "nested-approval-not-supported: sibling tool ${toolCall.name} requires approval",
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (auditError: Exception) {
                auditError.rethrowIfCancellation()
                // Epic 5.3: audit-while-reporting-primary. The ConfigurationException
                // below is the primary failure; an audit failure is recorded
                // (authoritative, terminal-recorded) and never substitutes it.
                SecondaryFailureDiagnostic.report(
                    extensionPoint = "approval_lifecycle_audit",
                    callback = "onUncertainOutcome",
                    errorType = auditError.javaClass.simpleName,
                    failurePolicy = "FAIL_CLOSED",
                    authority = SecondaryEffectAuthority.AUTHORITATIVE.name,
                )
            }
            throw ConfigurationException("Nested approval not supported in v1: sibling tool ${toolCall.name} requires approval")
        }
    }
    /**
     * Finalizes a resumed operation for all return kinds that don't need
     * structured parsing. Enforces BEFORE_RESPONSE_RETURN, persists conversation
     * memory, completes the observation, and returns the appropriate result.
     *
     * The [ReturnKind.STRUCTURED] branch delegates parsing, memory, and
     * observation completion to the structured response coordinator.
     */
    private suspend fun finalizeResumedOperation(
        operation: OperationDefinition,
        loopResult: ProviderCallResult,
        messages: MutableList<Message>,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        conversationId: String?,
        historySize: Int,
    ): Any? {
        when (operation.returnKind) {
            ReturnKind.STRING -> {
                // Enforce BEFORE_RESPONSE_RETURN (Fix 3: per-return-kind, not before dispatch)
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                        correlationId = correlationId,
                    ).providerId(loopResult.providerId)
                        .modelName(loopResult.modelName)
                        .applySecurityContext(securityContext)
                        .build()
                )
                // Memory persistence + observation + return (once)
                if (conversationId != null) {
                    conversationMemoryCoordinator.persistTurn(
                        PersistConversationTurnRequest(
                            conversationId,
                            messages,
                            historySize,
                            Message(
                                role = MessageRole.ASSISTANT,
                                content = loopResult.response.content,
                                toolCalls = loopResult.response.toolCalls,
                            ),
                        ),
                    )
                }
                loopResult.observation.onCallCompleted(parseSuccess = null)
                return loopResult.response.content
            }
            ReturnKind.UNIT -> {
                // Enforce BEFORE_RESPONSE_RETURN (Fix 3: per-return-kind, not before dispatch)
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                        correlationId = correlationId,
                    ).providerId(loopResult.providerId)
                        .modelName(loopResult.modelName)
                        .applySecurityContext(securityContext)
                        .build()
                )
                if (conversationId != null) {
                    conversationMemoryCoordinator.persistTurn(
                        PersistConversationTurnRequest(
                            conversationId,
                            messages,
                            historySize,
                            Message(
                                role = MessageRole.ASSISTANT,
                                content = loopResult.response.content,
                                toolCalls = loopResult.response.toolCalls,
                            ),
                        ),
                    )
                }
                loopResult.observation.onCallCompleted(parseSuccess = null)
                loopResult.response.content // consume it
                return Unit
            }
            ReturnKind.STRUCTURED -> {
                return structuredResponseCoordinator.finalizeResumed(
                    ResumedStructuredResponseRequest(
                        operation = operation,
                        loopResult = loopResult,
                        messages = messages,
                        correlationId = correlationId,
                        securityContext = securityContext,
                        conversationId = conversationId,
                        historySize = historySize,
                    ),
                )
            }
            ReturnKind.STREAMING -> throw ConfigurationException("Streaming approval resume not supported")
        }
    }
    private data class ContinueAfterToolResultRequest(
        val operation: OperationDefinition,
        val messages: MutableList<Message>,
        val toolResult: ToolResult,
        val toolCallId: String,
        val toolCallIndex: Int,
        val correlationId: String,
        val securityContext: ExecutionSecurityContext,
        val identity: EngineExecutionIdentity,
        val tokenBudgetTracker: TokenBudgetTracker,
        val suspendedToolName: String = "",
        val approvalId: String = "",
        val conversationId: String? = null,
        val historySize: Int = 0,
        val resumingApproval: Boolean = false,
    )

private fun PolicyContextBuilder.applySecurityContext(
    securityContext: ExecutionSecurityContext,
): PolicyContextBuilder = dataClassification(securityContext.dataClassification)
    .classificationSource(securityContext.classificationSource)
}
