package dev.tramai.orchestration

import java.time.Clock

/**
 * Whether a workflow step may checkpoint the whole workflow run.
 *
 * [NONE] steps never suspend; [TOP_LEVEL_CHECKPOINT] steps may persist a
 * checkpoint and suspend the run at a top-level step boundary.
 */
internal enum class WorkflowStepSuspensionMode {
    NONE,
    TOP_LEVEL_CHECKPOINT,
}

/**
 * Contract-level result of executing one workflow step.
 *
 * A step returns [Completed] with the next state, or [Suspended] to
 * checkpoint and suspend the workflow run. Suspension is modelled as a value,
 * never as an exception thrown from inside a step; the workflow runner
 * converts [Suspended] into the outer [WorkflowSuspendedException] at the
 * existing boundary.
 */
internal sealed interface WorkflowStepExecutionResult<out S> {
    data class Completed<S>(
        val state: S,
    ) : WorkflowStepExecutionResult<S>

    data object Suspended : WorkflowStepExecutionResult<Nothing>
}

/**
 * Runtime-only collaborators handed to a step's execute implementation.
 *
 * This is the deliberate boundary that keeps steps from reaching into the
 * whole [Workflow] object: a step may use these services and nothing else.
 */
internal data class WorkflowStepExecutionServices(
    val clock: Clock,
    val httpTransport: HttpTransport,
    val outboundNetworkPolicy: OutboundNetworkPolicy,
    val externalStepExecutorResolver: ExternalStepExecutorResolver,
    val failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver,
)

/**
 * Everything a step needs to execute, without the step itself: the receiver
 * of [InternalWorkflowStep.execute] is the step.
 *
 * [executeNestedSteps] routes nested steps through the same common step
 * wrapper (step counting, observation, cancellation, sanitisation) instead of
 * letting a step call child [InternalWorkflowStep.execute] directly.
 */
internal data class WorkflowStepExecutionRequest<S>(
    val workflowName: String,
    val state: S,
    val context: WorkflowContext,
    val observer: WorkflowObserver,
    val stepCounter: StepCounter,
    val persistenceSession: WorkflowPersistenceSession<S>?,
    val topLevelStepIndex: Int?,
    val resumedCheckpointMetadata: Map<String, String>?,
    val services: WorkflowStepExecutionServices,
    val executeNestedSteps: suspend (
        steps: List<InternalWorkflowStep<S>>,
        state: S,
    ) -> S,
)

/**
 * Internal runtime execution contract for every built-in workflow step.
 *
 * The workflow runner sequences steps through this single boundary; it no
 * longer knows how any concrete step type executes. [suspensionMode] makes
 * the topology constraint part of the model: only [WorkflowStepSuspensionMode.TOP_LEVEL_CHECKPOINT]
 * steps may suspend, and only at top level.
 */
internal sealed interface InternalWorkflowStep<S> {
    val name: String

    val suspensionMode: WorkflowStepSuspensionMode
        get() = WorkflowStepSuspensionMode.NONE

    suspend fun execute(
        request: WorkflowStepExecutionRequest<S>,
    ): WorkflowStepExecutionResult<S>
}
