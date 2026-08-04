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

    /** Input was rejected as invalid; engine feeds [message] back to the model. */
    data class InvalidInput(val message: String) : ToolResult()

    /** Execution failed with a permanent error; [message] is fed back to the model. */
    data class PermanentFailure(val message: String) : ToolResult()

    /**
     * Execution failed with a transient error; engine retries if the tool is
     * idempotent. On retry exhaustion the engine classifies the terminal
     * failure as [ToolFailureCode.RETRY_EXHAUSTED].
     */
    data class TransientFailure(val cause: Throwable) : ToolResult()

    companion object {
        /** Validated message (or the INVALID_INPUT default) inside an [InvalidInput]. */
        @JvmStatic
        fun safeInvalidInput(modelMessage: ModelVisibleToolMessage? = null): InvalidInput =
            InvalidInput(modelMessage?.value ?: ToolFailureCode.INVALID_INPUT.defaultModelMessage)

        /** Validated message (or the EXECUTION_FAILED default) inside a [PermanentFailure]. */
        @JvmStatic
        fun safePermanentFailure(modelMessage: ModelVisibleToolMessage? = null): PermanentFailure =
            PermanentFailure(modelMessage?.value ?: ToolFailureCode.EXECUTION_FAILED.defaultModelMessage)
    }
}
