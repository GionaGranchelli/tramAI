package dev.tramai.core.model

interface ModelArtifactVerifier {
    suspend fun verify(registeredModel: RegisteredModel): VerifiedLocalModelArtifact?
}

object NoOpModelArtifactVerifier : ModelArtifactVerifier {
    override suspend fun verify(registeredModel: RegisteredModel): VerifiedLocalModelArtifact? = null
}
