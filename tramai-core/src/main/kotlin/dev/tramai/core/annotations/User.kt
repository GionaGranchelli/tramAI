package dev.tramai.core.annotations

/**
 * Defines a user-role message for an [Operation].
 *
 * The value is sent as a `user` role message. Multiple `@User` annotations
 * are sent as separate user messages in order. Parameter interpolation
 * ({paramName}) resolves against the method's parameter names.
 *
 * If neither `@User` nor `@Operation.prompt` is present, the engine
 * constructs a default user message based on the method signature.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class User(
    /** User message template with {param} interpolation markers. */
    val value: String,
)
