package dev.tramai.core.observation

import dev.tramai.core.model.ModelResponse

/**
 * Immutable context describing one engine attempt for an operation call.
 */
data class OperationCallContext(
    /** Fully qualified service interface name. */
    val serviceInterface: String,
    /** Invoked service method name. */
    val methodName: String,
    /** Provider selected for the attempt. */
    val providerId: String,
    /** Model requested by the operation definition. */
    val requestedModel: String,
    /** Zero-based attempt number within the engine retry loop. */
    val attempt: Int,
)

/**
 * Observer entry point used by the engine to create per-call observations.
 */
interface OperationObserver {
    /**
     * Starts observation for a single provider attempt.
     */
    fun onCallStarted(context: OperationCallContext): OperationObservation
}

/**
 * Callback set emitted during one provider attempt.
 */
interface OperationObservation {
    /**
     * Records a successful provider response before any structured parsing result is known.
     */
    fun onProviderResponse(response: ModelResponse)

    /**
     * Records a provider call failure.
     */
    fun onProviderFailure(error: Throwable)

    /**
     * Records a structured parsing or validation failure that may trigger an engine retry.
     */
    fun onStructuredParseFailure(
        rawResponse: String,
        errorSummary: String,
    )

    /**
     * Marks the end of an attempt.
     *
     * `parseSuccess` is `null` for raw string/unit operations.
     */
    fun onCallCompleted(parseSuccess: Boolean?)
}

/**
 * Default no-op observer.
 */
object NoOpOperationObserver : OperationObserver {
    override fun onCallStarted(context: OperationCallContext): OperationObservation = NoOpOperationObservation
}

/**
 * Default no-op per-call observation.
 */
object NoOpOperationObservation : OperationObservation {
    override fun onProviderResponse(response: ModelResponse) = Unit

    override fun onProviderFailure(error: Throwable) = Unit

    override fun onStructuredParseFailure(
        rawResponse: String,
        errorSummary: String,
    ) = Unit

    override fun onCallCompleted(parseSuccess: Boolean?) = Unit
}
