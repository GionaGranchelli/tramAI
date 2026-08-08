package dev.tramai.core.exception

import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.structured.StructuredOutputFailureCode

/**
 * Base runtime exception for Tramai failures.
 */
sealed class TramaiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Base class for approval-domain exceptions.
 * Kept for backward compatibility with [IllegalApprovalTransitionException].
 */
open class ApprovalException(
    message: String,
    cause: Throwable? = null,
) : TramaiException(message, cause)

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
) : TramaiException(message, cause) {
    /**
     * Typed classification for engine-produced failures; null for caller-constructed
     * instances. Trusted only when [safeFactoryTrusted] is true.
     */
    var failureCode: StructuredOutputFailureCode? = null
        internal set

    /**
     * Set only by the internal safe factory; external instances remain untrusted and
     * are re-sanitized at the engine boundary.
     */
    var safeFactoryTrusted: Boolean = false
        internal set
}

/**
 * Constructs the safe engine-produced structured-output failure: fixed trusted
 * message, typed failure code, safe numeric attempt count, no raw fields, no
 * cause. Raw response/validation detail is delivered separately to the
 * diagnostic observer; this exception is what callers see.
 *
 * Only pass text controlled by the caller (fixed templates). Provider
 * responses, throwable messages, raw model output, and other untrusted values
 * must not be interpolated into [message].
 */
fun safeStructuredOutputFailure(
    message: String,
    code: StructuredOutputFailureCode,
    attemptCount: Int,
): StructuredOutputException = StructuredOutputException(
    message = message,
    attemptCount = attemptCount,
).apply {
    failureCode = code
    safeFactoryTrusted = true
}

/**
 * Raised when a provider transport or API call fails.
 *
 * Built-in provider factories construct instances with fixed trusted messages
 * and without retaining the original throwable as cause. Observer-aware
 * variants deliver the original failure or a bounded error-body preview only
 * to the configured [dev.tramai.core.observation.ProviderFailureDiagnosticObserver].
 * The existing constructor remains available for callers that build their own
 * provider exceptions, but transport boundaries do not trust arbitrary
 * caller-constructed messages.
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
) : TramaiException(message, cause) {
    /** Typed classification of the failure, when produced by a built-in factory. */
    var failureCode: ProviderFailureCode? = null
        internal set

    internal var safeFactoryTrusted: Boolean = false
}

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

/**
 * Raised when an approval transition is not allowed by the state machine or
 * when the preconditions for the transition are not met (expired, already
 * decided, already consumed, etc.).
 */
class IllegalApprovalTransitionException(
    val approvalId: String,
    val from: ApprovalStatus,
    val to: ApprovalStatus,
    val reason: String,
) : ApprovalException(
    "Illegal approval transition for '$approvalId': $from -> $to - $reason"
)

// =============================================================================
// Sealed store exceptions — internal types thrown by ApprovalStore
// implementations. These are NOT exposed to callers; the coordinator maps them
// to safe public exceptions below.
// =============================================================================

/**
 * Sealed base class for store-level failures.
 * These exceptions carry only [approvalId] — no message or cause parameter.
 */
sealed class ApprovalStoreException(
    open val approvalId: String,
) : RuntimeException()

/**
 * Raised by the store when the requested approval ID does not exist.
 */
class ApprovalStoreNotFoundException(
    override val approvalId: String,
) : ApprovalStoreException(approvalId)

/**
 * Raised by the store when the presented token digest does not match.
 */
class ApprovalStoreTokenRejectedException(
    override val approvalId: String,
) : ApprovalStoreException(approvalId)

/**
 * Raised by the store on optimistic concurrency conflicts (version mismatch)
 * or duplicate ID on create.
 */
class ApprovalStoreConflictException(
    override val approvalId: String,
) : ApprovalStoreException(approvalId)

/**
 * Raised by the store when the approval is not in a consumable state
 * (wrong status, expired, already consumed, etc.).
 */
class ApprovalStoreNotConsumableException(
    override val approvalId: String,
) : ApprovalStoreException(approvalId)

// =============================================================================
// Coordinator-facing (public) safe exceptions
// These have FIXED safe messages and do NOT accept a cause parameter.
// =============================================================================

/**
 * Raised by the coordinator when an approval ID is not found
 * (store.get() returned null).
 */
class ApprovalNotFoundException(
    val approvalId: String,
) : ApprovalException("Approval not found: '$approvalId'")

/**
 * Raised by the coordinator when the presented approval token is rejected.
 */
class ApprovalTokenRejectedException(
    val approvalId: String,
) : ApprovalException("Approval token rejected for '$approvalId'")

/**
 * Raised by the coordinator when a binding field does not match the stored value.
 */
class ApprovalBindingMismatchException(
    val approvalId: String,
    val field: String,
) : ApprovalException("Approval binding mismatch for '$approvalId': $field")

/**
 * Raised by the coordinator when authorization fails due to a store-level
 * or unexpected error. Has a fixed safe message and no cause chain.
 */
class ApprovalAuthorizationException(
    val approvalId: String?,
) : ApprovalException("Approval authorization failed")

/**
 * Raised by the coordinator when approval creation fails due to a store-level
 * or unexpected error. Has a fixed safe message and no cause chain.
 */
class ApprovalCreationException(
    val approvalId: String?,
) : ApprovalException("Approval creation failed")

// =============================================================================
// Internal diagnostic observer SPI
// =============================================================================

/**
 * Internal observer that records original failures before they are sanitized
 * into safe public exceptions. Implementations must NOT leak sensitive data
 * through external channels.
 *
 * This is an internal diagnostic SPI — not part of the public API contract.
 */
fun interface ApprovalFailureObserver {
    fun record(
        operation: String,
        approvalId: String?,
        failure: RuntimeException,
    )
}
