package dev.tramai.spring

import dev.tramai.anthropic.AnthropicProvider
import dev.tramai.core.provider.ModelProvider
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
        secretResolvers: ObjectProvider<SecretValueResolver>,
        applicationContext: org.springframework.context.ApplicationContext,
    ): Tramai {
        val builder = Tramai.builder()
        val secretResolver = CompositeSecretValueResolver(
            secretResolvers.orderedStream().toList() + listOf(
                EnvironmentSecretValueResolver,
                FileSecretValueResolver,
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
}
