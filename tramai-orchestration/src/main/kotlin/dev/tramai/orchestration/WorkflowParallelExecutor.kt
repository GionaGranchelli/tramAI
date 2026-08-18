package dev.tramai.orchestration

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Parallel-specific runtime logic: bounded [coroutineScope]/[async]/[awaitAll]
 * execution with output ordering, max-parallel limits, cancellation
 * propagation, first-failure behavior, and merge timing.
 *
 * Invariants preserved:
 * - output ordering (results collected by item index);
 * - max-parallel limit semantics ([collectPendingItems] + [WorkflowLimitExceededException]);
 * - cancellation propagation (coroutineScope cancels all children);
 * - child cancellation and failure observation via [executeObservedParallelBranch];
 * - merge timing (merge runs after all branches complete);
 * - step-budget semantics ([StepCounter.beforeParallelBranch]).
 */
internal data class ParallelWorkflowStep<S, I, O>(
    override val name: String,
    val items: (S) -> Iterable<I>,
    val invoke: suspend (I) -> O,
    val merge: (S, List<O>) -> S,
) : InternalWorkflowStep<S> {
    suspend fun execute(
        workflowName: String,
        state: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
        stepCounter: StepCounter,
    ): S = coroutineScope {
        val pendingItems = collectPendingItems(
            source = items(state),
            maxParallelBranches = stepCounter.stopPolicy.maxParallelBranches,
        )
        if (pendingItems == null) {
            throw WorkflowLimitExceededException(
                "Workflow '$workflowName' exceeded maxParallelBranches=${stepCounter.stopPolicy.maxParallelBranches} at step '$name'",
            )
        }
        val results = pendingItems.mapIndexed { index, item ->
            stepCounter.beforeParallelBranch(workflowName, name, index)
            observer.onStepStarted(workflowName, "$name[$index]", context)
            async {
                executeObservedParallelBranch(
                    workflowName, name, index, item, context, observer, invoke,
                )
            }
        }.awaitAll()
        merge(state, results)
    }

    override suspend fun execute(
        request: WorkflowStepExecutionRequest<S>,
    ): WorkflowStepExecutionResult<S> = WorkflowStepExecutionResult.Completed(
        execute(
            workflowName = request.workflowName,
            state = request.state,
            context = request.context,
            observer = request.observer,
            stepCounter = request.stepCounter,
        ),
    )
}

/**
 * Collects up to [maxParallelBranches] items from [source]. Returns null when
 * the source has more items than the limit, signalling a limit violation.
 */
private fun <I> collectPendingItems(
    source: Iterable<I>,
    maxParallelBranches: Int,
): List<I>? {
    val iterator = source.iterator()
    val pending = ArrayList<I>(maxParallelBranches)
    repeat(maxParallelBranches) {
        if (!iterator.hasNext()) {
            return pending
        }
        pending += iterator.next()
    }
    return if (iterator.hasNext()) null else pending
}
