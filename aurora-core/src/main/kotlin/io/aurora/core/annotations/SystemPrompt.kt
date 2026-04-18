package io.aurora.core.annotations

/**
 * Declares a service-wide system prompt applied to every operation on the interface.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class SystemPrompt(
    /** System prompt text prepended to each request. */
    val value: String,
)
