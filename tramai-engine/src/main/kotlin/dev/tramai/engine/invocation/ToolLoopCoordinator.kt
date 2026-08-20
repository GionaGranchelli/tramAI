package dev.tramai.engine.invocation

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.TokenBudgetExceededException
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.SecondaryFailureRecording
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.budget.TokenBudgetCoordinator
import dev.tramai.engine.budget.TokenBudgetTracker
import dev.tramai.engine.provider.AttemptCounter
import dev.tramai.engine.provider.ProviderCallResult
import dev.tramai.engine.provider.ProviderExecutionCoordinator
import dev.tramai.engine.provider.ProviderExecutionRequest
import dev.tramai.engine.tool.ToolCallBatchRequest
import dev.tramai.engine.tool.ToolExposureCoordinator
import dev.tramai.engine.tool.ToolReinjectionCoordinator
import dev.tramai.engine.ToolRegistry
import kotlinx.coroutines.CancellationException

/**
 * Runs the provider ↔ tool loop for one operation: at most five
 * provider iterations, normalizing unregistered tool calls, enforcing the
 * token budget, and ordering observation completion exactly as the original
 * handler did.
 */
internal class ToolLoopCoordinator(
    private val providerExecutionCoordinator: ProviderExecutionCoordinator,
    private val toolExposureCoordinator: ToolExposureCoordinator,
    private val tokenBudgetCoordinator: TokenBudgetCoordinator,
    private val toolRegistry: ToolRegistry,
    private val toolReinjectionCoordinator: ToolReinjectionCoordinator,
) {
    suspend fun execute(
        context: ToolLoopContext,
    ): ProviderCallResult {
        val operation = context.operation
        val messages = context.messages
        val tokenBudgetTracker = context.tokenBudgetTracker
        val correlationId = context.correlationId
        val securityContext = context.securityContext
        val maxToolLoops = 5 // Guard against infinite tool loops
        val attemptCounter = AttemptCounter()
        repeat(maxToolLoops) {
            val result = providerExecutionCoordinator.execute(
                ProviderExecutionRequest(
                    operation = operation,
                    messages = messages,
                    attemptCounter = attemptCounter,
                    correlationId = correlationId,
                    securityContext = securityContext,
                    beforeRoute = { toolExposureCoordinator.enforce(operation, correlationId, securityContext) },
                ),
            )
            try {
                tokenBudgetCoordinator.enforce(
                    tracker = tokenBudgetTracker,
                    response = result.response,
                    observation = result.observation,
                    providerId = result.providerId,
                    modelName = result.modelName,
                )
            } catch (error: TokenBudgetExceededException) {
                result.observation.onCallCompleted(parseSuccess = null)
                throw error
            }

            val toolCalls = result.response.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                return result
            }

            // Normalize unregistered tool calls: replace unknown names with safe placeholder
            val normalizedToolCalls = toolCalls.map { toolCall ->
                if (toolRegistry.resolve(toolCall.name) == null) {
                    toolCall.copy(name = UNREGISTERED_TOOL_NAME, argumentsJson = "{}")
                } else {
                    toolCall
                }
            }

            // Append assistant message with normalized tool calls
            messages += Message(
                role = MessageRole.ASSISTANT,
                content = result.response.content,
                toolCalls = normalizedToolCalls,
            )

            // Tool execution must complete before the observation is finalised,
            // so that cancellation during tool execution calls onCallCancelled
            // instead of onCallCompleted.
            // The try covers only tool reinjection, not onCallCompleted:
            // if the observer throws after successful tool execution, that
            // failure is suppressed on the process error rather than causing
            // a duplicate onCallCompleted call or invalidating the side effect.
            try {
                toolReinjectionCoordinator.process(
                    ToolCallBatchRequest(
                        operation = operation,
                        messages = messages,
                        toolCalls = normalizedToolCalls,
                        correlationId = correlationId,
                        securityContext = securityContext,
                        identity = context.identity,
                        tokenBudgetTracker = tokenBudgetTracker,
                        conversationId = context.conversationId,
                        historySize = context.historySize,
                        resumingApproval = context.resumingApproval,
                        parentApprovalId = context.parentApprovalId,
                    ),
                )
            } catch (cancellation: CancellationException) {
                result.observation.completeCancellation(cancellation)
                throw cancellation
            } catch (error: Throwable) {
                error.rethrowIfCancellation()

                // Suppress observer failure on the process error so that
                // a failing onCallCompleted cannot duplicate the callback,
                // and this non-suspend helper keeps the cancellation scanner
                // satisfied.
                result.observation.completeAfterToolProcessing(primaryError = error)

                throw error
            }

            result.observation.completeAfterToolProcessing()
        }
        error("Exceeded maximum tool call loops ($maxToolLoops)")
    }
    private fun OperationObservation.completeCancellation(cancellation: CancellationException) {
        try {
            onCallCancelled()
        } catch (observerError: Throwable) {
            cancellation.addSuppressed(observerError)
        }
    }
    /**
     * Finalises tool-processing observation without a suspend boundary,
     * so the cancellation scanner does not flag a broad [Throwable] catch.
     *
     * When [primaryError] is provided, observer failure is suppressed on it.
     * When it is null (successful tool processing), observer failure is
     * logged as a warning but does not invalidate the completed side effect.
     */
    private fun OperationObservation.completeAfterToolProcessing(
        primaryError: Throwable? = null,
    ) {
        try {
            onCallCompleted(parseSuccess = null)
        } catch (observerError: Throwable) {
            if (primaryError != null) {
                primaryError.addSuppressed(observerError)
            } else {
                System.getLogger("dev.tramai.engine.TramaiEngine").log(
                    System.Logger.Level.WARNING,
                    "Operation observer failed after successful tool processing",
                    observerError,
                )
            }
        }
        // Epic 5.3: the failure-isolating observation contains onCallCompleted
        // failures (so a success path is never invalidated by telemetry), but
        // records them; surface the recorded failure exactly as the direct
        // throw path above — suppressed on the primary error, warning otherwise.
        (this as? SecondaryFailureRecording)?.lastCompletionFailure?.let { observerError ->
            if (primaryError != null) {
                primaryError.addSuppressed(observerError)
            } else {
                System.getLogger("dev.tramai.engine.TramaiEngine").log(
                    System.Logger.Level.WARNING,
                    "Operation observer failed after successful tool processing",
                    observerError,
                )
            }
        }
    }
}

internal data class ToolLoopContext(
    val operation: OperationDefinition,
    val messages: MutableList<Message>,
    val tokenBudgetTracker: TokenBudgetTracker,
    val correlationId: String,
    val securityContext: ExecutionSecurityContext,
    val identity: EngineExecutionIdentity,
    val conversationId: String? = null,
    val historySize: Int = 0,
    val resumingApproval: Boolean = false,
    val parentApprovalId: String? = null,
)

private const val UNREGISTERED_TOOL_NAME = "unregistered_tool"
