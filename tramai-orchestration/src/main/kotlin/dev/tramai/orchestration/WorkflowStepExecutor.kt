package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation

/**
 * The one shared execution wrapper for every built-in workflow step.
 *
 * Top-level steps, nested branch steps, and (via [WorkflowStepExecutionRequest.executeNestedSteps])
 * all nested executions route through this single wrapper so that step
 * budgeting, observation, cancellation passthrough, and failure sanitisation
 * stay centralized. The polymorphic execution itself happens inside
 * [InternalWorkflowStep.execute]; this class never dispatches on a concrete
 * step type.
 *
 * Frozen contract (unchanged by the #246/#247 refactors):
 * ```
 * stepCounter.beforeStep
 *   → observer.onStepStarted
 *   → step.execute(request)
 *   → Completed?  YES → observer.onStepCompleted
 *                 NO  → no completion event (Suspended)
 * ```
 * Ordinary exception:
 * ```
 * exception
 *   → rethrow CancellationException unchanged (no sanitisation, no onStepFailed)
 *   → sanitizeStepFailure(...)
 *   → observer.onStepFailed(...)
 *   → throw sanitized failure
 * ```
 */
internal class WorkflowStepExecutor<S>(
    private val workflowName: String,
    private val failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver,
) {
    suspend fun executeStep(
        step: InternalWorkflowStep<S>,
        request: WorkflowStepExecutionRequest<S>,
    ): WorkflowStepExecutionResult<S> {
        request.stepCounter.beforeStep(workflowName, step.name)
        request.observer.onStepStarted(workflowName, step.name, request.context)
        val result = try {
            step.execute(request)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            val sanitized = sanitizeStepFailure(step, workflowName, failureDiagnosticObserver, error)
            request.observer.onStepFailed(workflowName, step.name, sanitized, request.context)
            throw sanitized
        }
        if (result is WorkflowStepExecutionResult.Completed) {
            request.observer.onStepCompleted(workflowName, step.name, request.context)
        }
        return result
    }
}
