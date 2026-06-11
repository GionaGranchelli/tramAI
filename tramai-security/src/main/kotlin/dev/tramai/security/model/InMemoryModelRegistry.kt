package dev.tramai.security.model

import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.RegisteredModel
import java.util.Collections

class InMemoryModelRegistry private constructor(
    private val modelsByKey: Map<String, RegisteredModel>,
) : ModelRegistry {

    override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? {
        return modelsByKey[key(providerId, modelName)]
    }

    companion object {
        fun builder(): Builder = Builder()

        private fun key(providerId: String, modelName: String): String = "$providerId:$modelName"
    }

    class Builder {
        private val modelsByKey = linkedMapOf<String, RegisteredModel>()

        fun register(model: RegisteredModel): Builder {
            val key = key(model.providerId, model.modelName)
            require(modelsByKey.put(key, model) == null) {
                "Registered model '${model.providerId}:${model.modelName}' is already registered"
            }
            return this
        }

        fun build(): InMemoryModelRegistry = InMemoryModelRegistry(
            modelsByKey = Collections.unmodifiableMap(modelsByKey.toMap()),
        )
    }
}
