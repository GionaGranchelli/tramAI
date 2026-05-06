package dev.tramai.core.annotations

/**
 * Defines the system-level instruction for an [Operation].
 *
 * The value is sent as a `system` role message in the provider's chat
 * completions API. Multiple `@System` annotations on the same function
 * are concatenated with newlines in declaration order.
 *
 * If no `@System` annotation is present on the method, the engine
 * checks for a class-level [SystemPrompt] annotation on the [AiService]
 * interface. If that also is absent, a default system message is
 * constructed from the interface name and method signature.
 *
 * When both `@System` (method) and `@SystemPrompt` (class) are present,
 * `@System` takes precedence (method-level wins), and a warning is logged.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class System(
    /** System instruction template with {param} interpolation markers. */
    val value: String,
)
