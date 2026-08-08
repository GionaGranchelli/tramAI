package dev.tramai.core.structured

/**
 * Coarse, stable machine-readable classification for structured-output failures.
 * Deliberately avoids Jackson/parser implementation detail — that belongs in
 * diagnostic detail, not a stable public taxonomy.
 */
enum class StructuredOutputFailureCode(val value: String) {
    /** Contract/schema preparation failed. */
    CONTRACT_FAILED("structured_output.contract_failed"),
    /** Model output could not be parsed or validated. */
    OUTPUT_REJECTED("structured_output.output_rejected"),
    /** Allowed structured-output attempts were exhausted. */
    REPAIR_EXHAUSTED("structured_output.repair_exhausted"),
    /** Structured-output handler failed unexpectedly. */
    HANDLER_FAILED("structured_output.handler_failed"),
}
