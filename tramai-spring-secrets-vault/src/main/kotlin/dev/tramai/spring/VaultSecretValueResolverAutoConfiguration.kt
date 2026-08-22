package dev.tramai.spring

import dev.tramai.core.secret.SecretValueResolver
import dev.tramai.spring.secret.VaultSecretValueResolver
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Contributes the Vault secret resolver to the full chain.
 *
 * Vault credentials resolve through the bootstrap chain (user + environment +
 * file), which is why this module depends on the bootstrap resolver rather
 * than the full chain — otherwise Vault would try to resolve its own token
 * through itself.
 */
@AutoConfiguration(before = [TramaiSecretResolutionAutoConfiguration::class])
@EnableConfigurationProperties(TramaiProperties::class)
@ConditionalOnMissingBean(dev.tramai.standalone.Tramai::class)
class VaultSecretValueResolverAutoConfiguration {

    @Bean
    fun vaultSecretValueResolver(
        properties: TramaiProperties,
        @Qualifier("tramaiBootstrapSecretValueResolver")
        bootstrapSecretValueResolver: SecretValueResolver,
    ): SpringBuiltInSecretValueResolver? {
        val vault = properties.secrets.vault
        if (!vault.enabled) {
            return null
        }

        val baseUrl = vault.baseUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("tramai.secrets.vault.baseUrl must be configured when Vault secret resolution is enabled")
        val token = SpringSecretResolution.resolve(
            directValue = vault.token,
            secretRef = vault.tokenSecretRef,
            fieldName = "tramai.secrets.vault.token",
            secretResolver = bootstrapSecretValueResolver,
        ) ?: throw IllegalStateException("tramai.secrets.vault.token must be configured when Vault secret resolution is enabled")

        return VaultSecretValueResolver(
            baseUrl = baseUrl,
            token = token,
            mountPath = vault.mountPath,
            kvVersion = vault.kvVersion,
            namespace = vault.namespace,
            defaultField = vault.defaultField,
        )
    }
}
