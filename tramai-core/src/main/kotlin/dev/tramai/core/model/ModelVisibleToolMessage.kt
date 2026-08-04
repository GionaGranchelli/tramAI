package dev.tramai.core.model

/**
 * Text that is deliberately safe to surface to the model.
 *
 * Construction enforces mechanical safety only: non-blank, bounded length,
 * and no control characters or line/paragraph separators. It rejects ISO
 * control characters and common multiline/log-forging input. It does NOT
 * detect prompt injection, secrets, Unicode spoofing, or unsafe semantic
 * content. The [trusted] factory name makes the responsibility explicit:
 * only text that the tool author has deliberately reviewed belongs here.
 *
 * The constructor is private; the only way to obtain an instance is
 * [trusted]. The factory is `@JvmStatic` and takes an ordinary [String],
 * so Java callers have the same ergonomic entry point as Kotlin callers.
 */
class ModelVisibleToolMessage private constructor(
    val value: String,
) {
    companion object {
        const val MAX_LENGTH: Int = 512

        /**
         * Creates a model-visible message from deliberately supplied text.
         *
         * @throws IllegalArgumentException if the text is blank, longer than
         * [MAX_LENGTH] characters, or contains control characters, line or
         * paragraph separators, or Unicode FORMAT characters.
         */
        @JvmStatic
        fun trusted(value: String): ModelVisibleToolMessage {
            require(value.isNotBlank()) { "Model-visible message must not be blank" }
            require(value.length <= MAX_LENGTH) {
                "Model-visible message exceeds $MAX_LENGTH characters (${value.length})"
            }
            require(!containsUnsafeCharacter(value)) {
                "Model-visible message must not contain control, separator, or format characters"
            }
            return ModelVisibleToolMessage(value)
        }

        private fun containsUnsafeCharacter(value: String): Boolean =
            value.codePoints().anyMatch { codePoint ->
                Character.isISOControl(codePoint) ||
                    codePoint == 0x2028 ||
                    codePoint == 0x2029 ||
                    Character.getType(codePoint) == Character.FORMAT.toInt()
            }
    }

    override fun equals(other: Any?): Boolean =
        other is ModelVisibleToolMessage && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "ModelVisibleToolMessage(value=<trusted>)"
}
