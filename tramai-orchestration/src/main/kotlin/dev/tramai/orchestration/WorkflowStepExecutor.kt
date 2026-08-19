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

/**
 * Step-execution budget accounting for one workflow invocation.
 *
 * Counts every step execution (including nested and parallel branches) against
 * [StopPolicy.maxStepExecutions] and raises [WorkflowLimitExceededException]
 * when the bound is exceeded. Sits beside [WorkflowStepExecutor] because
 * [StepCounter.beforeStep] is literally the first call in the shared execution
 * wrapper, and [StepCounter.beforeParallelBranch] is the same budget
 * accounting for parallel branches.
 */
internal class StepCounter(
    val stopPolicy: StopPolicy,
    initialStepExecutions: Int = 0,
) {
    var stepExecutions: Int = initialStepExecutions
        private set

    init {
        require(initialStepExecutions >= 0) {
            "StepCounter.initialStepExecutions must be zero or greater"
        }
        require(initialStepExecutions <= stopPolicy.maxStepExecutions) {
            "StepCounter.initialStepExecutions must not exceed StopPolicy.maxStepExecutions"
        }
    }

    fun beforeStep(
        workflowName: String,
        stepName: String,
    ) {
        if (stepExecutions >= stopPolicy.maxStepExecutions) {
            throw WorkflowLimitExceededException(
                "Workflow '$workflowName' exceeded maxStepExecutions=${stopPolicy.maxStepExecutions} before step '$stepName'",
            )
        }
        stepExecutions += 1
    }

    fun beforeParallelBranch(
        workflowName: String,
        stepName: String,
        branchIndex: Int,
    ) {
        if (stepExecutions >= stopPolicy.maxStepExecutions) {
            throw WorkflowLimitExceededException(
                "Workflow '$workflowName' exceeded maxStepExecutions=${stopPolicy.maxStepExecutions} during branch '$stepName[$branchIndex]'",
            )
        }
        stepExecutions += 1
    }
}
