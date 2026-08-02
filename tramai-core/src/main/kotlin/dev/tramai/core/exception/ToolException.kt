package dev.tramai.core.exception

import dev.tramai.core.model.ModelVisibleToolMessage

/**
 * Thrown by tool authors to indicate input validation failures.
 *
 * The exception [message] is diagnostic-only: it must never be forwarded to
 * the model or to the calling application. To deliberately surface safe
 * validation feedback to the model, use [withSafeModelMessage] so the text is
 * explicitly marked as model-visible.
 */
class ToolInvalidInputException(
    message: String,
    val safeModelMessage: ModelVisibleToolMessage? = null,
) : TramaiException(message) {

    companion object {
        /**
         * Creates an invalid-input exception whose [message] is diagnostic-only
         * while [modelMessage] is deliberately trusted model-visible text.
         */
        fun withSafeModelMessage(
            message: String,
            modelMessage: String,
        ): ToolInvalidInputException = ToolInvalidInputException(
            message = message,
            safeModelMessage = ModelVisibleToolMessage.trusted(modelMessage),
        )
    }
}
