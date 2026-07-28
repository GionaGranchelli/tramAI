package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation

/**
 * Executes one branch of a parallel workflow step, capturing the
 * cancellation-safe execution boundary.
 *
 * The try covers only [invoke] so that a completion-observer failure
 * does not incorrectly produce a branch [onStepFailed] notification.
 */
internal suspend fun <I, O> executeObservedParallelBranch(
    workflowName: String,
    stepName: String,
    branchIndex: Int,
    item: I,
    context: WorkflowContext,
    observer: WorkflowObserver,
    invoke: suspend (I) -> O,
): O {
    val branchName = "$stepName[$branchIndex]"

    val result = try {
        invoke(item)
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        observer.onStepFailed(
            workflowName,
            branchName,
            error,
            context,
        )
        throw error
    }

    observer.onStepCompleted(
        workflowName,
        branchName,
        context,
    )
    return result
}
