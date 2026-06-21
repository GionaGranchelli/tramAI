package dev.tramai.core.model

fun interface ModelArtifactVerifier {
    suspend fun verify(registeredModel: RegisteredModel): VerifiedLocalModelArtifact?
}

val NoOpModelArtifactVerifier: ModelArtifactVerifier = ModelArtifactVerifier { null }
