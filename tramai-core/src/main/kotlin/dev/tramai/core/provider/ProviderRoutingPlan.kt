package dev.tramai.core.provider

import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ConfigurationException
import java.util.Collections

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
    val providers: Map<ProviderId, ModelProvider> = Collections.unmodifiableMap(providers.toMap())
    val routes: Map<ModelId, List<PlannedProviderRoute>> = Collections.unmodifiableMap(
        routes.mapValues { (_, configuredRoutes) ->
            Collections.unmodifiableList(configuredRoutes.toList())
        },
    )

    companion object {
        fun builder(): Builder = Builder()
    }

    class Builder {
        private val providers = linkedMapOf<ProviderId, ModelProvider>()
        private val primaryRoutes = linkedMapOf<ModelId, PlannedProviderRoute>()
        private val fallbackRoutes = linkedMapOf<ModelId, MutableList<PlannedProviderRoute>>()
        private val duplicateProviderIds = linkedSetOf<ProviderId>()
        private val duplicatePrimaryModels = linkedSetOf<ModelId>()
        private var defaultProvider: ProviderId? = null

        fun provider(name: String, provider: ModelProvider, default: Boolean = false): Builder = apply {
            val providerId = ProviderId(name)
            if (providerId in providers) duplicateProviderIds += providerId
            providers[providerId] = provider
            if (default) defaultProvider = providerId
        }

        // Registers the explicit primary route for a model. A second primary for the
        // same model is a duplicate configuration error, not a silent replacement.
        fun model(modelName: String, providerName: String): Builder = apply {
            val modelId = ModelId(modelName)
            if (modelId in primaryRoutes) duplicatePrimaryModels += modelId
            primaryRoutes[modelId] = PlannedProviderRoute(ProviderId(providerName), modelId)
        }

        fun fallbackModel(
            requestedModelName: String,
            fallbackModelName: String,
            providerName: String,
        ): Builder = apply {
            val requestedModelId = ModelId(requestedModelName)
            fallbackRoutes.getOrPut(requestedModelId) { mutableListOf() } +=
                PlannedProviderRoute(ProviderId(providerName), ModelId(fallbackModelName))
        }

        fun fallbackProvider(modelName: String, providerName: String): Builder =
            fallbackModel(modelName, modelName, providerName)

        fun defaultProvider(providerName: String): Builder = apply {
            defaultProvider = ProviderId(providerName)
        }

        fun build(): ProviderRoutingPlan {
            validate()
            val composedRoutes = linkedMapOf<ModelId, List<PlannedProviderRoute>>()
            primaryRoutes.forEach { (modelId, primary) ->
                composedRoutes[modelId] = listOf(primary) + fallbackRoutes[modelId].orEmpty()
            }
            return ProviderRoutingPlan(providers, composedRoutes, defaultProvider)
        }

        private fun validate() {
            providers.keys.forEach { validateProviderId(it) }
            if (duplicateProviderIds.isNotEmpty()) {
                throw ConfigurationException("Duplicate provider '${duplicateProviderIds.first().value}'")
            }
            if (duplicatePrimaryModels.isNotEmpty()) {
                throw ConfigurationException("Duplicate primary route for model '${duplicatePrimaryModels.first().value}'")
            }
            // Every model with fallback routes must have an explicit primary. A fallback-only
            // route list would otherwise let a fallback masquerade as the primary at index 0.
            fallbackRoutes.keys.forEach { modelId ->
                if (modelId !in primaryRoutes) {
                    throw ConfigurationException("Model '${modelId.value}' has fallback routes but no primary route")
                }
            }
            primaryRoutes.forEach { (modelId, primary) ->
                validateModelId(modelId)
                validateProviderId(primary.providerId)
                validateModelId(primary.effectiveModelId)
                if (primary.providerId !in providers) {
                    throw ConfigurationException("Primary route for model '${modelId.value}' targets unknown provider '${primary.providerId.value}'")
                }
                fallbackRoutes[modelId].orEmpty().forEach { fallback ->
                    if (fallback == primary) {
                        throw ConfigurationException("Fallback route for model '${modelId.value}' duplicates its primary route")
                    }
                }
            }
            fallbackRoutes.forEach { (modelId, configuredRoutes) ->
                validateModelId(modelId)
                val seenFallbacks = mutableSetOf<PlannedProviderRoute>()
                configuredRoutes.forEach { route ->
                    validateProviderId(route.providerId)
                    validateModelId(route.effectiveModelId)
                    if (route.providerId !in providers) {
                        throw ConfigurationException("Fallback route for model '${modelId.value}' targets unknown provider '${route.providerId.value}'")
                    }
                    if (!seenFallbacks.add(route)) {
                        throw ConfigurationException("Duplicate fallback route for model '${modelId.value}'")
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
            if (modelId.value != modelId.value.trim()) {
                throw ConfigurationException("Model name '${modelId.value}' must not have surrounding whitespace")
            }
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
