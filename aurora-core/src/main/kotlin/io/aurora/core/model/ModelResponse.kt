package io.aurora.core.model

/**
 * Normalized completion stop reason across providers.
 */
enum class FinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    OTHER,
}

/**
 * Normalized provider response returned to the engine.
 */
data class ModelResponse(
    /** Primary assistant text returned by the provider. */
    val content: String,
    /** Input token count when exposed by the provider. */
    val inputTokens: Int? = null,
    /** Output token count when exposed by the provider. */
    val outputTokens: Int? = null,
    /** Effective model name reported by the provider. */
    val modelUsed: String? = null,
    /** Normalized finish reason for the completion. */
    val finishReason: FinishReason = FinishReason.STOP,
)
