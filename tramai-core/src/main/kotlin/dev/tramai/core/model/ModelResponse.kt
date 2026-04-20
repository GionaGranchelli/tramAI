package dev.tramai.core.model

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
 * Model-initiated tool call request.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * Normalized provider response returned to the engine.
 */
data class ModelResponse(
    /** Primary assistant text returned by the provider. */
    val content: String,
    /** Optional model-initiated tool calls. */
    val toolCalls: List<ToolCall>? = null,
    /** Input token count when exposed by the provider. */
    val inputTokens: Int? = null,
    /** Output token count when exposed by the provider. */
    val outputTokens: Int? = null,
    /** Effective model name reported by the provider. */
    val modelUsed: String? = null,
    /** Normalized finish reason for the completion. */
    val finishReason: FinishReason = FinishReason.STOP,
) {
    /**
     * Returns the sum of input and output tokens, or null if usage metrics are unavailable.
     */
    fun totalTokens(): Int? {
        val input = inputTokens ?: return null
        val output = outputTokens ?: return null
        return input + output
    }
}
