package io.aurora.core.structured

sealed interface StructuredOutputResult {
    data class Success(
        val value: Any,
        val rawResponse: String,
    ) : StructuredOutputResult

    data class Failure(
        val rawResponse: String,
        val errorSummary: String,
        val feedbackMessage: String,
    ) : StructuredOutputResult
}
