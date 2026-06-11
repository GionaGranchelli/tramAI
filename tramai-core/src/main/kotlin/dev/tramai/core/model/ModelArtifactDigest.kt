package dev.tramai.core.model

@JvmInline
value class ModelArtifactDigest private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^sha256:[0-9a-f]{64}$")

        fun of(raw: String): ModelArtifactDigest {
            require(raw.isNotBlank()) { "Model artifact digest must not be blank" }
            require(pattern.matches(raw)) {
                "Model artifact digest must match sha256:<64 lowercase hex>"
            }
            return ModelArtifactDigest(raw)
        }
    }
}
