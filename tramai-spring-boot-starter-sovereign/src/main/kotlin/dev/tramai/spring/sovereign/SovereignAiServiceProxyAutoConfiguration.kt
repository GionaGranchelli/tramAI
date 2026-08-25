package dev.tramai.spring.sovereign

import dev.tramai.sovereign.SovereignTramai
import dev.tramai.spring.AiServiceBeanDefinitionRegistrar
import kotlin.reflect.KClass
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Adapts the sovereign runtime to the shared Spring `@AiService` proxy path.
 *
 * The bean name intentionally matches the runtime-neutral lookup performed by
 * `AiServiceFactoryBean` in `tramai-spring-core`. Keeping this adapter in the
 * sovereign starter preserves the dependency direction: Spring core knows
 * nothing about sovereign runtime types.
 */
@AutoConfiguration(after = [SovereignTramaiProfileAutoConfiguration::class])
@ConditionalOnProperty(
    prefix = "tramai",
    name = ["profile"],
    havingValue = "sovereign",
)
internal class SovereignAiServiceProxyAutoConfiguration {

    @Bean(name = [AI_SERVICE_CREATOR_BEAN_NAME])
    @ConditionalOnBean(SovereignTramai::class)
    @ConditionalOnMissingBean(name = [AI_SERVICE_CREATOR_BEAN_NAME])
    fun sovereignAiServiceCreator(
        sovereignTramai: SovereignTramai,
    ): (KClass<*>) -> Any = { serviceType ->
        @Suppress("UNCHECKED_CAST")
        sovereignTramai.create(serviceType as KClass<Any>)
    }

    /**
     * Registers the shared scanner for sovereign applications as well.
     *
     * This is deliberately not conditional on the creator bean: discovered
     * `@AiService` definitions must not silently disappear when sovereign
     * runtime construction is unavailable. Resolving such a service then fails
     * loudly through the runtime-neutral factory rather than falling back to a
     * weaker runtime.
     */
    @Bean
    @ConditionalOnMissingBean
    fun sovereignAiServiceBeanDefinitionRegistrar(
        beanFactory: ConfigurableListableBeanFactory,
    ): AiServiceBeanDefinitionRegistrar = AiServiceBeanDefinitionRegistrar(beanFactory)

    private companion object {
        const val AI_SERVICE_CREATOR_BEAN_NAME = "tramaiAiServiceCreator"
    }
}
