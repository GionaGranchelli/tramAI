package dev.tramai.core.approval

@JvmInline
value class ApprovalToken private constructor(
    private val rawValue: String,
) {
    fun reveal(): String = rawValue

    override fun toString(): String = "[REDACTED]"

    companion object {
        fun parsePresented(raw: String): ApprovalToken {
            require(raw.isNotBlank()) { "Approval token must not be blank" }
            require(raw.length <= 512) { "Approval token exceeds maximum length" }
            require(raw.none { it.isISOControl() }) { "Approval token must not contain control characters" }
            return ApprovalToken(raw)
        }

        internal fun generated(raw: String): ApprovalToken = parsePresented(raw)
    }
}
