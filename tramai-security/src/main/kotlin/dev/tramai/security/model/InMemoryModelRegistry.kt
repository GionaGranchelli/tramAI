package dev.tramai.security.model

import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.RegisteredModel
import java.util.Collections

class InMemoryModelRegistry private constructor(
    private val modelsByKey: Map<ModelKey, RegisteredModel>,
) : ModelRegistry {

    override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? {
        return modelsByKey[ModelKey(providerId, modelName)]
    }

    companion object {
        fun builder(): Builder = Builder()
    }

    class Builder {
        private val modelsByKey = linkedMapOf<ModelKey, RegisteredModel>()

        fun register(model: RegisteredModel): Builder = apply {
            val key = ModelKey(model.providerId, model.modelName)
            require(key !in modelsByKey) {
                "Registered model '${model.providerId}:${model.modelName}' is already registered"
            }
            modelsByKey[key] = model
        }

        fun build(): InMemoryModelRegistry = InMemoryModelRegistry(
            modelsByKey = Collections.unmodifiableMap(modelsByKey.toMap()),
        )
    }

    private data class ModelKey(
        val providerId: String,
        val modelName: String,
    )
}
