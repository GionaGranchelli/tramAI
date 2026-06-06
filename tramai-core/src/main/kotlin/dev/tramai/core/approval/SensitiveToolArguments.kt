package dev.tramai.core.approval

/**
 * Trusted internal payload wrapper for raw tool arguments.
 *
 * - Not a public DTO
 * - Not safe for default serialization
 * - Persistent stores must encrypt arguments at rest
 * - Never serialize reveal()
 */
class SensitiveToolArguments private constructor(
    private val rawValue: String,
) {
    fun reveal(): String = rawValue

    override fun toString(): String = "[REDACTED]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SensitiveToolArguments) return false
        return rawValue == other.rawValue
    }

    override fun hashCode(): Int = rawValue.hashCode()

    companion object {
        fun of(raw: String): SensitiveToolArguments {
            require(raw.length <= MAX_TOOL_ARGUMENTS_LENGTH) {
                "Tool arguments exceed maximum length"
            }
            return SensitiveToolArguments(raw)
        }

        private const val MAX_TOOL_ARGUMENTS_LENGTH = 1_000_000
    }
}
