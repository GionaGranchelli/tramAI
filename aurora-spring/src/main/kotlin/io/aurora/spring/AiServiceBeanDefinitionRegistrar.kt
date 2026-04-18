package io.aurora.spring

import io.aurora.core.annotations.AiService
import org.springframework.beans.BeansException
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter

class AiServiceBeanDefinitionRegistrar(
    private val beanFactory: ConfigurableListableBeanFactory,
) : BeanDefinitionRegistryPostProcessor {

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        if (!AutoConfigurationPackages.has(beanFactory)) {
            return
        }

        val scanner = object : ClassPathScanningCandidateComponentProvider(false) {
            override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition): Boolean {
                return beanDefinition.metadata.isIndependent && beanDefinition.metadata.isInterface
            }
        }.apply {
            addIncludeFilter(AnnotationTypeFilter(AiService::class.java))
        }

        AutoConfigurationPackages.get(beanFactory).forEach { basePackage ->
            scanner.findCandidateComponents(basePackage).forEach { candidate ->
                val className = candidate.beanClassName ?: return@forEach
                val beanClass = Class.forName(className)
                if (registry.containsBeanDefinition(className)) {
                    return@forEach
                }

                val definition = RootBeanDefinition(AiServiceFactoryBean::class.java)
                definition.constructorArgumentValues.addIndexedArgumentValue(0, beanClass)
                registry.registerBeanDefinition(className, definition)
            }
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit
}
