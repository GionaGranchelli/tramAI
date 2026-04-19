package dev.tramai.core.provider

import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ConfigurationException

/**
 * Explicit provider registry used to resolve operations to concrete providers.
 */
class ProviderRegistry private constructor(
    private val providersByName: Map<String, ModelProvider>,
    private val modelsToProviderNames: Map<String, String>,
    private val defaultProviderName: String?,
) {
    /**
     * Resolves the provider for an [operation].
     *
     * Resolution order is: explicit operation provider, explicit model mapping, default provider.
     */
    fun resolve(operation: Operation): ModelProvider {
        val explicitProvider = operation.provider.takeIf { it.isNotBlank() }
        if (explicitProvider != null) {
            return providersByName[explicitProvider]
                ?: throw ConfigurationException("Unknown provider '$explicitProvider' requested by operation model '${operation.model}'")
        }

        val registeredProviderName = modelsToProviderNames[operation.model]
        if (registeredProviderName != null) {
            return providersByName[registeredProviderName]
                ?: throw ConfigurationException("Model '${operation.model}' is mapped to unknown provider '$registeredProviderName'")
        }

        val defaultProvider = defaultProviderName?.let(providersByName::get)
        if (defaultProvider != null) {
            return defaultProvider
        }

        throw ConfigurationException(
            "No provider is registered for model '${operation.model}'. Register the model explicitly or configure a default provider.",
        )
    }

    companion object {
        /**
         * Creates a mutable registry builder.
         */
        fun builder(): Builder = Builder()

        /**
         * Creates a registry backed by a single provider and uses it as the default.
         */
        fun singleProvider(provider: ModelProvider): ProviderRegistry = Builder()
            .provider(provider.providerId(), provider, default = true)
            .build()
    }

    /**
     * Builder for an explicit provider registry.
     */
    class Builder {
        private val providersByName = linkedMapOf<String, ModelProvider>()
        private val modelsToProviderNames = linkedMapOf<String, String>()
        private var defaultProviderName: String? = null

        /**
         * Registers a provider under [name].
         */
        fun provider(
            name: String,
            provider: ModelProvider,
            default: Boolean = false,
        ): Builder {
            providersByName[name] = provider
            if (default) {
                defaultProviderName = name
            }
            return this
        }

        /**
         * Maps a logical [modelName] to a registered provider name.
         */
        fun model(
            modelName: String,
            providerName: String,
        ): Builder {
            modelsToProviderNames[modelName] = providerName
            return this
        }

        /**
         * Selects the provider used when an operation does not specify an explicit provider or model mapping.
         */
        fun defaultProvider(providerName: String): Builder {
            defaultProviderName = providerName
            return this
        }

        /**
         * Produces an immutable registry snapshot.
         */
        fun build(): ProviderRegistry = ProviderRegistry(
            providersByName = providersByName.toMap(),
            modelsToProviderNames = modelsToProviderNames.toMap(),
            defaultProviderName = defaultProviderName,
        )
    }
}
