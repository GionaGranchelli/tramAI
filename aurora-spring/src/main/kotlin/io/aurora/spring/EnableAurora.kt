package io.aurora.spring

import org.springframework.context.annotation.Import

/**
 * Enables Aurora Spring Boot auto-configuration for an application.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Import(AuroraAutoConfiguration::class)
annotation class EnableAurora
