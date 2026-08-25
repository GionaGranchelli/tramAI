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
import org.springframework.core.annotation.AnnotationAwareOrderComparator

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
@AutoConfiguration(before = [TramaiAutoConfiguration::class, StandardTramaiProfileAutoConfiguration::class])
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
        val userResolvers = applicationContext.getBeanNamesForType(SecretValueResolver::class.java)
            .filterNot { it in builtInNames || it in bootstrapNames }
            .map { name -> name to applicationContext.getBean(name, SecretValueResolver::class.java) }
        // Restore the exact pre-#263 orderedStream() semantics. Spring does
        // not reduce ordering to an integer lookup: it sorts the bean
        // INSTANCES with AnnotationAwareOrderComparator + a factory-aware
        // OrderSourceProvider. That combination retains PriorityOrdered
        // grouping, bean-definition/factory-method metadata, annotation
        // ordering, and the runtime Ordered#getOrder() fallback on the
        // instance itself.
        val instancesToBeanNames = userResolvers.associateBy({ it.second }, { it.first })
        val orderSourceProvider = org.springframework.core.OrderComparator.OrderSourceProvider { instance ->
            factoryOrderSources(applicationContext, instancesToBeanNames.getValue(instance as SecretValueResolver))
        }
        val comparator = AnnotationAwareOrderComparator.INSTANCE.withSourceProvider(orderSourceProvider)
        return userResolvers.map { it.second }.sortedWith(comparator)
    }

    /**
     * Same source array as Spring's FactoryAwareOrderSourceProvider:
     * bean-definition order attribute, then the resolved @Bean factory
     * method, then the target type. OrderComparator.getOrder(obj, provider)
     * iterates the array with findOrder(element) and, if every element is
     * unordered, falls back to findOrder(obj) on the instance itself.
     */
    private fun factoryOrderSources(applicationContext: ApplicationContext, beanName: String): Array<Any> {
        val beanFactory = applicationContext.autowireCapableBeanFactory as org.springframework.beans.factory.config.ConfigurableListableBeanFactory
        val definition = try {
            beanFactory.getMergedBeanDefinition(beanName) as? org.springframework.beans.factory.support.RootBeanDefinition
        } catch (_: Exception) {
            null
        }
        val sources = buildList {
            (definition?.getAttribute(org.springframework.beans.factory.support.AbstractBeanDefinition.ORDER_ATTRIBUTE) as? Int)
                ?.let { attribute -> add(org.springframework.core.Ordered { attribute }) }
            definition?.resolvedFactoryMethod?.let { add(it) }
            definition?.targetType?.let { add(it) }
        }
        return sources.toTypedArray()
    }
}
