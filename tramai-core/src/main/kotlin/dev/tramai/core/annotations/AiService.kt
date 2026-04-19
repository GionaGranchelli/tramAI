package dev.tramai.core.annotations

/**
 * Marks an interface as an Tramai service contract.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class AiService
