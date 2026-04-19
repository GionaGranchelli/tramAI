package dev.tramai.spring

import org.springframework.context.annotation.Import

/**
 * Enables Tramai Spring Boot auto-configuration for an application.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Import(TramaiAutoConfiguration::class)
annotation class EnableTramai
