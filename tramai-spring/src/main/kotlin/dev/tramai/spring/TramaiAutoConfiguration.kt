package dev.tramai.spring

import dev.tramai.anthropic.AnthropicProvider
import dev.tramai.core.provider.ModelProvider
import dev.tramai.openai.CodexAuthFileTokenSource
import dev.tramai.openai.ExperimentalCodexAuth
import dev.tramai.openai.OpenAiCompatibleProvider
import dev.tramai.openai.OpenAiProvider
import dev.tramai.ollama.OllamaProvider
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
        applicationContext: org.springframework.context.ApplicationContext,
    ): Tramai {
        val builder = Tramai.builder()

        // Scan for @AiTool beans
        builder.tools(AiToolScanner.fromApplicationContext(applicationContext))

        // Register property-backed providers first so explicit provider beans can override them when needed.
        properties.providers.anthropic.apiKey?.takeIf { it.isNotBlank() }?.let { apiKey ->
            builder.provider(
                provider = AnthropicProvider(
                    apiKey = apiKey,
                    baseUrl = properties.providers.anthropic.baseUrl ?: "https://api.anthropic.com",
                ),
                name = "anthropic",
            )
        }

        resolveOpenAiProvider(properties.providers.openai)?.let { provider ->
            builder.provider(provider = provider, name = provider.providerId())
        }

        resolveOpenAiCompatibleProvider(properties.providers.openaiCompatible)?.let { provider ->
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
    ): ModelProvider? {
        val baseUrl = properties.baseUrl ?: OpenAiProvider.DEFAULT_BASE_URL
        val authFile = properties.codexAuth.authFile?.let(Path::of) ?: CodexAuthFileTokenSource.defaultAuthFile()

        return when {
            !properties.apiKey.isNullOrBlank() -> OpenAiProvider(
                apiKey = properties.apiKey.orEmpty(),
                baseUrl = baseUrl,
                organization = properties.organization,
                project = properties.project,
            )
            !properties.bearerToken.isNullOrBlank() -> OpenAiProvider.bearerToken(
                bearerToken = properties.bearerToken.orEmpty(),
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
    ): ModelProvider? {
        val baseUrl = properties.baseUrl?.takeIf { it.isNotBlank() } ?: return null
        val providerName = properties.providerName.ifBlank { "openai-compatible" }
        val authFile = properties.codexAuth.authFile?.let(Path::of) ?: CodexAuthFileTokenSource.defaultAuthFile()

        return when {
            !properties.apiKey.isNullOrBlank() -> OpenAiCompatibleProvider.bearerToken(
                bearerToken = properties.apiKey.orEmpty(),
                baseUrl = baseUrl,
                providerName = providerName,
            )
            !properties.bearerToken.isNullOrBlank() -> OpenAiCompatibleProvider.bearerToken(
                bearerToken = properties.bearerToken.orEmpty(),
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
}
