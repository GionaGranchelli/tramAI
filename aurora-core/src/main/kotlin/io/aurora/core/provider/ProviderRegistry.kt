package io.aurora.core.provider

import io.aurora.core.annotations.Operation
import io.aurora.core.exception.ConfigurationException

class ProviderRegistry private constructor(
    private val providersByName: Map<String, ModelProvider>,
    private val modelsToProviderNames: Map<String, String>,
    private val defaultProviderName: String?,
) {
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
        fun builder(): Builder = Builder()

        fun singleProvider(provider: ModelProvider): ProviderRegistry = Builder()
            .provider(provider.providerId(), provider, default = true)
            .build()
    }

    class Builder {
        private val providersByName = linkedMapOf<String, ModelProvider>()
        private val modelsToProviderNames = linkedMapOf<String, String>()
        private var defaultProviderName: String? = null

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

        fun model(
            modelName: String,
            providerName: String,
        ): Builder {
            modelsToProviderNames[modelName] = providerName
            return this
        }

        fun defaultProvider(providerName: String): Builder {
            defaultProviderName = providerName
            return this
        }

        fun build(): ProviderRegistry = ProviderRegistry(
            providersByName = providersByName.toMap(),
            modelsToProviderNames = modelsToProviderNames.toMap(),
            defaultProviderName = defaultProviderName,
        )
    }
}
