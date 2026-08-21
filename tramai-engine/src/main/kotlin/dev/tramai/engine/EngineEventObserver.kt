package dev.tramai.engine

import dev.tramai.core.observation.event.RuntimeEvent

interface EngineEventObserver {
    fun onEngineEvent(
        name: String,
        attributes: Map<String, Any?>,
    )

    /**
     * Typed overload that carries the event's declared failure policy
     * (Epic 5.2/5.3): the failure-isolating boundary can honour
     * [RuntimeEvent.definition.failurePolicy] instead of flattening it away.
     * The default implementation routes through the legacy [onEngineEvent]
     * so existing implementations keep working unchanged.
     */
    fun onEngineEvent(event: RuntimeEvent) {
        onEngineEvent(event.name, event.attributes())
    }
}

object NoOpEngineEventObserver : EngineEventObserver {
    override fun onEngineEvent(
        name: String,
        attributes: Map<String, Any?>,
    ) = Unit
}
