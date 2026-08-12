package dev.tramai.core.provider

import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ConfigurationException

@JvmInline
value class ProviderId(val value: String)

@JvmInline
value class ModelId(val value: String)

data class PlannedProviderRoute(
    val providerId: ProviderId,
    val effectiveModelId: ModelId,
)

/** Immutable, authoritative snapshot of configured provider routing. */
class ProviderRoutingPlan private constructor(
    providers: Map<ProviderId, ModelProvider>,
    routes: Map<ModelId, List<PlannedProviderRoute>>,
    val defaultProvider: ProviderId?,
) {
    val providers: Map<ProviderId, ModelProvider> = providers.toMap()
    val routes: Map<ModelId, List<PlannedProviderRoute>> =
        routes.mapValues { (_, configuredRoutes) -> configuredRoutes.toList() }

    companion object {
        fun builder(): Builder = Builder()
    }

    class Builder {
        private val providers = linkedMapOf<ProviderId, ModelProvider>()
        private val routes = linkedMapOf<ModelId, List<PlannedProviderRoute>>()
        private val duplicateProviderIds = linkedSetOf<ProviderId>()
        private var defaultProvider: ProviderId? = null

        fun provider(name: String, provider: ModelProvider, default: Boolean = false): Builder = apply {
            val providerId = ProviderId(name)
            if (providerId in providers) duplicateProviderIds += providerId
            providers[providerId] = provider
            if (default) defaultProvider = providerId
        }

        // Re-registering a primary route intentionally replaces only that route and retains fallbacks.
        fun model(modelName: String, providerName: String): Builder = apply {
            val modelId = ModelId(modelName)
            val existingFallbacks = routes[modelId]?.drop(1).orEmpty()
            routes[modelId] = listOf(PlannedProviderRoute(ProviderId(providerName), modelId)) + existingFallbacks
        }

        fun fallbackModel(
            requestedModelName: String,
            fallbackModelName: String,
            providerName: String,
        ): Builder = apply {
            val requestedModelId = ModelId(requestedModelName)
            routes[requestedModelId] = routes.getOrPut(requestedModelId) { emptyList() } +
                PlannedProviderRoute(ProviderId(providerName), ModelId(fallbackModelName))
        }

        fun fallbackProvider(modelName: String, providerName: String): Builder =
            fallbackModel(modelName, modelName, providerName)

        fun defaultProvider(providerName: String): Builder = apply {
            defaultProvider = ProviderId(providerName)
        }

        fun build(): ProviderRoutingPlan {
            validate()
            return ProviderRoutingPlan(providers, routes, defaultProvider)
        }

        private fun validate() {
            providers.keys.forEach { providerId -> validateProviderId(providerId) }
            if (duplicateProviderIds.isNotEmpty()) {
                throw ConfigurationException("Duplicate provider '${duplicateProviderIds.first().value}'")
            }
            routes.forEach { (requestedModelId, configuredRoutes) ->
                validateModelId(requestedModelId)
                val seenFallbacks = mutableSetOf<PlannedProviderRoute>()
                configuredRoutes.forEachIndexed { index, route ->
                    validateProviderId(route.providerId)
                    validateModelId(route.effectiveModelId)
                    if (route.providerId !in providers) {
                        val kind = if (index == 0) "Primary" else "Fallback"
                        throw ConfigurationException("$kind route for model '${requestedModelId.value}' targets unknown provider '${route.providerId.value}'")
                    }
                    if (index > 0 && !seenFallbacks.add(route)) {
                        throw ConfigurationException("Duplicate fallback route for model '${requestedModelId.value}'")
                    }
                }
            }
            defaultProvider?.let { providerId ->
                validateProviderId(providerId)
                if (providerId !in providers) {
                    throw ConfigurationException("Default provider '${providerId.value}' is not registered")
                }
            }
        }

        private fun validateProviderId(providerId: ProviderId) {
            if (providerId.value.isBlank()) throw ConfigurationException("Provider name must not be blank")
            if (providerId.value != providerId.value.trim()) {
                throw ConfigurationException("Provider name '${providerId.value}' must not have surrounding whitespace")
            }
        }

        private fun validateModelId(modelId: ModelId) {
            if (modelId.value.isBlank()) throw ConfigurationException("Model name must not be blank")
        }
    }
}

/** Resolves execution candidates from this immutable routing snapshot. */
fun ProviderRoutingPlan.resolveCandidates(operation: Operation): List<ResolvedProviderRoute> {
    val explicitProvider = operation.provider.takeIf { it.isNotBlank() }
    if (explicitProvider != null) {
        val provider = providers[ProviderId(explicitProvider)]
            ?: throw ConfigurationException("Unknown provider '$explicitProvider' requested by operation model '${operation.model}'")
        return listOf(ResolvedProviderRoute(explicitProvider, provider, operation.model, operation.model))
    }

    val requestedModelId = ModelId(operation.model)
    val registeredRoutes = routes[requestedModelId]
    if (registeredRoutes != null) {
        return registeredRoutes.map { route ->
            ResolvedProviderRoute(
                providerName = route.providerId.value,
                provider = providers[route.providerId]
                    ?: throw ConfigurationException("Model '${operation.model}' is mapped to unknown provider '${route.providerId.value}'"),
                requestedModelName = operation.model,
                effectiveModelName = route.effectiveModelId.value,
            )
        }
    }

    val defaultProviderId = defaultProvider
    if (defaultProviderId != null) {
        val defaultProvider = providers[defaultProviderId]
            ?: throw ConfigurationException("Default provider '${defaultProviderId.value}' is not registered")
        return listOf(ResolvedProviderRoute(defaultProviderId.value, defaultProvider, operation.model, operation.model))
    }

    throw ConfigurationException("No provider is registered for model '${operation.model}'. Register the model explicitly or configure a default provider.")
}

fun ProviderRoutingPlan.resolve(operation: Operation): ModelProvider = resolveCandidates(operation).first().provider
