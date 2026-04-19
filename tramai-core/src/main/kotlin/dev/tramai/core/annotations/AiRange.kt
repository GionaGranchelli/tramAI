package dev.tramai.core.annotations

/**
 * Constrains a numeric structured output property to a closed range.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class AiRange(
    /** Inclusive lower bound accepted during validation. */
    val min: Double,
    /** Inclusive upper bound accepted during validation. */
    val max: Double,
)
