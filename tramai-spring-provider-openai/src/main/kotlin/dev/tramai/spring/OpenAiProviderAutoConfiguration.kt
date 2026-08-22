package dev.tramai.spring

import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.secret.SecretValueResolver
import dev.tramai.openai.CodexAuthFileTokenSource
import dev.tramai.openai.ExperimentalCodexAuth
import dev.tramai.openai.OpenAiCompatibleProvider
import dev.tramai.openai.OpenAiProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.nio.file.Path

/**
 * OpenAI and OpenAI-compatible provider adapter auto-configuration.
 *
 * Constructs the OpenAI and OpenAI-compatible providers from their
 * `tramai.providers.openai.*` / `tramai.providers.openai-compatible.*`
 * properties and contributes them as [SpringConfiguredModelProvider]
 * descriptors. The generic runtime assembly in [TramaiAutoConfiguration]
 * consumes the descriptors without knowing OpenAI exists.
 *
 * Backs off when the user supplies their own [dev.tramai.standalone.Tramai]
 * bean, matching the original behavior where the auto-configuration tramai()
 * bean (and therefore provider construction) was skipped entirely.
 */
@AutoConfiguration(before = [TramaiAutoConfiguration::class])
@EnableConfigurationProperties(OpenAiProperties::class, OpenAiCompatibleProperties::class)
@ConditionalOnMissingBean(dev.tramai.standalone.Tramai::class)
class OpenAiProviderAutoConfiguration {

    @Bean
    fun openAiProvider(
        properties: OpenAiProperties,
        secretChain: SpringSecretChain,
    ): SpringConfiguredModelProvider? =
        resolveOpenAiProvider(properties, secretChain.resolver)?.let { provider ->
            SpringConfiguredModelProvider(providerId = provider.providerId(), provider = provider)
        }

    @Bean
    fun openAiCompatibleProvider(
        properties: OpenAiCompatibleProperties,
        secretChain: SpringSecretChain,
    ): SpringConfiguredModelProvider? =
        resolveOpenAiCompatibleProvider(properties, secretChain.resolver)?.let { provider ->
            SpringConfiguredModelProvider(providerId = provider.providerId(), provider = provider)
        }

    @OptIn(ExperimentalCodexAuth::class)
    private fun resolveOpenAiProvider(
        properties: OpenAiProperties,
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
        properties: OpenAiCompatibleProperties,
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
}
