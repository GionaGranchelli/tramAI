package dev.tramai.core.exception

/**
 * Base runtime exception for Tramai failures.
 */
sealed class TramaiException(
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
) : TramaiException(message, cause)

/**
 * Raised when a provider transport or API call fails.
 */
class ProviderException(
    message: String,
    cause: Throwable? = null,
    /** Provider HTTP status when one exists. */
    val statusCode: Int? = null,
    /** Whether the engine may retry this failure under provider retry policy. */
    val retryable: Boolean = false,
    /** Recommended delay before retrying, when exposed by the provider. */
    val retryAfterMillis: Long? = null,
) : TramaiException(message, cause)

/**
 * Raised when Tramai configuration is incomplete or internally inconsistent.
 */
class ConfigurationException(
    message: String,
    cause: Throwable? = null,
) : TramaiException(message, cause)

/**
 * Raised when an operation exceeds an externally imposed timeout.
 */
class TimeoutException(
    message: String,
    cause: Throwable? = null,
) : TramaiException(message, cause)

/**
 * Raised when a provider does not support a requested capability (e.g., streaming).
 */
class ProviderCapabilityException(
    val providerId: String,
    val capability: String,
) : TramaiException("Provider '$providerId' does not support $capability")

/**
 * Raised when a provider route is temporarily unavailable because its circuit is open.
 */
class CircuitBreakerOpenException(
    val providerId: String,
    /** Absolute epoch millis after which the provider may be probed again. */
    val reopenAtEpochMillis: Long,
) : TramaiException("Provider '$providerId' is temporarily unavailable because its circuit is open")

/**
 * Raised when engine-owned token budget policy is exceeded.
 */
class TokenBudgetExceededException(
    /** Budget scope that was exceeded, e.g. `attempt` or `operation`. */
    val scope: String,
    /** Maximum configured tokens allowed for the scope. */
    val limitTokens: Long,
    /** Observed tokens when the budget check failed. */
    val observedTokens: Long,
    /** Provider associated with the failing response, when known. */
    val providerId: String? = null,
    /** Effective model associated with the failing response, when known. */
    val modelName: String? = null,
) : TramaiException(
    buildString {
        append("Token budget exceeded for ")
        append(scope)
        append(": observed ")
        append(observedTokens)
        append(" token(s), limit is ")
        append(limitTokens)
        providerId?.let {
            append(" [provider=")
            append(it)
            append("]")
        }
        modelName?.let {
            append(" [model=")
            append(it)
            append("]")
        }
    },
)
