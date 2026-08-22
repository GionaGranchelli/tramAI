package dev.tramai.spring

import dev.tramai.core.provider.ModelProvider

/**
 * Descriptor contributed by provider adapter auto-configurations.
 *
 * Provider modules construct concrete providers from their own properties and
 * register them as [SpringConfiguredModelProvider] beans. The Spring core only
 * composes: it collects these descriptors as "property-generated providers"
 * and plain [ModelProvider] beans as "user-supplied providers", preserving the
 * bean-over-property precedence and duplicate-detection semantics without
 * knowing anything about a concrete provider.
 */
data class SpringConfiguredModelProvider(
    val providerId: String,
    val provider: ModelProvider,
)
