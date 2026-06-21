package dev.tramai.core.model

fun interface ModelRegistry {
    suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel?
}

val NoOpModelRegistry: ModelRegistry = ModelRegistry { _, _ -> null }
