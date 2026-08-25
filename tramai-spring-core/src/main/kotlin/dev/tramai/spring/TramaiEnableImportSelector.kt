package dev.tramai.spring

import org.springframework.beans.factory.BeanClassLoaderAware
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.context.annotation.ImportSelector
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
 */
internal class TramaiEnableImportSelector : ImportSelector, BeanClassLoaderAware {

    private lateinit var beanClassLoader: ClassLoader

    override fun setBeanClassLoader(classLoader: ClassLoader) {
        beanClassLoader = classLoader
    }

    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> =
        ImportCandidates.load(EnableTramai::class.java, beanClassLoader)
            .candidates
            .distinct()
            .toTypedArray()
}
