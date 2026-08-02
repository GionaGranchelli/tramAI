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
     * [modelMessage] is the explicitly trusted model-visible text, or null to
     * resolve the fixed default for [code] internally.
     */
    data class InvalidInput(
        val code: ToolFailureCode = ToolFailureCode.INVALID_INPUT,
        val modelMessage: ModelVisibleToolMessage? = null,
    ) : ToolResult() {
        /**
         * Source-compatibility constructor. Treats [message] as deliberately
         * supplied model-visible text; built-in adapters must not use it.
         */
        @Deprecated(
            message = "Use ToolFailureCode and an explicitly trusted model message",
            replaceWith = ReplaceWith(
                "InvalidInput(code = ToolFailureCode.INVALID_INPUT, modelMessage = ModelVisibleToolMessage.trusted(message))",
            ),
        )
        constructor(message: String) : this(
            code = ToolFailureCode.INVALID_INPUT,
            modelMessage = ModelVisibleToolMessage.trusted(message),
        )
    }

    /**
     * Execution failed with a transient error; engine retries if tool is
     * idempotent. [terminalCode] is the code the engine classifies the
     * terminal exhaustion as.
     */
    data class TransientFailure(
        val cause: Throwable,
        val terminalCode: ToolFailureCode = ToolFailureCode.RETRY_EXHAUSTED,
    ) : ToolResult()

    /**
     * Execution failed with a permanent error; surfaces to caller.
     *
     * [modelMessage] is the explicitly trusted model-visible text, or null to
     * resolve the fixed default for [code] internally. The original exception
     * is intentionally not retained here: it is only available through an
     * explicitly configured [dev.tramai.core.observation.ToolFailureDiagnosticObserver].
     */
    data class PermanentFailure(
        val code: ToolFailureCode = ToolFailureCode.EXECUTION_FAILED,
        val modelMessage: ModelVisibleToolMessage? = null,
    ) : ToolResult() {
        /**
         * Source-compatibility constructor. Treats [message] as deliberately
         * supplied model-visible text; built-in adapters must not use it.
         */
        @Deprecated(
            message = "Use ToolFailureCode and an explicitly trusted model message",
            replaceWith = ReplaceWith(
                "PermanentFailure(code = ToolFailureCode.EXECUTION_FAILED, modelMessage = ModelVisibleToolMessage.trusted(message))",
            ),
        )
        constructor(message: String) : this(
            code = ToolFailureCode.EXECUTION_FAILED,
            modelMessage = ModelVisibleToolMessage.trusted(message),
        )
    }
}
