package dev.tramai.core.annotations

/**
 * Describes the semantic meaning of a structured output property.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class AiDescription(
    /** Human-readable description exposed to the model schema. */
    val value: String,
)
