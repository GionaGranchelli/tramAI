package dev.tramai.orchestration

import dev.tramai.core.observation.event.RuntimeEvent

/**
 * Emits a catalogue-validated [RuntimeEvent] through the typed workflow
 * observation overload. The event is constructed through the typed builder, so
 * schema violations fail at the call site; routing through the typed overload
 * lets the failure-isolating boundary honor the event's declared failure
 * policy (FAIL_CLOSED propagates, FAIL_OPEN is contained).
 */
internal fun WorkflowObserver.emitWorkflowEvent(
    workflowName: String,
    context: WorkflowContext,
    event: RuntimeEvent,
) {
    onWorkflowEvent(workflowName, event, context)
}