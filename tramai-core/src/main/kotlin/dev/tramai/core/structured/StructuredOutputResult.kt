package dev.tramai.core.structured

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
        /** Compatibility-safe summary for ordinary observation; raw diagnostic
         *  material flows only through the diagnostic observer. */
        val errorSummary: String,
        /** Natural-language feedback that the engine can append for another attempt. */
        val feedbackMessage: String,
    ) : StructuredOutputResult {
        /**
         * Original throwable when the failure came from an exception, delivered
         * diagnostic-only to the structured-output failure observer. Excluded from
         * equals/hashCode/toString (class-body property, not part of the primary
         * constructor — the 3-arg constructor descriptor is ABI-stable).
         */
        var failure: Throwable? = null
    }
}
