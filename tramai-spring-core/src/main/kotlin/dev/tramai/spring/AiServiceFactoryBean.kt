package dev.tramai.spring

import dev.tramai.standalone.Tramai
import kotlin.reflect.KClass
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.FactoryBean

/**
 * Spring [FactoryBean] that creates service proxies through the active TramAI
 * runtime profile.
 *
 * Profile-aware Spring Boot wiring provides the shared named [AiServiceCreator]
 * bean. For compatibility with applications/tests that explicitly import
 * [TramaiAutoConfiguration] instead of using starter auto-configuration, the
 * factory falls back to the plain [Tramai] bean only when no named creator is
 * present.
 *
 * Sovereign mode never creates a plain [Tramai] bean, so a missing sovereign
 * creator fails loudly rather than silently downgrading to the standard runtime.
 */
class AiServiceFactoryBean<T : Any>(
    private val serviceType: Class<T>,
) : FactoryBean<T>, BeanFactoryAware {
    private lateinit var beanFactory: BeanFactory

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
    }

    override fun getObject(): T {
        val creator = resolveCreator()

        @Suppress("UNCHECKED_CAST")
        return creator(serviceType.kotlin) as T
    }

    private fun resolveCreator(): AiServiceCreator {
        if (beanFactory.containsBean(AI_SERVICE_CREATOR_BEAN_NAME)) {
            val creatorBean = beanFactory.getBean(AI_SERVICE_CREATOR_BEAN_NAME)
            check(creatorBean is Function1<*, *>) {
                "AI service creator bean has invalid type"
            }

            @Suppress("UNCHECKED_CAST")
            return creatorBean as AiServiceCreator
        }

        val tramai = beanFactory.getBean(Tramai::class.java)
        return { serviceType ->
            @Suppress("UNCHECKED_CAST")
            tramai.create(serviceType as KClass<Any>)
        }
    }

    override fun getObjectType(): Class<*> = serviceType

    override fun isSingleton(): Boolean = true
}
