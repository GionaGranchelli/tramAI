package dev.tramai.engine.provider

import dev.tramai.core.model.RegisteredModel
import dev.tramai.engine.ModelRegistryEnforcer
import kotlinx.coroutines.CancellationException

internal class ProviderAuthorizationService(private val modelRegistryEnforcer: ModelRegistryEnforcer) {
    suspend fun authorize(providerId: String, modelName: String): RegisteredModel? = try {
        modelRegistryEnforcer.authorize(providerId, modelName)
    } catch (error: CancellationException) {
        throw error
    }
}
