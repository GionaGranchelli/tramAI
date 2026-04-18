package io.aurora.spring

import io.aurora.standalone.Aurora
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.FactoryBean

/**
 * Spring [FactoryBean] that creates Aurora-backed service proxies.
 */
class AiServiceFactoryBean<T : Any>(
    private val serviceType: Class<T>,
) : FactoryBean<T>, BeanFactoryAware {
    private lateinit var beanFactory: BeanFactory

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
    }

    override fun getObject(): T {
        val aurora = beanFactory.getBean(Aurora::class.java)
        return aurora.create(serviceType.kotlin)
    }

    override fun getObjectType(): Class<*> = serviceType

    override fun isSingleton(): Boolean = true
}
