package io.aurora.core.model

import io.aurora.core.exception.AuroraException

/**
 * Common token usage metrics reported by providers.
 */
data class UsageMetrics(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

/**
 * Incremental data chunk emitted during a streaming operation.
 */
sealed class StreamChunk {
    /**
     * A single token or text fragment emitted by the model.
     */
    data class Token(val text: String) : StreamChunk()

    /**
     * The terminal event indicating the stream completed successfully.
     */
    data class Complete(
        val fullText: String,
        val usage: UsageMetrics = UsageMetrics(),
    ) : StreamChunk()

    /**
     * A terminal event indicating the stream failed during execution.
     */
    data class Error(val cause: AuroraException) : StreamChunk()
}
