package dev.tramai.engine

import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.secondary.SecondaryEffectAuthority
import dev.tramai.core.observation.secondary.SecondaryFailureDiagnostic
import kotlinx.coroutines.CancellationException

/**
 * Epic 5.3 — failure-isolating [EngineEventObserver] boundary.
 *
 * Engine lifecycle events are non-authoritative telemetry: a throwing
 * observer must never block approval replay, tool sanitization, circuit
 * decisions, or any other engine behaviour. Each callback failure is contained
 * and reported through the safe secondary-failure diagnostic.
 * [kotlinx.coroutines.CancellationException] always escapes unchanged.
 *
 * The typed [onEngineEvent] overload honours the event's declared failure
 * policy: FAIL_CLOSED events propagate (never contained), FAIL_OPEN events are
 * contained with a diagnostic.
 */
@ExperimentalTramaiInternalApi
class FailureIsolatingEngineEventObserver(
    private val delegate: EngineEventObserver,
) : EngineEventObserver {

    override fun onEngineEvent(
        name: String,
        attributes: Map<String, Any?>,
    ) {
        isolate("onEngineEvent") {
            delegate.onEngineEvent(name, attributes)
        }
    }

    override fun onEngineEvent(event: RuntimeEvent) {
        if (event.definition.failurePolicy == dev.tramai.core.observation.event.RuntimeEventFailurePolicy.FAIL_CLOSED) {
            // Authoritative event: emission failure must propagate.
            delegate.onEngineEvent(event)
        } else {
            isolate("onEngineEvent(${event.definition.name})") {
                delegate.onEngineEvent(event)
            }
        }
    }

    private inline fun isolate(callback: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            SecondaryFailureDiagnostic.report(
                extensionPoint = "engine_event_observer",
                callback = callback,
                errorType = error.javaClass.simpleName,
                failurePolicy = "FAIL_OPEN",
                authority = SecondaryEffectAuthority.NON_AUTHORITATIVE.name,
            )
        }
    }
}
