package dev.tramai.spring

import dev.tramai.core.secret.CompositeSecretValueResolver
import dev.tramai.core.secret.EnvironmentSecretValueResolver
import dev.tramai.core.secret.FileSecretValueResolver
import dev.tramai.core.secret.SecretValueResolver
import dev.tramai.spring.secret.AwsSecretsManagerSecretValueResolver
import dev.tramai.spring.secret.VaultSecretValueResolver
import dev.tramai.standalone.Tramai
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.nio.file.Path

/**
 * Secret resolution chain assembly for Spring Tramai.
 *
 * The chain is deliberately split into a bootstrap resolver and a full
 * resolver so that Vault/AWS credentials can be bootstrapped through the
 * lower-level resolver set (user + environment + file) without recursively
 * resolving through themselves:
 *
 *   bootstrap = user + [Environment, File]
 *   full      = user + [Vault, AWS, ...built-in modules] + [Environment, File]
 *
 * Built-in secret resolvers contributed by optional modules implement
 * [SpringBuiltInSecretValueResolver]; they are excluded from the bootstrap
 * chain and from the user-resolver set so the ordering matches the original
 * inline assembly (user first, then built-ins, then environment/file).
 *
 * Both beans back off when a user supplies their own [Tramai] bean, matching
 * the original behavior where the chain was only ever assembled inside the
 * auto-configuration tramai() bean.
 */
@AutoConfiguration(before = [TramaiAutoConfiguration::class])
@EnableConfigurationProperties(TramaiProperties::class)
class TramaiSecretResolutionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(Tramai::class)
    fun tramaiBootstrapSecretValueResolver(
        properties: TramaiProperties,
        applicationContext: org.springframework.context.ApplicationContext,
    ): SecretValueResolver {
        val userResolvers = userSecretResolvers(applicationContext)
        val fileResolver = fileSecretResolver(properties)
        return CompositeSecretValueResolver(
            userResolvers + listOf(EnvironmentSecretValueResolver, fileResolver),
        )
    }

    @Bean
    @ConditionalOnMissingBean(Tramai::class)
    fun tramaiSecretValueResolver(
        properties: TramaiProperties,
        applicationContext: org.springframework.context.ApplicationContext,
        @org.springframework.beans.factory.annotation.Qualifier("tramaiBootstrapSecretValueResolver")
        bootstrapSecretValueResolver: SecretValueResolver,
    ): SecretValueResolver {
        val userResolvers = userSecretResolvers(applicationContext)
        val fileResolver = fileSecretResolver(properties)
        val builtInResolvers = listOfNotNull(
            createVaultSecretValueResolver(properties.secrets.vault, bootstrapSecretValueResolver),
            createAwsSecretsManagerSecretValueResolver(properties.secrets.awsSecretsManager, bootstrapSecretValueResolver),
        ) + applicationContext.getBeanProvider(SpringBuiltInSecretValueResolver::class.java).orderedStream().toList()
        return CompositeSecretValueResolver(
            userResolvers + builtInResolvers + listOf(EnvironmentSecretValueResolver, fileResolver),
        )
    }

    private fun userSecretResolvers(applicationContext: org.springframework.context.ApplicationContext): List<SecretValueResolver> {
        // Resolve by bean NAME, not by type stream: the two chain beans are
        // themselves SecretValueResolver beans, and streaming by type would
        // try to instantiate the very bean currently in creation.
        val chainBeanNames = setOf("tramaiBootstrapSecretValueResolver", "tramaiSecretValueResolver")
        val builtInNames = applicationContext.getBeanNamesForType(SpringBuiltInSecretValueResolver::class.java).toSet()
        return applicationContext.getBeanNamesForType(SecretValueResolver::class.java)
            .filterNot { it in chainBeanNames || it in builtInNames }
            .map { applicationContext.getBean(it, SecretValueResolver::class.java) }
    }

    private fun fileSecretResolver(properties: TramaiProperties): FileSecretValueResolver =
        FileSecretValueResolver(
            allowedDirectory = properties.secrets.file.allowedDirectory
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of),
        )

    private fun createVaultSecretValueResolver(
        properties: TramaiProperties.Vault,
        bootstrapSecretResolver: SecretValueResolver,
    ): SecretValueResolver? {
        if (!properties.enabled) {
            return null
        }

        val baseUrl = properties.baseUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("tramai.secrets.vault.baseUrl must be configured when Vault secret resolution is enabled")
        val token = SpringSecretResolution.resolve(
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
        val accessKeyId = SpringSecretResolution.resolve(
            directValue = properties.accessKeyId,
            secretRef = properties.accessKeyIdSecretRef,
            fieldName = "tramai.secrets.aws-secrets-manager.accessKeyId",
            secretResolver = bootstrapSecretResolver,
        )
        val secretAccessKey = SpringSecretResolution.resolve(
            directValue = properties.secretAccessKey,
            secretRef = properties.secretAccessKeySecretRef,
            fieldName = "tramai.secrets.aws-secrets-manager.secretAccessKey",
            secretResolver = bootstrapSecretResolver,
        )
        val sessionToken = SpringSecretResolution.resolve(
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
