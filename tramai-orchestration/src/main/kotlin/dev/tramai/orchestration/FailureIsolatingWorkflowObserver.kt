package dev.tramai.orchestration

import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEventFailurePolicy
import dev.tramai.core.observation.secondary.SecondaryEffectAuthority
import dev.tramai.core.observation.secondary.SecondaryFailureDiagnostic
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * Epic 5.3 — failure-isolating [WorkflowObserver] boundary.
 *
 * Wraps a delegate observer so that a throwing telemetry callback can never
 * change the workflow's business outcome: a successful workflow stays
 * successful, a failing workflow keeps its original failure as primary, and
 * [kotlinx.coroutines.CancellationException] always escapes unchanged.
 *
 * [RuntimeEvent] emissions honor the event's declared
 * [RuntimeEventFailurePolicy]: `FAIL_CLOSED` propagates, `FAIL_OPEN` is
 * contained. The legacy (name, attributes) form carries no policy metadata and
 * is contained.
 */
class FailureIsolatingWorkflowObserver(
    private val delegate: WorkflowObserver,
) : WorkflowObserver {

    private inline fun <T> isolate(callback: String, block: () -> T) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            SecondaryFailureDiagnostic.report(
                extensionPoint = "workflow_observer",
                callback = callback,
                errorType = error.javaClass.simpleName,
                failurePolicy = "FAIL_OPEN",
                authority = SecondaryEffectAuthority.NON_AUTHORITATIVE.name,
            )
        }
    }

    override fun onWorkflowStarted(workflowName: String, context: WorkflowContext) {
        isolate("onWorkflowStarted") { delegate.onWorkflowStarted(workflowName, context) }
    }

    override fun onWorkflowEvent(
        workflowName: String,
        name: String,
        attributes: Map<String, Any?>,
        context: WorkflowContext,
    ) {
        isolate("onWorkflowEvent") { delegate.onWorkflowEvent(workflowName, name, attributes, context) }
    }

    override fun onWorkflowEvent(workflowName: String, event: RuntimeEvent, context: WorkflowContext) {
        if (event.definition.failurePolicy == RuntimeEventFailurePolicy.FAIL_CLOSED) {
            // Authoritative emission: a failure must propagate, never be contained.
            delegate.onWorkflowEvent(workflowName, event, context)
        } else {
            isolate("onWorkflowEvent") { delegate.onWorkflowEvent(workflowName, event, context) }
        }
    }

    override fun onStepStarted(workflowName: String, stepName: String, context: WorkflowContext) {
        isolate("onStepStarted") { delegate.onStepStarted(workflowName, stepName, context) }
    }

    override fun onStepCompleted(workflowName: String, stepName: String, context: WorkflowContext) {
        isolate("onStepCompleted") { delegate.onStepCompleted(workflowName, stepName, context) }
    }

    override fun onStepFailed(
        workflowName: String,
        stepName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        isolate("onStepFailed") { delegate.onStepFailed(workflowName, stepName, error, context) }
    }

    override fun onWorkflowCompleted(workflowName: String, context: WorkflowContext) {
        isolate("onWorkflowCompleted") { delegate.onWorkflowCompleted(workflowName, context) }
    }

    override fun onWorkflowFailed(workflowName: String, error: Throwable, context: WorkflowContext) {
        isolate("onWorkflowFailed") { delegate.onWorkflowFailed(workflowName, error, context) }
    }

    override fun onScheduledTick(workflowName: String, scheduledFireAt: Instant, context: WorkflowContext) {
        isolate("onScheduledTick") { delegate.onScheduledTick(workflowName, scheduledFireAt, context) }
    }

    override fun onSkippedTick(
        workflowName: String,
        scheduledFireAt: Instant,
        reason: String,
        context: WorkflowContext,
    ) {
        isolate("onSkippedTick") { delegate.onSkippedTick(workflowName, scheduledFireAt, reason, context) }
    }

    override fun onMissedTick(
        workflowName: String,
        scheduledFireAt: Instant,
        reason: String,
        context: WorkflowContext,
    ) {
        isolate("onMissedTick") { delegate.onMissedTick(workflowName, scheduledFireAt, reason, context) }
    }
}
