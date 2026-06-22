package dev.tramai.core.provider

import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ConfigurationException

/**
 * One explicit execution route for a requested model.
 */
data class ProviderRoute(
    val providerName: String,
    val effectiveModelName: String,
)

/**
 * A provider route resolved to a concrete provider instance.
 */
data class ResolvedProviderRoute(
    val providerName: String,
    val provider: ModelProvider,
    val requestedModelName: String,
    val effectiveModelName: String,
)

/**
 * Explicit provider registry used to resolve operations to concrete providers.
 */
class ProviderRegistry private constructor(
    private val providersByName: Map<String, ModelProvider>,
    private val routesByRequestedModel: Map<String, List<ProviderRoute>>,
    private val defaultProviderName: String?,
) {
    /**
     * Resolves the provider for an [operation].
     *
     * Resolution order is: explicit operation provider, explicit model mapping, default provider.
     */
    fun resolve(operation: Operation): ModelProvider {
        return resolveCandidates(operation).first().provider
    }

    /**
     * Resolves the ordered provider routes for an [operation], including any configured fallbacks.
     */
    fun resolveCandidates(operation: Operation): List<ResolvedProviderRoute> {
        val explicitProvider = operation.provider.takeIf { it.isNotBlank() }
        if (explicitProvider != null) {
            val provider = providersByName[explicitProvider]
                ?: throw ConfigurationException("Unknown provider '$explicitProvider' requested by operation model '${operation.model}'")
            return listOf(
                ResolvedProviderRoute(
                    providerName = explicitProvider,
                    provider = provider,
                    requestedModelName = operation.model,
                    effectiveModelName = operation.model,
                ),
            )
        }

        val registeredRoutes = routesByRequestedModel[operation.model]
        if (registeredRoutes != null) {
            return registeredRoutes.map { route ->
                ResolvedProviderRoute(
                    providerName = route.providerName,
                    provider = providersByName[route.providerName]
                        ?: throw ConfigurationException("Model '${operation.model}' is mapped to unknown provider '${route.providerName}'"),
                    requestedModelName = operation.model,
                    effectiveModelName = route.effectiveModelName,
                )
            }
        }

        val defaultProviderName = defaultProviderName
        if (defaultProviderName != null) {
            val defaultProvider = providersByName[defaultProviderName]
                ?: throw ConfigurationException("Default provider '$defaultProviderName' is not registered")
            return listOf(
                ResolvedProviderRoute(
                    providerName = defaultProviderName,
                    provider = defaultProvider,
                    requestedModelName = operation.model,
                    effectiveModelName = operation.model,
                ),
            )
        }

        throw ConfigurationException("No provider is registered for model '${operation.model}'. Register the model explicitly or configure a default provider.")
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
        private val routesByRequestedModel = linkedMapOf<String, List<ProviderRoute>>()
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
            val existingFallbacks = routesByRequestedModel[modelName]
                ?.drop(1)
                .orEmpty()
            routesByRequestedModel[modelName] = listOf(
                ProviderRoute(
                    providerName = providerName,
                    effectiveModelName = modelName,
                ),
            ) + existingFallbacks
            return this
        }

        /**
         * Adds an explicit fallback route for [requestedModelName].
         */
        fun fallbackModel(
            requestedModelName: String,
            fallbackModelName: String,
            providerName: String,
        ): Builder = apply {
            routesByRequestedModel[requestedModelName] =
                routesByRequestedModel.getOrPut(requestedModelName) { emptyList() } + ProviderRoute(
                    providerName = providerName,
                    effectiveModelName = fallbackModelName,
                )
        }

        /**
         * Adds a fallback route that keeps the same model name but uses another provider.
         */
        fun fallbackProvider(
            modelName: String,
            providerName: String,
        ): Builder = fallbackModel(
            requestedModelName = modelName,
            fallbackModelName = modelName,
            providerName = providerName,
        )

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
            routesByRequestedModel = routesByRequestedModel.mapValues { (_, routes) -> routes.toList() },
            defaultProviderName = defaultProviderName,
        )
    }
}
