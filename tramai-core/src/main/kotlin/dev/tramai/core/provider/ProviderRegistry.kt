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
 * This class holds no routing state of its own: every operation delegates to the
 * frozen [routingPlan]. The plan instance passed to [from] (or produced by
 * [Builder.build]) is the exact object consulted during execution — never a
 * reconstructed copy.
 *
 * ABI note: the pre-0.6.0 private constructor descriptor
 * `(Map, Map, String, DefaultConstructorMarker)` is deliberately not preserved.
 * That synthetic marker belongs to a private constructor; `DefaultConstructorMarker`
 * itself cannot be instantiated by consumers, and the committed binary-compatibility
 * fixture does not exercise this class, so the descriptor is non-contractual. The
 * public surface (companion factories, builder, resolve/resolveCandidates, and both
 * DTO shapes) is byte-compatible with 0.5.0.
 */
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
