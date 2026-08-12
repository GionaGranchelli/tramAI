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

/** Compatibility facade for the authoritative [ProviderRoutingPlan]. */
class ProviderRegistry private constructor(private val plan: ProviderRoutingPlan) {
    fun resolve(operation: Operation): ModelProvider = plan.resolve(operation)

    fun resolveCandidates(operation: Operation): List<ResolvedProviderRoute> = plan.resolveCandidates(operation)

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

    val routingPlan: ProviderRoutingPlan get() = plan
}
