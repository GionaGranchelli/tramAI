package dev.tramai.core.model

/**
 * Stable machine-readable classification of a tool failure.
 *
 * Codes drive retry and policy decisions. Human-readable text must never
 * be used for classification. Each code carries the default messages that
 * are safe to surface on the model-visible and caller-visible boundaries.
 */
enum class ToolFailureCode(val value: String) {
    /** Tool input was rejected as invalid. */
    INVALID_INPUT("tool.input.invalid"),

    /** Tool execution failed with a permanent error. */
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

    /** Fixed default text safe to surface to the calling application. */
    val defaultPublicMessage: String
        get() = when (this) {
            INVALID_INPUT -> "Tool input was rejected"
            EXECUTION_FAILED -> "Tool execution failed"
            RETRY_EXHAUSTED -> "Tool execution failed after retry attempts"
        }
}
