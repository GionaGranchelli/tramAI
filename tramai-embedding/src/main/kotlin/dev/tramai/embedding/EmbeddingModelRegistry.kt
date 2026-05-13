package dev.tramai.embedding

/**
 * Configuration that identifies which [EmbeddingModel] to resolve.
 *
 * @param providerId The symbolic provider identifier (e.g., "openai", "ollama").
 */
data class EmbeddingConfig(
    val providerId: String,
)

/**
 * Registry that maps symbolic names to [EmbeddingModel] instances.
 *
 * Uses a builder pattern consistent with [dev.tramai.core.provider.ProviderRegistry].
 */
class EmbeddingModelRegistry private constructor(
    private val modelsByName: Map<String, EmbeddingModel>,
    private val defaultName: String?,
) {
    /**
     * Resolves a registered embedding model by [name].
     *
     * @throws IllegalArgumentException if no model is registered under the given name.
     */
    fun resolve(name: String): EmbeddingModel {
        return modelsByName[name]
            ?: throw IllegalArgumentException("No embedding model registered under name '$name'. " +
                "Available models: ${modelsByName.keys.sorted().joinToString(", ")}")
    }

    /**
     * Resolves a registered embedding model by [config].
     *
     * @throws IllegalArgumentException if no model is registered for the given configuration.
     */
    fun resolve(config: EmbeddingConfig): EmbeddingModel {
        return resolve(config.providerId)
    }

    /**
     * Resolves the default embedding model.
     *
     * @throws IllegalStateException if no default model was registered.
     */
    fun resolveDefault(): EmbeddingModel {
        val name = defaultName
            ?: throw IllegalStateException("No default embedding model has been registered. " +
                "Register one with register(name, model, default = true).")
        return resolve(name)
    }

    companion object {
        /**
         * Creates a mutable registry builder.
         */
        fun builder(): Builder = Builder()
    }

    class Builder {
        private val modelsByName = linkedMapOf<String, EmbeddingModel>()
        private var defaultName: String? = null

        /**
         * Registers an [EmbeddingModel] under the given [name].
         *
         * @param default if true, this model becomes the default for [resolveDefault].
         * @throws IllegalArgumentException if [name] is already registered.
         */
        fun register(name: String, model: EmbeddingModel, default: Boolean = false): Builder {
            require(modelsByName.put(name, model) == null) {
                "Embedding model '$name' is already registered"
            }
            if (default) {
                defaultName = name
            }
            return this
        }

        /**
         * Produces an immutable registry snapshot.
         */
        fun build(): EmbeddingModelRegistry = EmbeddingModelRegistry(
            modelsByName = modelsByName.toMap(),
            defaultName = defaultName,
        )
    }
}
