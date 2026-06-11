package dev.tramai.sovereign

import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.NoOpModelRegistry
import dev.tramai.core.model.TramaiTool
import dev.tramai.core.observation.NoOpOperationInterceptor
import dev.tramai.core.observation.NoOpOperationObserver
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedactionAuditEmitter
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.NoOpEngineEventObserver
import dev.tramai.engine.NoOpOperationResponseCache
import dev.tramai.engine.OperationResponseCache
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.engine.TokenBudgetSettings
import dev.tramai.engine.ToolResultFilteringSettings
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
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
 * - Approved-model registry enforcement
 * - Classification-aware provider routing
 * - Hash-chained policy-decision audit emission
 * - Explicit provider trust zones
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

    class Builder {
        private var profileConfiguration: SovereignProfileConfiguration? = null
        private var modelRegistry: ModelRegistry? = null
        private var auditStore: AuditStore? = null
        private val standaloneBuilder = Tramai.builder()
        private var modelRegistrySettings: ModelRegistrySettings = ModelRegistrySettings(enabled = true)

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

        // --- Delegated standalone builder methods ---

        /**
         * Registers a provider with an optional explicit [name].
         */
        fun provider(
            provider: ModelProvider,
            name: String = provider.providerId(),
            default: Boolean = false,
        ): Builder = apply {
            standaloneBuilder.provider(provider, name, default)
        }

        /**
         * Maps a logical model name to a registered provider.
         */
        fun model(
            modelName: String,
            providerName: String,
        ): Builder = apply {
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
            standaloneBuilder.fallbackModel(requestedModelName, fallbackModelName, providerName)
        }

        fun fallbackProvider(
            modelName: String,
            providerName: String,
        ): Builder = apply {
            standaloneBuilder.fallbackProvider(modelName, providerName)
        }

        fun defaultProvider(providerName: String): Builder = apply {
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
         */
        fun build(): SovereignTramai {
            val profile = checkNotNull(profileConfiguration) {
                "SovereignProfileConfiguration is required"
            }

            val store = checkNotNull(auditStore) {
                "AuditStore is required for sovereign profile"
            }

            val registry = checkNotNull(modelRegistry) {
                "ModelRegistry is required for sovereign profile"
            }

            val policyConfig: PolicyConfiguration = profile.toPolicyConfiguration()
            val policyEngine = DefaultPolicyEngine(policyConfig)
            val auditEngine = AuditEngine(store)
            val policyAuditEmitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)

            val tramai = standaloneBuilder
                .policyEngine(policyEngine)
                .policyDecisionAudit(policyAuditEmitter)
                .modelRegistry(registry)
                .modelRegistrySettings(modelRegistrySettings)
                .build()

            return SovereignTramai(tramai)
        }

        /**
         * Overrides the default model registry settings.
         * Default is [ModelRegistrySettings] with enabled=true.
         */
        fun modelRegistrySettings(settings: ModelRegistrySettings): Builder = apply {
            this.modelRegistrySettings = settings
        }
    }
}

/**
 * Reified convenience overload for [SovereignTramai.create].
 */
inline fun <reified T : Any> SovereignTramai.create(): T = create(T::class)
