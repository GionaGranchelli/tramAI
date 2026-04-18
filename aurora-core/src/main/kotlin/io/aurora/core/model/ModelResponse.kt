package io.aurora.core.model

enum class FinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    OTHER,
}

data class ModelResponse(
    val content: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val modelUsed: String? = null,
    val finishReason: FinishReason = FinishReason.STOP,
)
