package dev.tramai.core.model

import dev.tramai.core.exception.TramaiException

/**
 * Common token usage metrics reported by providers.
 */
data class UsageMetrics(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val thinkingTokens: Int? = null,
    val imageCount: Int = 0,
    val imageTokensEstimate: Int = 0,
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
    data class Error(val cause: TramaiException) : StreamChunk()
}
