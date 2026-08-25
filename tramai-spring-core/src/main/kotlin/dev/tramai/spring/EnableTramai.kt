package dev.tramai.spring

import org.springframework.context.annotation.Import

/**
 * Enables TramAI Spring integration using the configured runtime profile.
 *
 * In Spring Boot applications the starter auto-configuration is normally
 * sufficient; this annotation remains an explicit opt-in for applications that
 * prefer annotation-driven configuration.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Import(
    StandardTramaiProfileAutoConfiguration::class,
    AiServiceProxyAutoConfiguration::class,
)
annotation class EnableTramai
