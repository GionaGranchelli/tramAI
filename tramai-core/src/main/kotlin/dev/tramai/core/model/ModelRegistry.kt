package dev.tramai.core.model

interface ModelRegistry {
    suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel?
}

object NoOpModelRegistry : ModelRegistry {
    override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? = null
}
