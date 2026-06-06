package dev.tramai.core.approval

@JvmInline
value class SensitiveToolArguments private constructor(
    private val rawValue: String,
) {
    fun reveal(): String = rawValue

    override fun toString(): String = "[REDACTED]"

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
