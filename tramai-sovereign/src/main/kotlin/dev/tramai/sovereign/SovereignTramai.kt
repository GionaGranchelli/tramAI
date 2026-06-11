package dev.tramai.sovereign

import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.TramaiTool
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedactionAuditEmitter
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.OperationResponseCache
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.engine.TokenBudgetSettings
import dev.tramai.engine.ToolResultFilteringSettings
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter
import dev.tramai.security.audit.AuditStore
import dev.tramai.standalone.Tramai
import kotlin.reflect.KClass

/**
 * Secure-by-default embedded runtime profile for sovereign TramAI deployments.
 *
 * Wraps [Tramai] with mandatory security configuration:
 * - Deny-by-default policy engine
 * - Approved-model registry enforcement (always enabled, non-disableable)
 * - Classification-aware provider routing (always enabled)
 * - Hash-chained policy-decision audit emission
 * - Explicit provider trust zones
 * - Fail-fast build-time provider and route validation
 *
 * Builder requires a [SovereignProfileConfiguration], [ModelRegistry], [AuditStore],
 * and at least one provider with a trust zone.
 *
 * Usage:
 * ```
 * val tramai = SovereignTramai.builder()
 *     .profile(configuration)
 *     .modelRegistry(registry)
 *     .auditStore(auditStore)
 *     .provider(ollamaProvider, name = "ollama", default = true)
 *     .model("llama3.2", "ollama")
 *     .build()
 * ```
 */
class SovereignTramai private constructor(
    private val delegate: Tramai,
) {
    /**
     * Creates a service proxy for the given service type.
     */
    fun <T : Any> create(serviceType: KClass<T>): T = delegate.create(serviceType)

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    /**
     * Describes a fallback route configured during builder assembly.
     */
    private data class FallbackRoute(
        val requestedModelName: String,
        val fallbackModelName: String,
        val providerName: String,
    )

    class Builder {
        private var profileConfiguration: SovereignProfileConfiguration? = null
        private var modelRegistry: ModelRegistry? = null
        private var auditStore: AuditStore? = null
        private val standaloneBuilder = Tramai.builder()

        // Tracking state for build-time validation
        private val registeredProviders = linkedSetOf<String>()
        private val primaryModelRoutes = linkedMapOf<String, String>()
        private val fallbackRoutes = mutableListOf<FallbackRoute>()
        private var defaultProviderName: String? = null

        // --- Required inputs ---

        /**
         * Sets the sovereign profile configuration.
         */
        fun profile(configuration: SovereignProfileConfiguration): Builder = apply {
            this.profileConfiguration = configuration
        }

        /**
         * Sets the approved-model registry.
         */
        fun modelRegistry(registry: ModelRegistry): Builder = apply {
            this.modelRegistry = registry
        }

        /**
         * Sets the audit store for hash-chained policy-decision audit events.
         */
        fun auditStore(store: AuditStore): Builder = apply {
            this.auditStore = store
        }

        // --- Delegated standalone builder methods with tracking ---

        /**
         * Registers a provider with an optional explicit [name].
         *
         * @throws IllegalArgumentException if the provider name is blank,
         *   has surrounding whitespace, or is a duplicate.
         */
        fun provider(
            provider: ModelProvider,
            name: String = provider.providerId(),
            default: Boolean = false,
        ): Builder = apply {
            require(name.isNotBlank()) { "Provider name must not be blank" }
            require(name == name.trim()) { "Provider name must not have surrounding whitespace" }
            require(name !in registeredProviders) { "Duplicate provider registration: $name" }
            registeredProviders.add(name)
            if (default) defaultProviderName = name
            standaloneBuilder.provider(provider, name, default)
        }

        /**
         * Maps a logical model name to a registered provider.
         */
        fun model(
            modelName: String,
            providerName: String,
        ): Builder = apply {
            primaryModelRoutes[modelName] = providerName
            standaloneBuilder.model(modelName, providerName)
        }

        /**
         * Registers one or more tools.
         */
        fun tools(vararg tools: TramaiTool<*, *>): Builder = apply {
            standaloneBuilder.tools(*tools)
        }

        /**
         * Registers tools from an iterable.
         */
        fun tools(tools: Iterable<TramaiTool<*, *>>): Builder = apply {
            standaloneBuilder.tools(tools)
        }

        // --- Optional delegation ---

        fun fallbackModel(
            requestedModelName: String,
            fallbackModelName: String,
            providerName: String,
        ): Builder = apply {
            fallbackRoutes.add(FallbackRoute(requestedModelName, fallbackModelName, providerName))
            standaloneBuilder.fallbackModel(requestedModelName, fallbackModelName, providerName)
        }

        fun fallbackProvider(
            modelName: String,
            providerName: String,
        ): Builder = fallbackModel(
            requestedModelName = modelName,
            fallbackModelName = modelName,
            providerName = providerName,
        )

        fun defaultProvider(providerName: String): Builder = apply {
            this.defaultProviderName = providerName
            standaloneBuilder.defaultProvider(providerName)
        }

        fun observer(observer: OperationObserver): Builder = apply {
            standaloneBuilder.observer(observer)
        }

        fun interceptor(interceptor: OperationInterceptor): Builder = apply {
            standaloneBuilder.interceptor(interceptor)
        }

        fun cache(cache: OperationResponseCache): Builder = apply {
            standaloneBuilder.cache(cache)
        }

        fun circuitBreaker(settings: CircuitBreakerSettings): Builder = apply {
            standaloneBuilder.circuitBreaker(settings)
        }

        fun retryPolicy(settings: RetryPolicySettings): Builder = apply {
            standaloneBuilder.retryPolicy(settings)
        }

        fun tokenBudget(settings: TokenBudgetSettings): Builder = apply {
            standaloneBuilder.tokenBudget(settings)
        }

        fun dlp(interceptor: DlpInterceptor): Builder = apply {
            standaloneBuilder.dlp(interceptor)
        }

        fun dlpRedactionAudit(emitter: DlpRedactionAuditEmitter): Builder = apply {
            standaloneBuilder.dlpRedactionAudit(emitter)
        }

        fun toolResultFiltering(settings: ToolResultFilteringSettings): Builder = apply {
            standaloneBuilder.toolResultFiltering(settings)
        }

        fun engineEventObserver(observer: EngineEventObserver): Builder = apply {
            standaloneBuilder.engineEventObserver(observer)
        }

        // --- Build ---

        /**
         * Builds the [SovereignTramai] instance with fail-fast validation.
         *
         * @throws IllegalStateException if required inputs are missing.
         * @throws IllegalArgumentException if provider or route validation fails.
         */
        fun build(): SovereignTramai {
            val profile = checkNotNull(profileConfiguration) {
                "SovereignProfileConfiguration is required"
            }
            checkNotNull(auditStore) {
                "AuditStore is required for sovereign profile"
            }
            checkNotNull(modelRegistry) {
                "ModelRegistry is required for sovereign profile"
            }

            // Build-time provider and route validation
            require(registeredProviders.isNotEmpty()) {
                "At least one provider must be registered"
            }

            // Every registered provider must be explicitly allowed
            for (p in registeredProviders) {
                require(p in profile.allowedProviders) {
                    "Registered provider '$p' is not in allowedProviders"
                }
            }

            // Every allowed provider must be registered
            for (p in profile.allowedProviders) {
                require(p in registeredProviders) {
                    "Allowed provider '$p' has not been registered"
                }
            }

            // Every registered provider must have an explicit trust zone
            for (p in registeredProviders) {
                require(p in profile.providerZones) {
                    "Registered provider '$p' has no trust zone configured"
                }
            }

            // Every allowed model must have an explicit primary route
            for (m in profile.allowedModels) {
                require(m in primaryModelRoutes) {
                    "Allowed model '$m' has no primary route"
                }
            }

            // Every primary route must target a registered allowed provider
            for ((modelName, providerName) in primaryModelRoutes) {
                require(modelName in profile.allowedModels) {
                    "Primary route for '$modelName' routes a model not in allowedModels"
                }
                require(providerName in registeredProviders) {
                    "Model '$modelName' routes to unknown provider '$providerName'"
                }
                require(providerName in profile.allowedProviders) {
                    "Model '$modelName' routes to non-allowed provider '$providerName'"
                }
            }

            // Fallback routes must target registered providers
            for (fb in fallbackRoutes) {
                require(fb.requestedModelName in profile.allowedModels) {
                    "Fallback source model '${fb.requestedModelName}' is not in allowedModels"
                }
                require(fb.providerName in registeredProviders) {
                    "Fallback route for '${fb.requestedModelName}' targets unknown provider '${fb.providerName}'"
                }
                require(fb.providerName in profile.allowedFallbackProviders) {
                    "Fallback provider '${fb.providerName}' is not in allowedFallbackProviders"
                }
                require(fb.fallbackModelName in profile.allowedModels) {
                    "Fallback model '${fb.fallbackModelName}' is not in allowedModels"
                }
            }

            // Default provider must be registered and allowed
            val defaultName = defaultProviderName
            if (defaultName != null) {
                require(defaultName in registeredProviders) {
                    "Default provider '$defaultName' is not registered"
                }
                require(defaultName in profile.allowedProviders) {
                    "Default provider '$defaultName' is not in allowedProviders"
                }
            }

            val policyConfig: PolicyConfiguration = profile.toPolicyConfiguration()
            val policyEngine = DefaultPolicyEngine(policyConfig)
            val auditEng = AuditEngine(auditStore!!)
            val policyAuditEmitter = AuditEnginePolicyDecisionAuditEmitter(auditEng)

            val tramai = standaloneBuilder
                .policyEngine(policyEngine)
                .policyDecisionAudit(policyAuditEmitter)
                .modelRegistry(modelRegistry!!)
                .modelRegistrySettings(ModelRegistrySettings(enabled = true))
                .build()

            return SovereignTramai(tramai)
        }
    }
}

/**
 * Reified convenience overload for [SovereignTramai.create].
 */
inline fun <reified T : Any> SovereignTramai.create(): T = create(T::class)
