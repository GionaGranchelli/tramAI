package dev.tramai.spring

import dev.tramai.core.provider.ModelProvider
import org.springframework.beans.factory.ObjectProvider

/**
 * Shared provider-resolution seam for both runtime profiles.
 *
 * Collects property-generated providers ([SpringConfiguredModelProvider]
 * descriptors contributed by `tramai-spring-provider-*` adapter modules) and
 * explicit user [ModelProvider] beans with identical semantics for standard
 * and sovereign: unique beans override property-backed providers, and genuine
 * duplicates are passed through so the canonical runtime builder rejects
 * them deterministically.
 *
 * Lives in Spring core so no runtime profile knows a concrete provider type.
 * Public because it crosses the module boundary to the sovereign integration;
 * it is a composition seam, not an application-facing API.
 */
object SpringProviderResolution {

    /**
     * Returns providers as (providerId, provider) pairs in registration order:
     * unique property providers, unique bean providers (bean-over-property
     * precedence), then duplicates of each kind unchanged.
     */
    fun resolve(
        springConfiguredProviders: ObjectProvider<SpringConfiguredModelProvider>,
        beanProviders: ObjectProvider<ModelProvider>,
    ): List<Pair<String, ModelProvider>> {
        val propertyProviders: List<Pair<String, ModelProvider>> =
            springConfiguredProviders.orderedStream()
                .map { it.providerId to it.provider }
                .toList()

        val beanProviderList = beanProviders.orderedStream().toList()
        val beanProviderCounts = beanProviderList.groupingBy { it.providerId() }.eachCount()
        val uniqueBeanProviders = beanProviderList.filter { beanProviderCounts.getValue(it.providerId()) == 1 }
        val duplicateBeanProviders = beanProviderList.filter { beanProviderCounts.getValue(it.providerId()) > 1 }

        val propertyProviderCounts = propertyProviders.groupingBy { it.first }.eachCount()
        val duplicatePropertyProviders = propertyProviders.filter { propertyProviderCounts.getValue(it.first) > 1 }
        val uniquePropertyProviders = propertyProviders.filter { propertyProviderCounts.getValue(it.first) == 1 }

        val providersById = uniquePropertyProviders.toMap() + uniqueBeanProviders.associate { it.providerId() to it }
        return buildList {
            providersById.forEach { (providerId, provider) -> add(providerId to provider) }
            duplicatePropertyProviders.forEach { add(it) }
            duplicateBeanProviders.forEach { add(it.providerId() to it) }
        }
    }
}
