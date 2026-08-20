package dev.tramai.engine

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
 */
class FailureIsolatingEngineEventObserver(
    private val delegate: EngineEventObserver,
) : EngineEventObserver {

    override fun onEngineEvent(
        name: String,
        attributes: Map<String, Any?>,
    ) {
        try {
            delegate.onEngineEvent(name, attributes)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            SecondaryFailureDiagnostic.report(
                extensionPoint = "engine_event_observer",
                callback = "onEngineEvent",
                errorType = error.javaClass.simpleName,
                failurePolicy = "FAIL_OPEN",
                authority = SecondaryEffectAuthority.NON_AUTHORITATIVE.name,
            )
        }
    }
}
