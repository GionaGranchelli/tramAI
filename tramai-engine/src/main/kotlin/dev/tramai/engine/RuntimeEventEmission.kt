package dev.tramai.engine

import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.event.RuntimeEvent

/**
 * Emits a catalogue-validated [RuntimeEvent] through the legacy
 * (name, attributes) observation boundary. The event is constructed through
 * the typed builder, so schema violations fail at the call site.
 */
internal fun OperationObservation.emitRuntimeEvent(event: RuntimeEvent) {
    onEngineEvent(event.name, event.attributes())
}
