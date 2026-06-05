package dev.tramai.core.approval

/**
 * Validated SHA-256 digest in format: sha256:<64 lowercase hex chars>
 */
@JvmInline
value class Sha256Digest private constructor(val value: String) {
    companion object {
        private val PATTERN = Regex("^sha256:[0-9a-f]{64}$")

        fun of(raw: String): Sha256Digest {
            require(PATTERN.matches(raw)) { "Invalid digest format, expected sha256:<64 hex chars>" }
            return Sha256Digest(raw)
        }
    }
}
