package io.aurora.core.structured

/**
 * Result emitted by a [StructuredOutputHandler].
 */
sealed interface StructuredOutputResult {
    /**
     * Parsed and validated structured value.
     */
    data class Success(
        /** Parsed value materialized for the requested return type. */
        val value: Any,
        /** Original response used to produce [value]. */
        val rawResponse: String,
    ) : StructuredOutputResult

    /**
     * Parse or validation failure, with feedback suitable for a retry prompt.
     */
    data class Failure(
        /** Original failing response. */
        val rawResponse: String,
        /** Stable summary intended for logging and observation. */
        val errorSummary: String,
        /** Natural-language feedback that the engine can append for another attempt. */
        val feedbackMessage: String,
    ) : StructuredOutputResult
}
