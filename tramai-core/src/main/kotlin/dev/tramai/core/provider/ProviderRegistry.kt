package dev.tramai.core.provider

import dev.tramai.core.annotations.Operation

/** One explicit execution route for a requested model. */
data class ProviderRoute(val providerName: String, val effectiveModelName: String)

/** A provider route resolved to a concrete provider instance. */
data class ResolvedProviderRoute(
    val providerName: String,
    val provider: ModelProvider,
    val requestedModelName: String,
    val effectiveModelName: String,
)

/**
 * Compatibility facade for the authoritative [ProviderRoutingPlan].
 *
 * The primary constructor deliberately keeps the pre-0.6.0 signature
 * `(Map, Map, String)` so the synthetic `DefaultConstructorMarker` descriptor
 * recorded in tramai-core.api stays byte-identical for 0.5.0 consumers.
 * The plan-backed secondary constructor is the real construction path
 * (`from`, `Builder.build`) and restores the caller's frozen plan instance
 * after delegation.
 */
class ProviderRegistry private constructor(
    private val providersByName: Map<String, ModelProvider>,
    private val routesByRequestedModel: Map<String, List<ProviderRoute>>,
    private val defaultProviderName: String?,
) {
    private var plan: ProviderRoutingPlan? = null

    private val resolvedPlan: ProviderRoutingPlan
        get() = plan ?: buildPlan(providersByName, routesByRequestedModel, defaultProviderName).also { plan = it }

    /**
     * Plan-backed construction path used by [from] and [Builder.build]. Delegates
     * through the legacy primary for ABI, then restores the exact frozen plan
     * instance so the object consulted during execution is the object validated
     * during composition — never a reconstructed copy.
     */
    private constructor(plan: ProviderRoutingPlan) : this(emptyMap(), emptyMap(), null) {
        this.plan = plan
    }

    fun resolve(operation: Operation): ModelProvider = resolvedPlan.resolve(operation)

    fun resolveCandidates(operation: Operation): List<ResolvedProviderRoute> =
        resolvedPlan.resolveCandidates(operation)

    companion object {
        fun builder(): Builder = Builder()

        fun singleProvider(provider: ModelProvider): ProviderRegistry {
            return builder()
                .provider(provider.providerId(), provider, default = true)
                .build()
        }

        fun from(plan: ProviderRoutingPlan): ProviderRegistry = ProviderRegistry(plan)
    }

    class Builder {
        private val planBuilder = ProviderRoutingPlan.builder()

        fun provider(name: String, provider: ModelProvider, default: Boolean = false): Builder = apply {
            planBuilder.provider(name, provider, default)
        }

        fun model(modelName: String, providerName: String): Builder = apply { planBuilder.model(modelName, providerName) }

        fun fallbackModel(requestedModelName: String, fallbackModelName: String, providerName: String): Builder = apply {
            planBuilder.fallbackModel(requestedModelName, fallbackModelName, providerName)
        }

        fun fallbackProvider(modelName: String, providerName: String): Builder = apply {
            planBuilder.fallbackProvider(modelName, providerName)
        }

        fun defaultProvider(providerName: String): Builder = apply { planBuilder.defaultProvider(providerName) }

        fun build(): ProviderRegistry {
            return ProviderRegistry(planBuilder.build())
        }
    }

    val routingPlan: ProviderRoutingPlan get() = resolvedPlan

    private fun buildPlan(
        providersByName: Map<String, ModelProvider>,
        routesByRequestedModel: Map<String, List<ProviderRoute>>,
        defaultProviderName: String?,
    ): ProviderRoutingPlan {
        val builder = ProviderRoutingPlan.builder()
        providersByName.forEach { (name, provider) -> builder.provider(name, provider) }
        routesByRequestedModel.forEach { (modelName, routes) ->
            routes.forEachIndexed { index, route ->
                if (index == 0) {
                    builder.model(modelName, route.providerName)
                } else {
                    builder.fallbackModel(modelName, route.effectiveModelName, route.providerName)
                }
            }
        }
        defaultProviderName?.let { builder.defaultProvider(it) }
        return builder.build()
    }
}
