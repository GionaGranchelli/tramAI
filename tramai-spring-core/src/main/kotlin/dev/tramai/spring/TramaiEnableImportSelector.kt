package dev.tramai.spring

import org.springframework.beans.factory.BeanClassLoaderAware
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.context.EnvironmentAware
import org.springframework.context.annotation.DeferredImportSelector
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotationMetadata

/**
 * Loads the TramAI configurations contributed to [EnableTramai] by modules on
 * the application classpath.
 *
 * Contributions use Spring Boot's imports convention under
 * `META-INF/spring/dev.tramai.spring.EnableTramai.imports`. This keeps the
 * annotation profile-neutral: spring-core contributes the standard profile,
 * while optional modules such as the sovereign starter contribute their own
 * profile configuration without creating a compile-time dependency back into
 * spring-core.
 *
 * Imports are deferred so application `@Bean` definitions are registered before
 * TramAI auto-configuration conditions are evaluated. This preserves the same
 * lifecycle assumption as Spring Boot auto-configuration (for example,
 * sovereign runtime creation is conditional on an application ModelProvider).
 */
internal class TramaiEnableImportSelector :
    DeferredImportSelector,
    BeanClassLoaderAware,
    EnvironmentAware {

    private lateinit var beanClassLoader: ClassLoader
    private lateinit var environment: Environment

    override fun setBeanClassLoader(classLoader: ClassLoader) {
        beanClassLoader = classLoader
    }

    override fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> {
        TramaiRuntimeProfileSupport.validate(
            environment.getProperty(TramaiRuntimeProfileSupport.PROPERTY_NAME),
        )

        return ImportCandidates.load(EnableTramai::class.java, beanClassLoader)
            .candidates
            .distinct()
            .toTypedArray()
    }
}
