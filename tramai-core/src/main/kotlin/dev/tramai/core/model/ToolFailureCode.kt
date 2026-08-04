package dev.tramai.core.model

/**
 * Stable machine-readable diagnostic classification of a tool failure.
 *
 * Codes classify failures for diagnostics and select fixed model-visible
 * defaults. Retry remains driven by [ToolResult.TransientFailure] and tool
 * idempotency; policy does not consume these codes. Caller-visible failure
 * mapping remains a later Epic 1.2 slice.
 */
enum class ToolFailureCode(val value: String) {
    /** Tool input was rejected as invalid. */
    INVALID_INPUT("tool.input.invalid"),

    /** A tool execution attempt failed. */
    EXECUTION_FAILED("tool.execution.failed"),

    /** Retryable tool execution exhausted all configured attempts. */
    RETRY_EXHAUSTED("tool.execution.retry_exhausted"),
    ;

    /** Fixed default text safe to feed back into the model conversation. */
    val defaultModelMessage: String
        get() = when (this) {
            INVALID_INPUT -> "Invalid tool input"
            EXECUTION_FAILED -> "Tool execution failed"
            RETRY_EXHAUSTED -> "Tool execution failed"
        }
}
