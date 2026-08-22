package dev.tramai.spring

import dev.tramai.core.secret.CompositeSecretValueResolver
import dev.tramai.core.secret.EnvironmentSecretValueResolver
import dev.tramai.core.secret.SecretValueResolver
import dev.tramai.standalone.Tramai
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.OrderUtils

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
 * The two chains are exposed as [SpringBootstrapSecretChain] /
 * [SpringSecretChain] holders rather than raw [SecretValueResolver] beans:
 * they are internal assembly details and must not participate in unqualified
 * application-level `SecretValueResolver` injection (which would turn a
 * previously valid application into a `NoUniqueBeanDefinitionException`).
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
    fun tramaiBootstrapSecretChain(
        applicationContext: ApplicationContext,
    ): SpringBootstrapSecretChain {
        val userResolvers = userSecretResolvers(applicationContext)
        val bootstrapResolvers = bootstrapSecretResolvers(applicationContext)
        return SpringBootstrapSecretChain(
            CompositeSecretValueResolver(
                userResolvers + listOf(EnvironmentSecretValueResolver) + bootstrapResolvers,
            ),
        )
    }

    @Bean
    @ConditionalOnMissingBean(Tramai::class)
    fun tramaiSecretChain(
        applicationContext: ApplicationContext,
    ): SpringSecretChain {
        val userResolvers = userSecretResolvers(applicationContext)
        val bootstrapResolvers = bootstrapSecretResolvers(applicationContext)
        val builtInResolvers = applicationContext.getBeanProvider(SpringBuiltInSecretValueResolver::class.java).orderedStream().toList()
        return SpringSecretChain(
            CompositeSecretValueResolver(
                userResolvers + builtInResolvers + listOf(EnvironmentSecretValueResolver) + bootstrapResolvers,
            ),
        )
    }

    private fun bootstrapSecretResolvers(applicationContext: ApplicationContext): List<SecretValueResolver> =
        applicationContext.getBeanProvider(SpringBootstrapSecretValueResolver::class.java).orderedStream().toList()

    private fun userSecretResolvers(applicationContext: ApplicationContext): List<SecretValueResolver> {
        // Resolve by bean NAME, not by type stream: streaming by type would
        // try to instantiate every SecretValueResolver candidate including
        // the ones currently in creation (the chains are holders now, but a
        // built-in resolver contributed by an optional module may itself be
        // under construction). The internal chain beans are no longer
        // SecretValueResolver beans at all, so only built-in-marker and
        // bootstrap-marker names need excluding.
        val builtInNames = applicationContext.getBeanNamesForType(SpringBuiltInSecretValueResolver::class.java).toSet()
        val bootstrapNames = applicationContext.getBeanNamesForType(SpringBootstrapSecretValueResolver::class.java).toSet()
        return applicationContext.getBeanNamesForType(SecretValueResolver::class.java)
            .filterNot { it in builtInNames || it in bootstrapNames }
            .map { name -> name to applicationContext.getBean(name, SecretValueResolver::class.java) }
            // Restore the pre-#263 ordering contract: the original inline
            // assembly used ObjectProvider.orderedStream(), which sorts by
            // Spring's factory-aware order source — bean-definition order
            // attribute, then the @Bean factory method, then the target
            // type — exactly what FactoryAwareOrderSourceProvider does.
            .sortedWith { (name1, _), (name2, _) ->
                orderOf(applicationContext, name1).compareTo(orderOf(applicationContext, name2))
            }
            .map { (_, resolver) -> resolver }
    }

    private fun orderOf(applicationContext: ApplicationContext, beanName: String): Int {
        val beanFactory = applicationContext.autowireCapableBeanFactory as org.springframework.beans.factory.config.ConfigurableListableBeanFactory
        val definition = try {
            beanFactory.getMergedBeanDefinition(beanName) as? org.springframework.beans.factory.support.RootBeanDefinition
        } catch (_: Exception) {
            null
        }
        val attribute = definition?.getAttribute(org.springframework.beans.factory.support.AbstractBeanDefinition.ORDER_ATTRIBUTE) as? Int
        if (attribute != null) return attribute
        // Same fall-through as Spring's FactoryAwareOrderSourceProvider:
        // bean-definition attribute, then the @Bean factory method's
        // @Order, then the target type's @Order/Ordered.
        val factoryMethodOrder = definition?.resolvedFactoryMethod?.let { OrderUtils.getOrder(it) }
        if (factoryMethodOrder != null) return factoryMethodOrder
        val targetTypeOrder = definition?.targetType?.let { OrderUtils.getOrder(it) }
        return targetTypeOrder ?: org.springframework.core.Ordered.LOWEST_PRECEDENCE
    }
}
