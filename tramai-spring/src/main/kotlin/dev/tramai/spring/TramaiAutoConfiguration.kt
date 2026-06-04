package dev.tramai.spring

import dev.tramai.core.secret.CompositeSecretValueResolver
import dev.tramai.core.secret.EnvironmentSecretValueResolver
import dev.tramai.core.secret.FileSecretValueResolver
import dev.tramai.core.secret.SecretValueResolver
import dev.tramai.core.observation.CompositeOperationInterceptor
import dev.tramai.core.observation.NoOpOperationInterceptor
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.anthropic.AnthropicProvider
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.openai.CodexAuthFileTokenSource
import dev.tramai.openai.ExperimentalCodexAuth
import dev.tramai.openai.OpenAiCompatibleProvider
import dev.tramai.openai.OpenAiProvider
import dev.tramai.ollama.OllamaProvider
import dev.tramai.spring.secret.AwsSecretsManagerSecretValueResolver
import dev.tramai.spring.secret.VaultSecretValueResolver
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
import dev.tramai.standalone.Tramai
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.nio.file.Path

/**
 * Spring Boot auto-configuration for standalone Tramai usage.
 */
@AutoConfiguration
@EnableConfigurationProperties(TramaiProperties::class)
class TramaiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun tramai(
        properties: TramaiProperties,
        modelProviders: ObjectProvider<ModelProvider>,
        operationResponseCache: ObjectProvider<OperationResponseCache>,
        operationInterceptors: ObjectProvider<OperationInterceptor>,
        dlpInterceptors: ObjectProvider<DlpInterceptor>,
        engineEventObservers: ObjectProvider<EngineEventObserver>,
        auditEmitters: ObjectProvider<PolicyDecisionAuditEmitter>,
        policyEngines: ObjectProvider<PolicyEngine>,
        secretResolvers: ObjectProvider<SecretValueResolver>,
        applicationContext: org.springframework.context.ApplicationContext,
    ): Tramai {
        val builder = Tramai.builder()
        val interceptorChain = operationInterceptors.orderedStream().toList()
        val userSecretResolvers = secretResolvers.orderedStream().toList()
        val fileSecretResolver = FileSecretValueResolver(
            allowedDirectory = properties.secrets.file.allowedDirectory
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of),
        )
        val bootstrapSecretResolver = CompositeSecretValueResolver(
            userSecretResolvers + listOf(
                EnvironmentSecretValueResolver,
                fileSecretResolver,
            ),
        )
        val builtInSecretResolvers = listOfNotNull(
            createVaultSecretValueResolver(properties.secrets.vault, bootstrapSecretResolver),
            createAwsSecretsManagerSecretValueResolver(properties.secrets.awsSecretsManager, bootstrapSecretResolver),
        )
        val secretResolver = CompositeSecretValueResolver(
            userSecretResolvers + builtInSecretResolvers + listOf(
                EnvironmentSecretValueResolver,
                fileSecretResolver,
            ),
        )

        // Scan for @AiTool beans
        builder.tools(AiToolScanner.fromApplicationContext(applicationContext))

        builder.cache(
            operationResponseCache.ifAvailable
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
        resolveDlpInterceptor(applicationContext, dlpInterceptors)?.let(builder::dlp)
        builder.toolResultFiltering(
            applicationContext.getBeanProvider(ToolResultFilteringSettings::class.java).ifAvailable
                ?: ToolResultFilteringSettings()
        )
        resolveEngineEventObserver(engineEventObservers)?.let(builder::engineEventObserver)
        resolvePolicyDecisionAuditEmitter(auditEmitters)?.let(builder::policyDecisionAudit)
        resolvePolicyEngine(policyEngines)?.let(builder::policyEngine)

        // Register property-backed providers first so explicit provider beans can override them when needed.
        resolveSecret(
            directValue = properties.providers.anthropic.apiKey,
            secretRef = properties.providers.anthropic.apiKeySecretRef,
            fieldName = "tramai.providers.anthropic.apiKey",
            secretResolver = secretResolver,
        )?.let { apiKey ->
            builder.provider(
                provider = AnthropicProvider(
                    apiKey = apiKey,
                    baseUrl = properties.providers.anthropic.baseUrl ?: "https://api.anthropic.com",
                ),
                name = "anthropic",
            )
        }

        resolveOpenAiProvider(properties.providers.openai, secretResolver)?.let { provider ->
            builder.provider(provider = provider, name = provider.providerId())
        }

        resolveOpenAiCompatibleProvider(properties.providers.openaiCompatible, secretResolver)?.let { provider ->
            builder.provider(provider = provider, name = provider.providerId())
        }

        properties.providers.ollama.baseUrl?.takeIf { it.isNotBlank() }?.let { baseUrl ->
            builder.provider(
                provider = OllamaProvider(baseUrl = baseUrl),
                name = "ollama",
            )
        }

        modelProviders.orderedStream().forEach { provider ->
            builder.provider(provider, name = provider.providerId())
        }

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
        val apiKey = resolveSecret(
            directValue = properties.apiKey,
            secretRef = properties.apiKeySecretRef,
            fieldName = "tramai.providers.openai.apiKey",
            secretResolver = secretResolver,
        )
        val bearerToken = resolveSecret(
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
        val apiKey = resolveSecret(
            directValue = properties.apiKey,
            secretRef = properties.apiKeySecretRef,
            fieldName = "tramai.providers.openai-compatible.apiKey",
            secretResolver = secretResolver,
        )
        val bearerToken = resolveSecret(
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

    private fun resolveSecret(
        directValue: String?,
        secretRef: String?,
        fieldName: String,
        secretResolver: SecretValueResolver,
    ): String? {
        val trimmedDirect = directValue?.trim()?.takeIf { it.isNotBlank() }
        val trimmedRef = secretRef?.trim()?.takeIf { it.isNotBlank() }
        if (trimmedDirect != null && trimmedRef != null) {
            throw IllegalStateException("$fieldName cannot be configured together with its secret reference")
        }
        if (trimmedRef == null) {
            return trimmedDirect
        }

        return secretResolver.resolve(trimmedRef)
            ?: throw IllegalStateException("No SecretValueResolver could resolve '$trimmedRef' for $fieldName")
    }

    private fun resolveDlpInterceptor(
        applicationContext: org.springframework.context.ApplicationContext,
        dlpInterceptors: ObjectProvider<DlpInterceptor>,
    ): DlpInterceptor? {
        val interceptors = dlpInterceptors.orderedStream().toList()
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
        val observers = engineEventObservers.orderedStream().toList()
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

    private fun resolvePolicyDecisionAuditEmitter(
        auditEmitters: ObjectProvider<PolicyDecisionAuditEmitter>,
    ): PolicyDecisionAuditEmitter? {
        val emitters = auditEmitters.orderedStream().toList()
        if (emitters.isEmpty()) return null
        if (emitters.size == 1) return emitters.single()
        throw IllegalArgumentException(
            "Multiple PolicyDecisionAuditEmitter beans found (${emitters.size}). Define at most one.",
        )
    }

    private fun resolvePolicyEngine(
        policyEngines: ObjectProvider<PolicyEngine>,
    ): PolicyEngine? {
        val engines = policyEngines.orderedStream().toList()
        if (engines.isEmpty()) return null
        if (engines.size == 1) return engines.single()
        throw IllegalArgumentException(
            "Multiple PolicyEngine beans found (${engines.size}). Define at most one.",
        )
    }

    private fun createVaultSecretValueResolver(
        properties: TramaiProperties.Vault,
        bootstrapSecretResolver: SecretValueResolver,
    ): SecretValueResolver? {
        if (!properties.enabled) {
            return null
        }

        val baseUrl = properties.baseUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("tramai.secrets.vault.baseUrl must be configured when Vault secret resolution is enabled")
        val token = resolveSecret(
            directValue = properties.token,
            secretRef = properties.tokenSecretRef,
            fieldName = "tramai.secrets.vault.token",
            secretResolver = bootstrapSecretResolver,
        ) ?: throw IllegalStateException("tramai.secrets.vault.token must be configured when Vault secret resolution is enabled")

        return VaultSecretValueResolver(
            baseUrl = baseUrl,
            token = token,
            mountPath = properties.mountPath,
            kvVersion = properties.kvVersion,
            namespace = properties.namespace,
            defaultField = properties.defaultField,
        )
    }

    private fun createAwsSecretsManagerSecretValueResolver(
        properties: TramaiProperties.AwsSecretsManager,
        bootstrapSecretResolver: SecretValueResolver,
    ): SecretValueResolver? {
        if (!properties.enabled) {
            return null
        }

        val region = properties.region?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "tramai.secrets.aws-secrets-manager.region must be configured when AWS Secrets Manager resolution is enabled",
            )
        val accessKeyId = resolveSecret(
            directValue = properties.accessKeyId,
            secretRef = properties.accessKeyIdSecretRef,
            fieldName = "tramai.secrets.aws-secrets-manager.accessKeyId",
            secretResolver = bootstrapSecretResolver,
        )
        val secretAccessKey = resolveSecret(
            directValue = properties.secretAccessKey,
            secretRef = properties.secretAccessKeySecretRef,
            fieldName = "tramai.secrets.aws-secrets-manager.secretAccessKey",
            secretResolver = bootstrapSecretResolver,
        )
        val sessionToken = resolveSecret(
            directValue = properties.sessionToken,
            secretRef = properties.sessionTokenSecretRef,
            fieldName = "tramai.secrets.aws-secrets-manager.sessionToken",
            secretResolver = bootstrapSecretResolver,
        )

        return AwsSecretsManagerSecretValueResolver.fromSdk(
            region = region,
            endpoint = properties.endpoint,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            sessionToken = sessionToken,
            defaultField = properties.defaultField,
        )
    }
}
