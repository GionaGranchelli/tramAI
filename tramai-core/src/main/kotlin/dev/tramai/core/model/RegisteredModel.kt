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
}
