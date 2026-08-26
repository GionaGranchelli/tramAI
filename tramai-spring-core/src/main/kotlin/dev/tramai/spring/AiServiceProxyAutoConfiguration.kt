package dev.tramai.spring

import dev.tramai.standalone.Tramai
import kotlin.reflect.KClass
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** Internal bean name shared by TramAI Spring runtime profiles. */
internal const val AI_SERVICE_CREATOR_BEAN_NAME: String = "tramaiAiServiceCreator"

/** Runtime-neutral service creation contract used by [AiServiceFactoryBean]. */
internal typealias AiServiceCreator = (KClass<*>) -> Any

/**
 * Adapts the standard [Tramai] runtime to Spring's runtime-neutral AI service
 * registration path.
 *
 * Sovereign and future runtime profiles provide the same named creator bean,
 * allowing `@AiService` discovery to stay independent of the active runtime.
 */
@AutoConfiguration(after = [TramaiAutoConfiguration::class, StandardTramaiProfileAutoConfiguration::class])
internal class AiServiceProxyAutoConfiguration {

    @Bean(name = [AI_SERVICE_CREATOR_BEAN_NAME])
    @ConditionalOnBean(Tramai::class)
    @ConditionalOnMissingBean(name = [AI_SERVICE_CREATOR_BEAN_NAME])
    fun aiServiceCreator(tramai: Tramai): AiServiceCreator = { serviceType ->
        @Suppress("UNCHECKED_CAST")
        tramai.create(serviceType as KClass<Any>)
    }
}
