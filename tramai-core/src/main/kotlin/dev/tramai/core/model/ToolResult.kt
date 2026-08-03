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

    /**
     * Input was rejected as invalid; engine feeds back to the model.
     *
     * The [message] is surfaced verbatim to the model. Deprecated: prefer
     * [SafeInvalidInput], which carries a typed [ToolFailureCode] and an
     * explicitly trusted [ModelVisibleToolMessage]. Built-in adapters must
     * not use this variant.
     */
    @Deprecated("Use SafeInvalidInput with a typed code and trusted model message")
    data class InvalidInput(val message: String) : ToolResult()

    /**
     * Execution failed with a permanent error; surfaces to caller.
     *
     * The [message] is surfaced verbatim to the model. Deprecated: prefer
     * [SafePermanentFailure], which carries a typed [ToolFailureCode] and an
     * explicitly trusted [ModelVisibleToolMessage]. Built-in adapters must
     * not use this variant.
     */
    @Deprecated("Use SafePermanentFailure with a typed code and trusted model message")
    data class PermanentFailure(val message: String) : ToolResult()

    /**
     * Execution failed with a transient error; engine retries if the tool is
     * idempotent. On retry exhaustion the engine classifies the terminal
     * failure as [ToolFailureCode.RETRY_EXHAUSTED].
     */
    data class TransientFailure(val cause: Throwable) : ToolResult()

    /**
     * Input was rejected as invalid; engine feeds back to the model.
     *
     * [modelMessage] is the explicitly trusted model-visible text, or null to
     * resolve the fixed default for [code] internally.
     */
    data class SafeInvalidInput(
        val code: ToolFailureCode = ToolFailureCode.INVALID_INPUT,
        val modelMessage: ModelVisibleToolMessage? = null,
    ) : ToolResult()

    /**
     * Execution failed with a permanent error; surfaces to caller.
     *
     * [modelMessage] is the explicitly trusted model-visible text, or null to
     * resolve the fixed default for [code] internally. The original exception
     * is intentionally not retained here: it is only available through an
     * explicitly configured [dev.tramai.core.observation.ToolFailureDiagnosticObserver].
     */
    data class SafePermanentFailure(
        val code: ToolFailureCode = ToolFailureCode.EXECUTION_FAILED,
        val modelMessage: ModelVisibleToolMessage? = null,
    ) : ToolResult()
}
