package dev.tramai.core.annotations

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
    /** Optional list of tool names available to this operation. */
    val tools: Array<String> = [],
    /** Maximum number of structured-output retries after validation failures. */
    val maxRetries: Int = 2,
    /** Maximum number of provider retries after transient transport or API failures. */
    val providerRetries: Int = 3,
    /** Maximum duration, in milliseconds, allowed for a single provider attempt. */
    val timeoutMillis: Long = 30_000,
    /** Whether the engine may cache successful non-streaming responses for this operation. */
    val cacheable: Boolean = false,
    /** Cache TTL, in milliseconds, for successful responses when [cacheable] is enabled. */
    val cacheTtlMillis: Long = 60_000,
)
