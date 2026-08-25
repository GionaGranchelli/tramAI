package dev.tramai.spring

import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.FactoryBean

/**
 * Spring [FactoryBean] that creates service proxies through the active TramAI
 * runtime profile.
 *
 * The concrete runtime is intentionally resolved through the shared named
 * [AiServiceCreator] bean rather than by looking up `Tramai` directly. This
 * keeps `@AiService` bean registration independent of standard vs sovereign
 * runtime selection.
 */
class AiServiceFactoryBean<T : Any>(
    private val serviceType: Class<T>,
) : FactoryBean<T>, BeanFactoryAware {
    private lateinit var beanFactory: BeanFactory

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
    }

    override fun getObject(): T {
        val creatorBean = beanFactory.getBean(AI_SERVICE_CREATOR_BEAN_NAME)
        check(creatorBean is Function1<*, *>) {
            "tramai-ai-service-creator-invalid"
        }

        @Suppress("UNCHECKED_CAST")
        val creator = creatorBean as AiServiceCreator

        @Suppress("UNCHECKED_CAST")
        return creator(serviceType.kotlin) as T
    }

    override fun getObjectType(): Class<*> = serviceType

    override fun isSingleton(): Boolean = true
}
