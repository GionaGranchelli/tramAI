package io.aurora.core.annotations

@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Operation(
    val prompt: String,
    val model: String,
    val provider: String = "",
    val maxRetries: Int = 2,
    val providerRetries: Int = 3,
)
