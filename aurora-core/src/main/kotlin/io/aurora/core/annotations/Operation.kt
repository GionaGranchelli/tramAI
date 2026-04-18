package io.aurora.core.annotations

/**
 * Declares one AI-backed operation on an [AiService] interface.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Operation(
    /** Prompt template sent as the base user instruction for the invocation. */
    val prompt: String,
    /** Logical model name requested by the operation. */
    val model: String,
    /** Optional explicit provider id that bypasses registry model resolution. */
    val provider: String = "",
    /** Maximum number of structured-output retries after validation failures. */
    val maxRetries: Int = 2,
    /** Reserved for provider-level retry integrations. */
    val providerRetries: Int = 3,
)
