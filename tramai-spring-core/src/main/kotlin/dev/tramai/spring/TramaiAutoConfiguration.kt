package dev.tramai.spring

import dev.tramai.core.observation.CompositeOperationInterceptor
import dev.tramai.core.observation.NoOpOperationInterceptor
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.secret.SecretValueResolver
import dev.tramai.anthropic.AnthropicProvider
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedactionAuditEmitter
import dev.tramai.openai.CodexAuthFileTokenSource
import dev.tramai.openai.ExperimentalCodexAuth
import dev.tramai.openai.OpenAiCompatibleProvider
import dev.tramai.openai.OpenAiProvider
import dev.tramai.ollama.OllamaProvider
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.InMemoryOperationResponseCache
import dev.tramai.engine.NoOpOperationResponseCache
import dev.tramai.engine.OperationResponseCache
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.engine.TokenBudgetSettings
import dev.tramai.engine.ToolResultFilteringSettings
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.NoOpEngineEventObserver
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.standalone.Tramai
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.nio.file.Path

private data class TramaiBeanDependencies(
    val modelProviders: ObjectProvider<ModelProvider>,
    val operationResponseCache: ObjectProvider<OperationResponseCache>,
    val operationInterceptors: ObjectProvider<OperationInterceptor>,
    val dlpInterceptors: ObjectProvider<DlpInterceptor>,
    val dlpRedactionAuditEmitters: ObjectProvider<DlpRedactionAuditEmitter>,
    val engineEventObservers: ObjectProvider<EngineEventObserver>,
    val auditEmitters: ObjectProvider<PolicyDecisionAuditEmitter>,
    val policyEngines: ObjectProvider<PolicyEngine>,
    val modelRegistries: ObjectProvider<ModelRegistry>,
    val modelRegistrySettingsProvider: ObjectProvider<ModelRegistrySettings>,
    val secretResolvers: ObjectProvider<SecretValueResolver>,
) {
    companion object {
        fun from(applicationContext: org.springframework.context.ApplicationContext): TramaiBeanDependencies =
            TramaiBeanDependencies(
                modelProviders = applicationContext.getBeanProvider(ModelProvider::class.java),
                operationResponseCache = applicationContext.getBeanProvider(OperationResponseCache::class.java),
                operationInterceptors = applicationContext.getBeanProvider(OperationInterceptor::class.java),
                dlpInterceptors = applicationContext.getBeanProvider(DlpInterceptor::class.java),
                dlpRedactionAuditEmitters = applicationContext.getBeanProvider(DlpRedactionAuditEmitter::class.java),
                engineEventObservers = applicationContext.getBeanProvider(EngineEventObserver::class.java),
                auditEmitters = applicationContext.getBeanProvider(PolicyDecisionAuditEmitter::class.java),
                policyEngines = applicationContext.getBeanProvider(PolicyEngine::class.java),
                modelRegistries = applicationContext.getBeanProvider(ModelRegistry::class.java),
                modelRegistrySettingsProvider = applicationContext.getBeanProvider(ModelRegistrySettings::class.java),
                secretResolvers = applicationContext.getBeanProvider(SecretValueResolver::class.java),
            )
    }
}

/**
 * Spring Boot auto-configuration for standalone Tramai usage.
 */
@AutoConfiguration
@EnableConfigurationProperties(TramaiProperties::class)
class TramaiAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun tramai(
        properties: TramaiProperties,
        applicationContext: org.springframework.context.ApplicationContext,
        @org.springframework.beans.factory.annotation.Qualifier("tramaiSecretValueResolver")
        secretResolver: SecretValueResolver,
    ): Tramai {
        val dependencies = TramaiBeanDependencies.from(applicationContext)
        val builder = Tramai.builder()
        val interceptorChain: List<OperationInterceptor> = buildList {
            dependencies.operationInterceptors.orderedStream().forEach { interceptor ->
                add(interceptor)
            }
        }

        // Scan for @AiTool beans
        builder.tools(AiToolScanner.fromApplicationContext(applicationContext))

        builder.cache(
            dependencies.operationResponseCache.ifAvailable
                ?: if (properties.cache.inMemory.enabled) {
                    InMemoryOperationResponseCache(maxEntries = properties.cache.inMemory.maxEntries)
                } else {
                    NoOpOperationResponseCache
                },
        )
        builder.interceptor(
            if (interceptorChain.isEmpty()) {
                NoOpOperationInterceptor
            } else {
                CompositeOperationInterceptor(interceptorChain)
            },
        )
        resolveDlpInterceptor(applicationContext, dependencies.dlpInterceptors)?.let(builder::dlp)
        resolveDlpRedactionAuditEmitter(dependencies.dlpRedactionAuditEmitters)?.let(builder::dlpRedactionAudit)
        builder.toolResultFiltering(
            applicationContext.getBeanProvider(ToolResultFilteringSettings::class.java).ifAvailable
                ?: ToolResultFilteringSettings()
        )
        resolveEngineEventObserver(dependencies.engineEventObservers)?.let(builder::engineEventObserver)
        resolvePolicyDecisionAuditEmitter(dependencies.auditEmitters)?.let(builder::policyDecisionAudit)
        resolvePolicyEngine(dependencies.policyEngines)?.let(builder::policyEngine)
        resolveModelRegistry(dependencies.modelRegistries)?.let(builder::modelRegistry)
        val settings = resolveModelRegistrySettings(dependencies.modelRegistrySettingsProvider)
            ?: ModelRegistrySettings(enabled = properties.security.modelRegistry.enabled)
        builder.modelRegistrySettings(settings)

        val propertyProviders = listOfNotNull(
            SpringSecretResolution.resolve(
                directValue = properties.providers.anthropic.apiKey,
                secretRef = properties.providers.anthropic.apiKeySecretRef,
                fieldName = "tramai.providers.anthropic.apiKey",
                secretResolver = secretResolver,
            )?.let { apiKey ->
                "anthropic" to AnthropicProvider(
                    apiKey = apiKey,
                    baseUrl = properties.providers.anthropic.baseUrl ?: "https://api.anthropic.com",
                )
            },
            resolveOpenAiProvider(properties.providers.openai, secretResolver)?.let { provider ->
                provider.providerId() to provider
            },
            resolveOpenAiCompatibleProvider(properties.providers.openaiCompatible, secretResolver)?.let { provider ->
                provider.providerId() to provider
            },
            properties.providers.ollama.baseUrl?.takeIf { it.isNotBlank() }?.let { baseUrl ->
                "ollama" to OllamaProvider(baseUrl = baseUrl)
            },
        )

        val beanProviders = dependencies.modelProviders.orderedStream().toList()
        val beanProviderCounts = beanProviders.groupingBy { it.providerId() }.eachCount()
        // Unique beans override property-backed providers; genuine user duplicates are
        // registered as-is so the canonical plan builder rejects them deterministically.
        val uniqueBeanProviders = beanProviders.filter { beanProviderCounts.getValue(it.providerId()) == 1 }
        val duplicateBeanProviders = beanProviders.filter { beanProviderCounts.getValue(it.providerId()) > 1 }

        // Only bean-over-property precedence is intentional. A property-vs-property
        // duplicate (e.g. OpenAI plus an openai-compatible provider explicitly named
        // "openai") must NOT be silently collapsed — pass both through so the canonical
        // plan builder rejects the collision deterministically.
        val propertyProviderCounts = propertyProviders.groupingBy { it.first }.eachCount()
        val duplicatePropertyProviders = propertyProviders.filter { propertyProviderCounts.getValue(it.first) > 1 }
        val uniquePropertyProviders = propertyProviders.filter { propertyProviderCounts.getValue(it.first) == 1 }

        val providersById = uniquePropertyProviders.toMap() + uniqueBeanProviders.associate { it.providerId() to it }
        providersById.forEach { (providerId, provider) ->
            builder.provider(provider, name = providerId)
        }
        duplicatePropertyProviders.forEach { (providerId, provider) ->
            builder.provider(provider, name = providerId)
        }
        duplicateBeanProviders.forEach { provider -> builder.provider(provider, name = provider.providerId()) }

        properties.models.forEach { (model, providerName) ->
            builder.model(model, providerName)
        }

        properties.fallbacks.forEach { (requestedModel, routes) ->
            routes.forEach { route ->
                val providerName = route.provider?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Fallback route for model '$requestedModel' must declare a provider")
                val effectiveModelName = route.model?.takeIf { it.isNotBlank() } ?: requestedModel
                builder.fallbackModel(
                    requestedModelName = requestedModel,
                    fallbackModelName = effectiveModelName,
                    providerName = providerName,
                )
            }
        }

        builder.circuitBreaker(
            CircuitBreakerSettings(
                enabled = properties.resilience.circuitBreaker.enabled,
                failureThreshold = properties.resilience.circuitBreaker.failureThreshold,
                openDurationMillis = properties.resilience.circuitBreaker.openDurationMillis,
            ),
        )
        builder.retryPolicy(
            RetryPolicySettings(
                maxRetryAfterMillis = properties.resilience.retry.maxRetryAfterMillis,
                jitterRatio = properties.resilience.retry.jitterRatio,
            ),
        )
        builder.tokenBudget(
            TokenBudgetSettings(
                hardMaxTokensPerAttempt = properties.cost.tokenBudget.hardMaxTokensPerAttempt,
                hardMaxTokensPerOperation = properties.cost.tokenBudget.hardMaxTokensPerOperation,
                softMaxTokensPerOperation = properties.cost.tokenBudget.softMaxTokensPerOperation,
            ),
        )

        properties.defaultProvider?.takeIf { it.isNotBlank() }?.let(builder::defaultProvider)

        return builder.build()
    }

    @Bean
    fun aiServiceBeanDefinitionRegistrar(
        beanFactory: ConfigurableListableBeanFactory,
    ): AiServiceBeanDefinitionRegistrar = AiServiceBeanDefinitionRegistrar(beanFactory)

    @OptIn(ExperimentalCodexAuth::class)
    private fun resolveOpenAiProvider(
        properties: TramaiProperties.OpenAi,
        secretResolver: SecretValueResolver,
    ): ModelProvider? {
        val baseUrl = properties.baseUrl ?: OpenAiProvider.DEFAULT_BASE_URL
        val authFile = properties.codexAuth.authFile?.let(Path::of) ?: CodexAuthFileTokenSource.defaultAuthFile()
        val apiKey = SpringSecretResolution.resolve(
            directValue = properties.apiKey,
            secretRef = properties.apiKeySecretRef,
            fieldName = "tramai.providers.openai.apiKey",
            secretResolver = secretResolver,
        )
        val bearerToken = SpringSecretResolution.resolve(
            directValue = properties.bearerToken,
            secretRef = properties.bearerTokenSecretRef,
            fieldName = "tramai.providers.openai.bearerToken",
            secretResolver = secretResolver,
        )

        return when {
            !apiKey.isNullOrBlank() -> OpenAiProvider(
                apiKey = apiKey,
                baseUrl = baseUrl,
                organization = properties.organization,
                project = properties.project,
            )
            !bearerToken.isNullOrBlank() -> OpenAiProvider.bearerToken(
                bearerToken = bearerToken,
                baseUrl = baseUrl,
                organization = properties.organization,
                project = properties.project,
            )
            properties.codexAuth.enabled -> OpenAiProvider.codexAuth(
                authFile = authFile,
                baseUrl = baseUrl,
                organization = properties.organization,
                project = properties.project,
            )
            else -> null
        }
    }

    @OptIn(ExperimentalCodexAuth::class)
    private fun resolveOpenAiCompatibleProvider(
        properties: TramaiProperties.OpenAiCompatible,
        secretResolver: SecretValueResolver,
    ): ModelProvider? {
        val baseUrl = properties.baseUrl?.takeIf { it.isNotBlank() } ?: return null
        val providerName = properties.providerName.ifBlank { "openai-compatible" }
        val authFile = properties.codexAuth.authFile?.let(Path::of) ?: CodexAuthFileTokenSource.defaultAuthFile()
        val apiKey = SpringSecretResolution.resolve(
            directValue = properties.apiKey,
            secretRef = properties.apiKeySecretRef,
            fieldName = "tramai.providers.openai-compatible.apiKey",
            secretResolver = secretResolver,
        )
        val bearerToken = SpringSecretResolution.resolve(
            directValue = properties.bearerToken,
            secretRef = properties.bearerTokenSecretRef,
            fieldName = "tramai.providers.openai-compatible.bearerToken",
            secretResolver = secretResolver,
        )

        return when {
            !apiKey.isNullOrBlank() -> OpenAiCompatibleProvider.bearerToken(
                bearerToken = apiKey,
                baseUrl = baseUrl,
                providerName = providerName,
            )
            !bearerToken.isNullOrBlank() -> OpenAiCompatibleProvider.bearerToken(
                bearerToken = bearerToken,
                baseUrl = baseUrl,
                providerName = providerName,
            )
            properties.codexAuth.enabled -> OpenAiCompatibleProvider.codexAuth(
                baseUrl = baseUrl,
                providerName = providerName,
                authFile = authFile,
            )
            else -> null
        }
    }

    private fun resolveDlpInterceptor(
        applicationContext: org.springframework.context.ApplicationContext,
        dlpInterceptors: ObjectProvider<DlpInterceptor>,
    ): DlpInterceptor? {
        val interceptors: List<DlpInterceptor> = buildList {
            dlpInterceptors.orderedStream().forEach { interceptor ->
                add(interceptor)
            }
        }
        if (interceptors.isEmpty()) {
            return null
        }
        if (interceptors.size == 1) {
            return interceptors.single()
        }

        val beanNames = applicationContext.getBeanNamesForType(DlpInterceptor::class.java).sorted()
        throw IllegalArgumentException(
            "Multiple DlpInterceptor beans found: ${beanNames.joinToString(", ")}. Define exactly one DlpInterceptor bean or none.",
        )
    }

    private fun resolveEngineEventObserver(
        engineEventObservers: ObjectProvider<EngineEventObserver>,
    ): EngineEventObserver? {
        val observers: List<EngineEventObserver> = buildList {
            engineEventObservers.orderedStream().forEach { observer ->
                add(observer)
            }
        }
        if (observers.isEmpty()) {
            return null
        }
        if (observers.size == 1) {
            return observers.single()
        }

        throw IllegalArgumentException(
            "Multiple EngineEventObserver beans found (${observers.size}). Define at most one.",
        )
    }

    private fun resolveDlpRedactionAuditEmitter(
        auditEmitters: ObjectProvider<DlpRedactionAuditEmitter>,
    ): DlpRedactionAuditEmitter? {
        val emitters: List<DlpRedactionAuditEmitter> = buildList {
            auditEmitters.orderedStream().forEach { emitter ->
                add(emitter)
            }
        }
        if (emitters.isEmpty()) return null
        if (emitters.size == 1) return emitters.single()
        throw IllegalArgumentException(
            "Multiple DlpRedactionAuditEmitter beans found (${emitters.size}). Define at most one.",
        )
    }

    private fun resolvePolicyDecisionAuditEmitter(
        auditEmitters: ObjectProvider<PolicyDecisionAuditEmitter>,
    ): PolicyDecisionAuditEmitter? {
        val emitters: List<PolicyDecisionAuditEmitter> = buildList {
            auditEmitters.orderedStream().forEach { emitter ->
                add(emitter)
            }
        }
        if (emitters.isEmpty()) return null
        if (emitters.size == 1) return emitters.single()
        throw IllegalArgumentException(
            "Multiple PolicyDecisionAuditEmitter beans found (${emitters.size}). Define at most one.",
        )
    }

    private fun resolvePolicyEngine(
        policyEngines: ObjectProvider<PolicyEngine>,
    ): PolicyEngine? {
        val engines: List<PolicyEngine> = buildList {
            policyEngines.orderedStream().forEach { engine ->
                add(engine)
            }
        }
        if (engines.isEmpty()) return null
        if (engines.size == 1) return engines.single()
        throw IllegalArgumentException(
            "Multiple PolicyEngine beans found (${engines.size}). Define at most one.",
        )
    }

    private fun resolveModelRegistry(
        registries: ObjectProvider<ModelRegistry>,
    ): ModelRegistry? {
        val list: List<ModelRegistry> = buildList {
            registries.orderedStream().forEach { registry ->
                add(registry)
            }
        }
        if (list.isEmpty()) return null
        if (list.size == 1) return list.single()
        throw IllegalArgumentException(
            "Multiple ModelRegistry beans found (${list.size}). Define at most one.",
        )
    }

    private fun resolveModelRegistrySettings(
        settings: ObjectProvider<ModelRegistrySettings>,
    ): ModelRegistrySettings? {
        val list: List<ModelRegistrySettings> = buildList {
            settings.orderedStream().forEach { registrySettings ->
                add(registrySettings)
            }
        }
        if (list.isEmpty()) return null
        if (list.size == 1) return list.single()
        throw IllegalArgumentException(
            "Multiple ModelRegistrySettings beans found (${list.size}). Define at most one.",
        )
    }
}
