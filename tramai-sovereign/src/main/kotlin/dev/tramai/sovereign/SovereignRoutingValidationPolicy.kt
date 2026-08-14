package dev.tramai.sovereign

import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.security.ProviderTrustZone

/** Internal policy validation for the authoritative provider routing plan. */
internal object SovereignRoutingValidationPolicy {
    fun validate(plan: ProviderRoutingPlan, profile: SovereignProfileConfiguration) {
        val registeredProviders = plan.providers.keys.map { it.value }.toSet()

        require(registeredProviders.isNotEmpty()) { "At least one provider must be registered" }
        registeredProviders.forEach { providerName ->
            require(providerName in profile.allowedProviders) {
                "Registered provider '$providerName' is not in allowedProviders"
            }
        }
        profile.allowedProviders.forEach { providerName ->
            require(providerName in registeredProviders) {
                "Allowed provider '$providerName' has not been registered"
            }
        }
        registeredProviders.forEach { providerName ->
            require(providerName in profile.providerZones) {
                "Registered provider '$providerName' has no trust zone configured"
            }
        }

        profile.allowedModels.forEach { modelName ->
            require(plan.routes[dev.tramai.core.provider.ModelId(modelName)]?.isNotEmpty() == true) {
                "Allowed model '$modelName' has no primary route"
            }
        }
        plan.routes.forEach { (modelId, routes) ->
            val modelName = modelId.value
            val primary = routes.firstOrNull()
            if (primary != null) {
                val providerName = primary.providerId.value
                require(modelName in profile.allowedModels) {
                    "Primary route for '$modelName' routes a model not in allowedModels"
                }
                require(primary.effectiveModelId.value in profile.allowedModels) {
                    "Primary route for '$modelName' targets unapproved effective model '${primary.effectiveModelId.value}'"
                }
                require(providerName in registeredProviders) {
                    "Model '$modelName' routes to unknown provider '$providerName'"
                }
                require(providerName in profile.allowedProviders) {
                    "Model '$modelName' routes to non-allowed provider '$providerName'"
                }
            }
            routes.drop(1).forEach { fallback ->
                val providerName = fallback.providerId.value
                require(modelName in profile.allowedModels) {
                    "Fallback source model '$modelName' is not in allowedModels"
                }
                require(providerName in registeredProviders) {
                    "Fallback route for '$modelName' targets unknown provider '$providerName'"
                }
                require(providerName in profile.allowedFallbackProviders) {
                    "Fallback provider '$providerName' is not in allowedFallbackProviders"
                }
                require(fallback.effectiveModelId.value in profile.allowedModels) {
                    "Fallback model '${fallback.effectiveModelId.value}' is not in allowedModels"
                }
            }
        }

        plan.defaultProvider?.value?.let { providerName ->
            require(providerName in registeredProviders) { "Default provider '$providerName' is not registered" }
            require(providerName in profile.allowedProviders) {
                "Default provider '$providerName' is not in allowedProviders"
            }
        }

        if (profile.deploymentMode == SovereignDeploymentMode.OFFLINE) {
            registeredProviders.forEach { providerName ->
                require(profile.providerZones.getValue(providerName) == ProviderTrustZone.LOCAL) {
                    "offline-profile-non-local-provider-rejected"
                }
            }
            plan.routes.values.forEach { routes ->
                routes.firstOrNull()?.let { primary ->
                    require(profile.providerZones.getValue(primary.providerId.value) == ProviderTrustZone.LOCAL) {
                        "offline-profile-non-local-primary-route-rejected"
                    }
                }
                routes.drop(1).forEach { fallback ->
                    require(profile.providerZones.getValue(fallback.providerId.value) == ProviderTrustZone.LOCAL) {
                        "offline-profile-non-local-fallback-rejected"
                    }
                }
            }
            plan.defaultProvider?.value?.let { providerName ->
                require(profile.providerZones.getValue(providerName) == ProviderTrustZone.LOCAL) {
                    "offline-profile-non-local-default-provider-rejected"
                }
            }
        }
    }
}

internal fun ProviderRoutingPlan.verificationTargets(): Set<Pair<String, String>> =
    buildSet {
        routes.values.forEach { configuredRoutes ->
            configuredRoutes.forEach { route ->
                add(route.providerId.value to route.effectiveModelId.value)
            }
        }
    }
