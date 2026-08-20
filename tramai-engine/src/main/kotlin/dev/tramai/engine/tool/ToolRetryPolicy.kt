package dev.tramai.engine.tool

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolFailureCode
import dev.tramai.core.model.ToolResult

internal class ToolRetryPolicy {
    /**
     * Retry requires THREE independent facts: the failure is retryable (it is a
     * [ToolResult.TransientFailure]), repeating the tool is safe ([ResolvedTool.idempotent]),
     * and an attempt budget remains. A non-idempotent tool never executes twice even
     * when the failure is transient; the terminal code is then [ToolFailureCode.EXECUTION_FAILED].
     */
    fun decide(result: ToolResult.TransientFailure, tool: ResolvedTool, attemptIndex: Int, maxAttempts: Int): ToolRetryDecision {
        result.cause.rethrowIfCancellation()
        if (!tool.idempotent) return ToolRetryDecision.Stop(ToolFailureCode.EXECUTION_FAILED)
        if (attemptIndex < maxAttempts - 1) return ToolRetryDecision.Retry
        return ToolRetryDecision.Stop(ToolFailureCode.RETRY_EXHAUSTED)
    }
}
