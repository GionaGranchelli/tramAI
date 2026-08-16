package dev.tramai.engine.tool

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolFailureCode
import dev.tramai.core.model.ToolResult

internal class ToolRetryPolicy {
    fun decide(result: ToolResult.TransientFailure, tool: ResolvedTool, attemptIndex: Int, maxAttempts: Int): ToolRetryDecision {
        result.cause.rethrowIfCancellation()
        if (attemptIndex < maxAttempts - 1) return ToolRetryDecision.Retry
        return ToolRetryDecision.Stop(if (tool.idempotent) ToolFailureCode.RETRY_EXHAUSTED else ToolFailureCode.EXECUTION_FAILED)
    }
}
