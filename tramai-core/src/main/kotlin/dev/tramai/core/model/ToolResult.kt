package dev.tramai.core.model

/**
 * Normalized outcome of a tool execution.
 */
sealed class ToolResult {
    /** Successful execution with a serialized JSON result. */
    data class Success(
        val value: Any,
        /** Optional multi-part content (text, images, etc.) to feed back to the provider. */
        val contentParts: List<ContentPart>? = null,
    ) : ToolResult()

    /** Input was rejected as invalid; engine feeds back to the model. */
    data class InvalidInput(val message: String) : ToolResult()

    /** Execution failed with a transient error; engine retries if tool is idempotent. */
    data class TransientFailure(val cause: Throwable) : ToolResult()

    /** Execution failed with a permanent error; surfaces to caller. */
    data class PermanentFailure(val message: String) : ToolResult()
}
