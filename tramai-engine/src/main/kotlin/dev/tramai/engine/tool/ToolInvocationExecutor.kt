package dev.tramai.engine.tool

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ToolInvalidInputException
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolFailureCode
import dev.tramai.core.model.ToolResult
import dev.tramai.core.observation.ToolFailureDiagnosticEvent
import dev.tramai.core.observation.ToolFailureDiagnosticObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class ToolInvocationExecutor(
    private val authorizationCoordinator: ToolAuthorizationCoordinator,
    private val retryPolicy: ToolRetryPolicy,
    private val toolFailureDiagnosticObserver: ToolFailureDiagnosticObserver,
    private val approvalGate: ToolApprovalGate,
) {
    suspend fun execute(request: ToolExecutionRequest): ToolResult {
        val tool = request.tool
        val input = request.toolCall.argumentsJson
        val maxAttempts = if (tool.idempotent) IDEMPOTENT_TOOL_MAX_ATTEMPTS else 1
        repeat(maxAttempts) { attemptIndex ->
            val context = ToolExecutionContext(
                operationName = request.operation.method.name,
                modelName = request.operation.operation.model,
                attemptNumber = attemptIndex,
                conversationId = request.conversationId,
                idempotencyKey = request.idempotencyKey,
                timeout = java.time.Duration.ofMillis(request.operation.operation.timeoutMillis),
            )
            when (val decision = authorizationCoordinator.authorize(request, input)) {
                is ToolAuthorizationDecision.Deny -> throw dev.tramai.core.exception.PolicyViolationException(decision.decision)
                is ToolAuthorizationDecision.RequireApproval -> approvalGate.requireApproval(request, decision.decision, input)
                ToolAuthorizationDecision.Allow -> Unit
            }
            val result = executeToolAttempt(tool, input, context)
            if (result !is ToolResult.TransientFailure) return result
            when (val decision = retryPolicy.decide(result, tool, attemptIndex, maxAttempts)) {
                ToolRetryDecision.Retry -> recordToolFailureDiagnostic(tool, ToolFailureCode.EXECUTION_FAILED, attemptIndex, tool.idempotent, result.cause)
                is ToolRetryDecision.Stop -> {
                    recordToolFailureDiagnostic(tool, ToolFailureCode.EXECUTION_FAILED, attemptIndex, tool.idempotent, result.cause)
                    if (decision.terminalCode == ToolFailureCode.RETRY_EXHAUSTED) {
                        recordToolFailureDiagnostic(tool, ToolFailureCode.RETRY_EXHAUSTED, attemptIndex, false, result.cause)
                    }
                    return ToolResult.PermanentFailure(decision.terminalCode.defaultModelMessage)
                }
            }
        }
        error("Tool retry loop exited without returning")
    }

    private suspend fun executeToolAttempt(tool: ResolvedTool, input: String, context: ToolExecutionContext): ToolResult = try {
        tool.execute(input, context)
    } catch (e: ToolInvalidInputException) {
        recordToolFailureDiagnostic(tool, ToolFailureCode.INVALID_INPUT, context.attemptNumber, retryClassified = false, e)
        ToolResult.InvalidInput(e.safeModelMessage?.value ?: ToolFailureCode.INVALID_INPUT.defaultModelMessage)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        if (tool.idempotent) ToolResult.TransientFailure(e) else {
            recordToolFailureDiagnostic(tool, ToolFailureCode.EXECUTION_FAILED, context.attemptNumber, retryClassified = false, e)
            ToolResult.PermanentFailure(ToolFailureCode.EXECUTION_FAILED.defaultModelMessage)
        }
    }

    private suspend fun recordToolFailureDiagnostic(tool: ResolvedTool, code: ToolFailureCode, attempt: Int, retryClassified: Boolean, failure: Throwable) {
        try {
            toolFailureDiagnosticObserver.record(ToolFailureDiagnosticEvent(toolName = tool.name, code = code, attempt = attempt, retryClassified = retryClassified, failure = failure))
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (e: Exception) {
            e.rethrowIfCancellation()
        }
    }
}
