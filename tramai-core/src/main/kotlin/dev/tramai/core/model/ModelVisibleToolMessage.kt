package dev.tramai.core.model

/**
 * Text that is deliberately safe to surface to the model.
 *
 * Construction enforces mechanical safety only: non-blank, bounded length,
 * and no control characters (including newlines, which prevents log and
 * prompt injection). It cannot prove that the text contains no secret.
 * The [trusted] factory name makes the responsibility explicit: only text
 * that the tool author has deliberately reviewed belongs here.
 */
@JvmInline
value class ModelVisibleToolMessage private constructor(
    val value: String,
) {
    companion object {
        const val MAX_LENGTH: Int = 512

        /**
         * Creates a model-visible message from deliberately supplied text.
         *
         * @throws IllegalArgumentException if the text is blank, longer than
         * [MAX_LENGTH] characters, or contains control characters.
         */
        fun trusted(value: String): ModelVisibleToolMessage {
            require(value.isNotBlank()) { "Model-visible message must not be blank" }
            require(value.length <= MAX_LENGTH) {
                "Model-visible message exceeds $MAX_LENGTH characters (${value.length})"
            }
            require(value.none { it.isISOControl() }) {
                "Model-visible message must not contain control characters"
            }
            return ModelVisibleToolMessage(value)
        }
    }
}
