package dev.tramai.engine.streaming

import dev.tramai.core.exception.TramaiException
import dev.tramai.core.model.StreamChunk
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.budget.TokenBudgetTracker

internal data class StreamingExecutionRequest(
    val operation: OperationDefinition,
    val arguments: List<Any?>,
    val tokenBudgetTracker: TokenBudgetTracker,
    val conversationId: String?,
)

internal sealed class StreamingRouteResult {
    data class Completed(
        val fullText: String,
    ) : StreamingRouteResult()

    data class StartupFailure(
        val error: TramaiException,
    ) : StreamingRouteResult()

    data class TerminalError(
        val errorChunk: StreamChunk.Error,
    ) : StreamingRouteResult()
}

internal class StreamingRouteFinished(
    val result: StreamingRouteResult,
) : RuntimeException(null, null, false, false)
