package dev.tramai.orchestration

import java.time.Instant
import java.util.UUID

/**
 * Workflow-level execution metadata.
 */
data class WorkflowContext(
    val workflowId: String = UUID.randomUUID().toString(),
    val attributes: Map<String, Any?> = emptyMap(),
)

/**
 * Workflow-level observation seam.
 */
interface WorkflowObserver {
    fun onWorkflowStarted(
        workflowName: String,
        context: WorkflowContext,
    ) = Unit

    fun onWorkflowEvent(
        workflowName: String,
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        context: WorkflowContext,
    ) = Unit

    /**
     * Records a catalogue-validated workflow event (Epic 5.2). The event
     * carries its own identity, domain, sensitivity, and permitted attributes;
     * additive overload that delegates to the legacy (name, attributes) form.
     */
    fun onWorkflowEvent(
        workflowName: String,
        event: dev.tramai.core.observation.event.RuntimeEvent,
        context: WorkflowContext,
    ) {
        onWorkflowEvent(workflowName, event.name, event.attributes(), context)
    }

    fun onStepStarted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) = Unit

    fun onStepCompleted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) = Unit

    fun onStepFailed(
        workflowName: String,
        stepName: String,
        error: Throwable,
        context: WorkflowContext,
    ) = Unit

    fun onWorkflowCompleted(
        workflowName: String,
        context: WorkflowContext,
    ) = Unit

    fun onWorkflowFailed(
        workflowName: String,
        error: Throwable,
        context: WorkflowContext,
    ) = Unit

    fun onScheduledTick(
        workflowName: String,
        scheduledFireAt: Instant,
        context: WorkflowContext,
    ) = Unit

    fun onSkippedTick(
        workflowName: String,
        scheduledFireAt: Instant,
        reason: String,
        context: WorkflowContext,
    ) = Unit

    fun onMissedTick(
        workflowName: String,
        scheduledFireAt: Instant,
        reason: String,
        context: WorkflowContext,
    ) = Unit
}

object NoOpWorkflowObserver : WorkflowObserver
