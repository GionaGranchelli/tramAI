package io.aurora.core.annotations

/**
 * Declares the minimum allowed number of elements for a collection property.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class AiMinItems(
    /** Minimum collection size accepted during structured-output validation. */
    val value: Int,
)
