package dev.tramai.orchestration

/**
 * Branch-specific runtime logic: select the configured/default branch and run
 * its nested steps through the shared [WorkflowStepExecutionRequest.executeNestedSteps]
 * callback so that nested execution always crosses the common step wrapper
 * (step counting, observation, cancellation, sanitisation).
 *
 * Invariants preserved:
 * - default branch behavior;
 * - missing-branch [WorkflowBranchSelectionException];
 * - step-budget accounting (via the shared wrapper);
 * - observer events;
 * - deeply nested branches (recursion through [WorkflowStepExecutionRequest.executeNestedSteps]).
 */
internal data class BranchWorkflowStep<S>(
    override val name: String,
    val select: (S) -> String,
    val branches: Map<String, List<InternalWorkflowStep<S>>>,
    val defaultSteps: List<InternalWorkflowStep<S>>?,
) : InternalWorkflowStep<S> {
    override suspend fun execute(
        request: WorkflowStepExecutionRequest<S>,
    ): WorkflowStepExecutionResult<S> {
        val key = select(request.state)
        val selected = branches[key] ?: defaultSteps
            ?: throw WorkflowBranchSelectionException(
                "Workflow '${request.workflowName}' selected unknown branch '$key' at step '${name}'",
            )
        return WorkflowStepExecutionResult.Completed(
            request.executeNestedSteps(selected, request.state),
        )
    }
}
