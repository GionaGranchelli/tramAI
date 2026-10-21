package dev.tramai.core.observation

import dev.tramai.core.model.ModelResponse

/**
 * Identity of the engine invocation an attempt belongs to (Epic 0.7.5b).
 *
 * The engine supplies these values; an observer never invents them. [invocationId] is unique per
 * invocation, so `(invocationId, attempt)` is the attempt identity — an attempt number alone
 * collides when one run invokes the same operation twice.
 *
 * Every field is nullable because the information is only available on the paths that have it:
 * `null` means "not known here", never "absent at runtime".
 */
data class AttemptIdentity(
    /** Unique identifier of one operation invocation, or `null` when the caller supplied none. */
    val invocationId: String? = null,
    /** Governed workflow run this invocation belongs to, when one exists. */
    val workflowRunId: String? = null,
    /** Correlation identifier propagated through the invocation, when one exists. */
    val correlationId: String? = null,
    /** Zero-based route index the attempt executed on, when the engine selected a route. */
    val routeIndex: Int? = null,
)

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
    /** Identity the engine can attribute this attempt to (Epic 0.7.5b). */
    val identity: AttemptIdentity = AttemptIdentity(),
) {
    /**
     * Pre-0.7.5b form, preserved so existing compiled callers keep working; it supplies no identity.
     */
    constructor(
        serviceInterface: String,
        methodName: String,
        providerId: String,
        requestedModel: String,
        attempt: Int,
    ) : this(serviceInterface, methodName, providerId, requestedModel, attempt, AttemptIdentity())
}

/**
 * Observer entry point used by the engine to create per-call observations.
 */
fun interface OperationObserver {
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
     *
     * This signature is preserved for compatibility, but this is an ordinary
     * observability surface and no longer receives raw diagnostic material. The
     * structured-output diagnostic observer is the sole privileged channel.
     */
    fun onStructuredParseFailure(
        rawResponse: String,
        errorSummary: String,
    )

    /**
     * Records an engine-owned resilience or routing event for the current attempt.
     */
    fun onEngineEvent(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
    ) = Unit

    /**
     * Records a catalogue-validated engine-owned event (Epic 5.2). The event
     * carries its own identity, domain, sensitivity, and permitted attributes;
     * additive overload that delegates to the legacy (name, attributes) form.
     */
    fun onEngineEvent(event: dev.tramai.core.observation.event.RuntimeEvent) {
        onEngineEvent(event.name, event.attributes())
    }

    /**
     * Marks the end of an attempt.
     *
     * `parseSuccess` is `null` for raw string/unit operations.
     */
    fun onCallCompleted(parseSuccess: Boolean?)

    /**
     * Called when the attempt is cancelled externally.
     *
     * Default implementation ends the span as a non-error terminal.
     */
    fun onCallCancelled() {
        onCallCompleted(parseSuccess = null)
    }
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

    override fun onEngineEvent(
        name: String,
        attributes: Map<String, Any?>,
    ) = Unit

    override fun onCallCompleted(parseSuccess: Boolean?) = Unit

    override fun onCallCancelled() = Unit
}
