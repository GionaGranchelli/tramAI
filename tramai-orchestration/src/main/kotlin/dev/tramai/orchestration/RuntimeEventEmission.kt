package dev.tramai.orchestration

import dev.tramai.core.observation.event.RuntimeEvent

/**
 * Emits a catalogue-validated [RuntimeEvent] through the legacy workflow
 * observation boundary. The event is constructed through the typed builder, so
 * schema violations fail at the call site.
 */
internal fun WorkflowObserver.emitWorkflowEvent(
    workflowName: String,
    context: WorkflowContext,
    event: RuntimeEvent,
) {
    onWorkflowEvent(workflowName, event.name, event.attributes(), context)
}