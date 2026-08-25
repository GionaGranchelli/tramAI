package dev.tramai.spring

import org.springframework.context.annotation.Import

/**
 * Enables TramAI Spring integration for the standard runtime profile.
 *
 * In Spring Boot applications the starter auto-configuration is normally
 * sufficient; this annotation remains an explicit opt-in for applications that
 * prefer annotation-driven configuration. It routes through the standard
 * profile gate only — sovereign `@AiService` registration requires the
 * sovereign starter's auto-configuration.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Import(
    StandardTramaiProfileAutoConfiguration::class,
    AiServiceProxyAutoConfiguration::class,
)
annotation class EnableTramai
