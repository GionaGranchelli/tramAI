package io.aurora.core.exception

/**
 * Base runtime exception for Aurora failures.
 */
sealed class AuroraException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Raised when structured output cannot be parsed or validated within the allowed attempts.
 */
class StructuredOutputException(
    message: String,
    /** Prompt that initiated the failing structured operation, when available. */
    val originalPrompt: String? = null,
    /** Last raw provider response seen before the failure was surfaced. */
    val lastRawResponse: String? = null,
    /** Validation or parsing summary associated with the last failure. */
    val validationError: String? = null,
    /** Total number of attempts performed before giving up. */
    val attemptCount: Int? = null,
    cause: Throwable? = null,
) : AuroraException(message, cause)

/**
 * Raised when a provider transport or API call fails.
 */
class ProviderException(
    message: String,
    cause: Throwable? = null,
) : AuroraException(message, cause)

/**
 * Raised when Aurora configuration is incomplete or internally inconsistent.
 */
class ConfigurationException(
    message: String,
    cause: Throwable? = null,
) : AuroraException(message, cause)

/**
 * Raised when an operation exceeds an externally imposed timeout.
 */
class TimeoutException(
    message: String,
    cause: Throwable? = null,
) : AuroraException(message, cause)
