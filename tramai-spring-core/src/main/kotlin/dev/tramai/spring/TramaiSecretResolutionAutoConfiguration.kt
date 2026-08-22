package dev.tramai.spring

import dev.tramai.core.secret.CompositeSecretValueResolver
import dev.tramai.core.secret.EnvironmentSecretValueResolver
import dev.tramai.core.secret.SecretValueResolver
import dev.tramai.standalone.Tramai
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Secret resolution chain assembly for Spring Tramai.
 *
 * The chain is deliberately split into a bootstrap resolver and a full
 * resolver so that Vault/AWS credentials can be bootstrapped through the
 * lower-level resolver set (user + environment + file) without recursively
 * resolving through themselves:
 *
 *   bootstrap = user + [Environment] + bootstrap markers (file module)
 *   full      = user + [Vault, AWS, ...built-in modules] + [Environment] + bootstrap markers
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
        applicationContext: org.springframework.context.ApplicationContext,
    ): SecretValueResolver {
        val userResolvers = userSecretResolvers(applicationContext)
        val bootstrapResolvers = bootstrapSecretResolvers(applicationContext)
        return CompositeSecretValueResolver(
            userResolvers + listOf(EnvironmentSecretValueResolver) + bootstrapResolvers,
        )
    }

    @Bean
    @ConditionalOnMissingBean(Tramai::class)
    fun tramaiSecretValueResolver(
        applicationContext: org.springframework.context.ApplicationContext,
    ): SecretValueResolver {
        val userResolvers = userSecretResolvers(applicationContext)
        val bootstrapResolvers = bootstrapSecretResolvers(applicationContext)
        val builtInResolvers = applicationContext.getBeanProvider(SpringBuiltInSecretValueResolver::class.java).orderedStream().toList()
        return CompositeSecretValueResolver(
            userResolvers + builtInResolvers + listOf(EnvironmentSecretValueResolver) + bootstrapResolvers,
        )
    }

    private fun bootstrapSecretResolvers(applicationContext: org.springframework.context.ApplicationContext): List<SecretValueResolver> =
        applicationContext.getBeanProvider(SpringBootstrapSecretValueResolver::class.java).orderedStream().toList()

    private fun userSecretResolvers(applicationContext: org.springframework.context.ApplicationContext): List<SecretValueResolver> {
        // Resolve by bean NAME, not by type stream: the two chain beans are
        // themselves SecretValueResolver beans, and streaming by type would
        // try to instantiate the very bean currently in creation.
        val chainBeanNames = setOf("tramaiBootstrapSecretValueResolver", "tramaiSecretValueResolver")
        val builtInNames = applicationContext.getBeanNamesForType(SpringBuiltInSecretValueResolver::class.java).toSet()
        val bootstrapNames = applicationContext.getBeanNamesForType(SpringBootstrapSecretValueResolver::class.java).toSet()
        return applicationContext.getBeanNamesForType(SecretValueResolver::class.java)
            .filterNot { it in chainBeanNames || it in builtInNames || it in bootstrapNames }
            .map { applicationContext.getBean(it, SecretValueResolver::class.java) }
    }
}
