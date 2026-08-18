package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.http.HttpClient
import java.time.Clock

/**
 * Explicit execution bounds for one workflow run.
 */
data class StopPolicy(
    val maxStepExecutions: Int = 100,
    val maxParallelBranches: Int = 16,
) {
    init {
        require(maxStepExecutions > 0) { "StopPolicy.maxStepExecutions must be greater than zero" }
        require(maxParallelBranches > 0) { "StopPolicy.maxParallelBranches must be greater than zero" }
    }
}

/**
 * Step-execution budget accounting for one workflow invocation.
 *
 * Counts every step execution (including nested and parallel branches) against
 * [StopPolicy.maxStepExecutions] and raises [WorkflowLimitExceededException]
 * when the bound is exceeded.
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

/**
 * Owns the lifecycle of one workflow invocation: initial run, resume, top-level
 * and nested step iteration, initial/after-step checkpoints, completion,
 * suspension, failure, and cancellation cleanup.
 *
 * The sequencing here is contractual and must remain unchanged:
 *
 * Normal run:
 * ```
 * onWorkflowStarted
 *   → create persistence session
 *   → initial checkpoint
 *   → execute steps
 *   → complete persistence
 *   → onWorkflowCompleted
 *   → resultSelector
 * ```
 * Failure: step/runtime failure → abort persistence → onWorkflowFailed → rethrow.
 * Cancellation: CancellationException → NonCancellable { persistence.abort(...) }
 *   → same cancellation escapes.
 * Suspension: WorkflowSuspendedException → abort session → tramai.workflow.suspended
 *   → rethrow suspension.
 *
 * Step-level execution is delegated to [WorkflowStepExecutor], the one shared
 * wrapper for top-level and nested steps.
 */
internal class WorkflowRunner<S, R>(
    private val name: String,
    private val steps: List<InternalWorkflowStep<S>>,
    private val resultSelector: (S) -> R,
    private val stopPolicy: StopPolicy,
    private val clock: Clock,
    private val externalStepExecutorResolver: ExternalStepExecutorResolver,
    private val httpClient: HttpClient,
    private val httpTransport: HttpTransport?,
    private val outboundNetworkPolicy: OutboundNetworkPolicy,
    private val failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver,
    private val definitionCompatibility: WorkflowDefinitionCompatibility,
) {
    private val stepExecutor = WorkflowStepExecutor<S>(name, failureDiagnosticObserver)

    suspend fun run(
        initialState: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
        persistence: WorkflowPersistence<S>?,
    ): R {
        observer.onWorkflowStarted(name, context)
        var persistenceSession: WorkflowPersistenceSession<S>? = null
        return try {
            val stepCounter = StepCounter(stopPolicy)
            persistenceSession = persistence?.session(
                workflowName = name,
                context = context,
                observer = observer,
                workflowDefinitionCompatibility = definitionCompatibility,
            )
            persistenceSession?.saveCheckpoint(
                state = initialState,
                nextStepIndex = 0,
                lastCompletedStepName = null,
                stepExecutions = stepCounter.stepExecutions,
            )
            val finalState = executeTopLevelSteps(
                startIndex = 0,
                state = initialState,
                context = context,
                observer = observer,
                stepCounter = stepCounter,
                persistenceSession = persistenceSession,
                resumedCheckpointMetadata = null,
            )
            persistenceSession?.complete(workflowName = name, context = context)
            observer.onWorkflowCompleted(name, context)
            resultSelector(finalState)
        } catch (suspended: WorkflowSuspendedException) {
            persistenceSession?.abort()
            observer.onWorkflowEvent(
                workflowName = name,
                name = "tramai.workflow.suspended",
                attributes = mapOf("workflow_id" to context.workflowId),
                context = context,
            )
            throw suspended
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                persistenceSession?.runCatchingAbort(error)
            }
            throw error
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            persistenceSession?.runCatchingAbort(error)
            observer.onWorkflowFailed(name, error, context)
            throw error
        }
    }

    suspend fun resume(
        context: WorkflowContext,
        observer: WorkflowObserver,
        persistence: WorkflowPersistence<S>,
    ): R {
        val checkpoint = persistence.checkpointStore.load(name, context.workflowId)
            ?: throw WorkflowResumeException(
                "No checkpoint exists for workflow '$name' and workflowId='${context.workflowId}'",
            )
        if (checkpoint.nextStepIndex < 0 || checkpoint.nextStepIndex > steps.size) {
            throw WorkflowResumeException(
                "Checkpoint for workflow '$name' and workflowId='${context.workflowId}' has invalid nextStepIndex=${checkpoint.nextStepIndex}; valid range is 0..${steps.size}",
            )
        }
        val persistedDefinitionCompatibility = checkpoint.requireWorkflowDefinitionCompatibility(
            workflowName = name,
            workflowId = context.workflowId,
        )
        requireCompatibleDefinition(
            workflowName = name,
            workflowId = context.workflowId,
            persisted = persistedDefinitionCompatibility,
            current = definitionCompatibility,
        )
        observer.onWorkflowStarted(name, context)
        observer.onWorkflowEvent(
            workflowName = name,
            name = "tramai.workflow.checkpoint.loaded",
            attributes = mapOf(
                "workflow_id" to checkpoint.workflowId,
                "next_step_index" to checkpoint.nextStepIndex,
                "step_executions" to checkpoint.stepExecutions,
                "revision" to checkpoint.revision,
                "has_last_completed_step" to (checkpoint.lastCompletedStepName != null),
                "definition_version" to persistedDefinitionCompatibility.version,
                "definition_digest_algorithm" to persistedDefinitionCompatibility.digestAlgorithm,
            ),
            context = context,
        )
        val persistenceSession: WorkflowPersistenceSession<S> = persistence.session(
            workflowName = name,
            context = context,
            observer = observer,
            initialRevision = checkpoint.revision,
            workflowDefinitionCompatibility = definitionCompatibility,
        )
        return try {
            val resumedState = persistence.stateCodec.decode(checkpoint.statePayload)
            val finalState = executeTopLevelSteps(
                startIndex = checkpoint.nextStepIndex,
                state = resumedState,
                context = context,
                observer = observer,
                stepCounter = StepCounter(
                    stopPolicy = stopPolicy,
                    initialStepExecutions = checkpoint.stepExecutions,
                ),
                persistenceSession = persistenceSession,
                resumedCheckpointMetadata = checkpoint.metadata,
            )
            persistenceSession.complete(workflowName = name, context = context)
            observer.onWorkflowCompleted(name, context)
            resultSelector(finalState)
        } catch (suspended: WorkflowSuspendedException) {
            persistenceSession.abort()
            observer.onWorkflowEvent(
                workflowName = name,
                name = "tramai.workflow.suspended",
                attributes = mapOf("workflow_id" to context.workflowId),
                context = context,
            )
            throw suspended
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                persistenceSession.runCatchingAbort(error)
            }
            throw error
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            persistenceSession.runCatchingAbort(error)
            observer.onWorkflowFailed(name, error, context)
            throw error
        }
    }

    private suspend fun executeTopLevelSteps(
        startIndex: Int,
        state: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
        stepCounter: StepCounter,
        persistenceSession: WorkflowPersistenceSession<S>?,
        resumedCheckpointMetadata: Map<String, String>?,
    ): S {
        var currentState = state
        val services = executionServices()
        for (index in startIndex until steps.size) {
            val step = steps[index]
            val request = WorkflowStepExecutionRequest(
                workflowName = name,
                state = currentState,
                context = context,
                observer = observer,
                stepCounter = stepCounter,
                persistenceSession = persistenceSession,
                topLevelStepIndex = index,
                resumedCheckpointMetadata = if (index == startIndex) resumedCheckpointMetadata else null,
                services = services,
                executeNestedSteps = { nestedSteps, nestedState ->
                    executeSteps(
                        steps = nestedSteps,
                        state = nestedState,
                        context = context,
                        observer = observer,
                        stepCounter = stepCounter,
                        services = services,
                    )
                },
            )
            when (val result = stepExecutor.executeStep(step, request)) {
                is WorkflowStepExecutionResult.Completed -> currentState = result.state
                WorkflowStepExecutionResult.Suspended -> throw WorkflowSuspendedException(
                    "Workflow '$name' suspended at step '${step.name}' for workflowId='${context.workflowId}'",
                )
            }
            persistenceSession?.saveCheckpoint(
                state = currentState,
                nextStepIndex = index + 1,
                lastCompletedStepName = step.name,
                stepExecutions = stepCounter.stepExecutions,
            )
        }
        return currentState
    }

    private suspend fun executeSteps(
        steps: List<InternalWorkflowStep<S>>,
        state: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
        stepCounter: StepCounter,
        services: WorkflowStepExecutionServices,
    ): S {
        var currentState = state
        for (step in steps) {
            val request = WorkflowStepExecutionRequest(
                workflowName = name,
                state = currentState,
                context = context,
                observer = observer,
                stepCounter = stepCounter,
                persistenceSession = null,
                topLevelStepIndex = null,
                resumedCheckpointMetadata = null,
                services = services,
                executeNestedSteps = { nestedSteps, nestedState ->
                    executeSteps(
                        steps = nestedSteps,
                        state = nestedState,
                        context = context,
                        observer = observer,
                        stepCounter = stepCounter,
                        services = services,
                    )
                },
            )
            when (val result = stepExecutor.executeStep(step, request)) {
                is WorkflowStepExecutionResult.Completed -> currentState = result.state
                WorkflowStepExecutionResult.Suspended -> throw WorkflowSuspendedException(
                    "Workflow '$name' suspended at nested step '${step.name}', but nested checkpoint suspension is not supported",
                )
            }
        }
        return currentState
    }

    private fun executionServices(): WorkflowStepExecutionServices = WorkflowStepExecutionServices(
        clock = clock,
        httpTransport = httpTransport ?: JdkHttpTransport(httpClient),
        outboundNetworkPolicy = outboundNetworkPolicy,
        externalStepExecutorResolver = externalStepExecutorResolver,
        failureDiagnosticObserver = failureDiagnosticObserver,
    )
}
