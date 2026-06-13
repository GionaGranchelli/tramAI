package dev.tramai.core.model

data class ModelArtifactVerificationSettings(
    val enabled: Boolean = false,
    val requireDigestForLocalModels: Boolean = false,
)
