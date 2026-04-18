package io.aurora.core.annotations

/**
 * Marks an interface as an Aurora service contract.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class AiService
