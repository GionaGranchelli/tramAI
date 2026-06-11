package dev.tramai.core.model

data class RegisteredModel(
    val registryEntryId: String,
    val providerId: String,
    val modelName: String,
    val revision: String,
    val artifactDigest: ModelArtifactDigest? = null,
    val enabled: Boolean = true,
) {
    init {
        validateField("registryEntryId", registryEntryId)
        validateField("providerId", providerId)
        validateField("modelName", modelName)
        validateField("revision", revision)
    }

    private fun validateField(fieldName: String, value: String) {
        require(value.isNotBlank()) { "$fieldName must not be blank" }
        require(value == value.trim()) { "$fieldName must not have surrounding whitespace" }
        require(value.length <= 256) { "$fieldName must be at most 256 characters" }
        require(value.none(Char::isISOControl)) { "$fieldName must not contain control characters" }
    }
}
