package dev.tramai.core.observation

import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEventFailurePolicy
import dev.tramai.core.observation.secondary.SecondaryEffectAuthority
import dev.tramai.core.observation.secondary.SecondaryFailureDiagnostic
import kotlinx.coroutines.CancellationException

/**
 * Epic 5.3 — failure-isolating [OperationObserver] boundary.
 *
 * Wraps a delegate observer so that a throwing telemetry callback can never
 * change the business outcome of a provider attempt: each callback failure is
 * contained and reported through [SecondaryFailureDiagnostic], while
 * [kotlinx.coroutines.CancellationException] always escapes unchanged.
 *
 * [RuntimeEvent] emissions honor the event's declared
 * [RuntimeEventFailurePolicy]: a `FAIL_CLOSED` event propagates its emission
 * failure (authoritative), a `FAIL_OPEN` event is contained. The legacy
 * (name, attributes) form carries no policy metadata and is contained.
 */
class FailureIsolatingOperationObserver(
    private val delegate: OperationObserver,
) : OperationObserver {
    override fun onCallStarted(context: OperationCallContext): OperationObservation {
        return try {
            FailureIsolatingOperationObservation(delegate.onCallStarted(context))
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            SecondaryFailureDiagnostic.report(
                extensionPoint = "operation_observer",
                callback = "onCallStarted",
                errorType = error.javaClass.simpleName,
                failurePolicy = "FAIL_OPEN",
                authority = SecondaryEffectAuthority.NON_AUTHORITATIVE.name,
            )
            NoOpOperationObservation
        }
    }
}

/**
 * Optional capability implemented by failure-isolating observation wrappers:
 * exposes the most recent completion-callback failure that the wrapper
 * contained, so call-site helpers (e.g. tool-processing finalizers) can attach
 * it as SUPPRESSED onto a primary business error — preserving the frozen
 * "primary error stays primary, observer failure preserved" contract without
 * letting the observer failure replace the primary.
 */
interface SecondaryFailureRecording {
    val lastCompletionFailure: Throwable?
}

/**
 * Per-attempt observation whose callbacks are all failure-isolated.
 *
 * The [onCallCancelled] default implementation of [OperationObservation]
 * routes through [onCallCompleted]; overriding both keeps every entry point
 * isolated exactly once.
 */
internal class FailureIsolatingOperationObservation(
    private val delegate: OperationObservation,
) : OperationObservation, SecondaryFailureRecording {

    @Volatile
    override var lastCompletionFailure: Throwable? = null
        private set

    private inline fun <T> isolate(callback: String, block: () -> T) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            SecondaryFailureDiagnostic.report(
                extensionPoint = "operation_observation",
                callback = callback,
                errorType = error.javaClass.simpleName,
                failurePolicy = "FAIL_OPEN",
                authority = SecondaryEffectAuthority.NON_AUTHORITATIVE.name,
            )
        }
    }

    override fun onProviderResponse(response: ModelResponse) {
        isolate("onProviderResponse") { delegate.onProviderResponse(response) }
    }

    override fun onProviderFailure(error: Throwable) {
        isolate("onProviderFailure") { delegate.onProviderFailure(error) }
    }

    override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) {
        isolate("onStructuredParseFailure") { delegate.onStructuredParseFailure(rawResponse, errorSummary) }
    }

    override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
        isolate("onEngineEvent") { delegate.onEngineEvent(name, attributes) }
    }

    override fun onEngineEvent(event: RuntimeEvent) {
        if (event.definition.failurePolicy == RuntimeEventFailurePolicy.FAIL_CLOSED) {
            // Authoritative emission: a failure must propagate, never be contained.
            delegate.onEngineEvent(event)
        } else {
            isolate("onEngineEvent") { delegate.onEngineEvent(event) }
        }
    }

    override fun onCallCompleted(parseSuccess: Boolean?) {
        try {
            delegate.onCallCompleted(parseSuccess)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // Record the contained failure so failure-path helpers
            // (completeAfterToolProcessing) can attach it as suppressed onto
            // the primary business error — the frozen contract.
            lastCompletionFailure = error
            SecondaryFailureDiagnostic.report(
                extensionPoint = "operation_observation",
                callback = "onCallCompleted",
                errorType = error.javaClass.simpleName,
                failurePolicy = "FAIL_OPEN",
                authority = SecondaryEffectAuthority.NON_AUTHORITATIVE.name,
            )
        }
    }

    override fun onCallCancelled() {
        // Epic 5.3: the cancellation path is NOT isolated. The engine's
        // completeCancellation helpers call this inside a try/catch that
        // attaches an observer failure as SUPPRESSED onto the in-flight
        // CancellationException — the frozen contract (CE primary, secondary
        // failure preserved as suppressed, never replacing the CE). Isolating
        // here would silently drop the suppression the contract tests demand.
        delegate.onCallCancelled()
    }
}
