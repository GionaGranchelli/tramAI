@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
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
        governedRunIdentity: GovernedRunIdentity? = null,
    ): R {
        // Epic 5.3: the isolated observer is the single failure boundary for
        // every workflow telemetry callback. A throwing observer can never
        // turn a successful run into a failure or replace the primary error.
        val isolatedObserver = FailureIsolatingWorkflowObserver(observer)
        isolatedObserver.onWorkflowStarted(name, context)
        var persistenceSession: WorkflowPersistenceSession<S>? = null
        return try {
            val stepCounter = StepCounter(stopPolicy)
            persistenceSession =
                persistence?.session(
                    WorkflowSessionInputs(
                        workflowName = name,
                        context = context,
                        observer = isolatedObserver,
                        workflowDefinitionCompatibility = definitionCompatibility,
                        clock = clock,
                        governedRunIdentity = governedRunIdentity,
                    ),
                )
            persistenceSession?.saveCheckpoint(
                state = initialState,
                nextStepIndex = 0,
                lastCompletedStepName = null,
                stepExecutions = stepCounter.stepExecutions,
            )
            val finalState =
                executeTopLevelSteps(
                    startIndex = 0,
                    state = initialState,
                    stepCounter = stepCounter,
                    frame =
                        WorkflowExecutionFrame(
                            context = context,
                            observer = isolatedObserver,
                            persistenceSession = persistenceSession,
                            resumedCheckpointMetadata = null,
                            governedRunIdentity = governedRunIdentity,
                        ),
                )
            persistenceSession?.complete(workflowName = name, context = context)
            isolatedObserver.onWorkflowCompleted(name, context)
            resultSelector(finalState)
        } catch (suspended: WorkflowSuspendedException) {
            failSuspended(suspended, persistenceSession, isolatedObserver, context)
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                persistenceSession?.runCatchingAbort(error)
            }
            throw error
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            failRun(error, persistenceSession, isolatedObserver, context)
        }
    }

    suspend fun resume(
        context: WorkflowContext,
        observer: WorkflowObserver,
        persistence: WorkflowPersistence<S>,
        governedRunIdentity: GovernedRunIdentity? = null,
    ): R {
        val isolatedObserver = FailureIsolatingWorkflowObserver(observer)
        // Phases 1-2: load the run's own checkpoint and validate every continuity
        // precondition. Read-only — a failure here has mutated nothing.
        val (checkpoint, persistedDefinitionCompatibility) =
            loadResumeCheckpoint(
                context = context,
                persistence = persistence,
                governedRunIdentity = governedRunIdentity,
            )
        emitResumeStarted(context, isolatedObserver, checkpoint, persistedDefinitionCompatibility)
        // Phase 3: establish the session that will checkpoint the resumed run.
        val persistenceSession: WorkflowPersistenceSession<S> =
            persistence.session(
                WorkflowSessionInputs(
                    workflowName = name,
                    context = context,
                    observer = isolatedObserver,
                    workflowDefinitionCompatibility = definitionCompatibility,
                    clock = clock,
                    governedRunIdentity = governedRunIdentity,
                ),
                initialRevision = checkpoint.revision,
                initialGeneration = checkpoint.checkpointGeneration,
            )
        // Phase 4: execute the remaining steps.
        return try {
            val resumedState = persistence.stateCodec.decode(checkpoint.statePayload)
            val finalState =
                executeTopLevelSteps(
                    startIndex = checkpoint.nextStepIndex,
                    state = resumedState,
                    stepCounter =
                        StepCounter(
                            stopPolicy = stopPolicy,
                            initialStepExecutions = checkpoint.stepExecutions,
                        ),
                    frame =
                        WorkflowExecutionFrame(
                            context = context,
                            observer = isolatedObserver,
                            persistenceSession = persistenceSession,
                            resumedCheckpointMetadata = checkpoint.metadata,
                            governedRunIdentity = governedRunIdentity,
                        ),
                )
            persistenceSession.complete(workflowName = name, context = context)
            isolatedObserver.onWorkflowCompleted(name, context)
            resultSelector(finalState)
        } catch (suspended: WorkflowSuspendedException) {
            failSuspended(suspended, persistenceSession, isolatedObserver, context)
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                persistenceSession.runCatchingAbort(error)
            }
            throw error
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            failRun(error, persistenceSession, isolatedObserver, context)
        }
    }

    /** Resume telemetry: the run has started again and its checkpoint has been read. */
    private fun emitResumeStarted(
        context: WorkflowContext,
        observer: WorkflowObserver,
        checkpoint: WorkflowCheckpoint,
        persistedDefinitionCompatibility: WorkflowDefinitionCompatibility,
    ) {
        observer.onWorkflowStarted(name, context)
        observer.emitWorkflowEvent(
            workflowName = name,
            context = context,
            event =
                RuntimeEvent.of(RuntimeEvents.WORKFLOW_CHECKPOINT_LOADED) {
                    set(RuntimeAttributes.WORKFLOW_ID_BARE, checkpoint.workflowId)
                    set(RuntimeAttributes.NEXT_STEP_INDEX, checkpoint.nextStepIndex.toLong())
                    set(RuntimeAttributes.STEP_EXECUTIONS, checkpoint.stepExecutions.toLong())
                    set(RuntimeAttributes.REVISION, checkpoint.revision)
                    set(RuntimeAttributes.HAS_LAST_COMPLETED_STEP, checkpoint.lastCompletedStepName != null)
                    set(RuntimeAttributes.DEFINITION_VERSION, persistedDefinitionCompatibility.version)
                    set(RuntimeAttributes.DEFINITION_DIGEST_ALGORITHM, persistedDefinitionCompatibility.digestAlgorithm)
                },
        )
    }

    /**
     * A suspended resume aborts the session and reports the suspension, then propagates it:
     * suspension is an outcome, not a failure, so the session is not failed.
     */
    private suspend fun failSuspended(
        suspended: WorkflowSuspendedException,
        persistenceSession: WorkflowPersistenceSession<S>?,
        observer: FailureIsolatingWorkflowObserver,
        context: WorkflowContext,
    ): Nothing {
        persistenceSession?.abort()
        observer.emitWorkflowEvent(
            workflowName = name,
            context = context,
            event =
                RuntimeEvent.of(RuntimeEvents.WORKFLOW_SUSPENDED) {
                    set(RuntimeAttributes.WORKFLOW_ID_BARE, context.workflowId)
                },
        )
        throw suspended
    }

    /**
     * Resume phases 1-2: the checkpoint, the identity-continuity gate, resumability, and
     * definition compatibility — everything that must hold before a single step runs.
     */

    private suspend fun loadResumeCheckpoint(
        context: WorkflowContext,
        persistence: WorkflowPersistence<S>,
        governedRunIdentity: GovernedRunIdentity?,
    ): Pair<WorkflowCheckpoint, WorkflowDefinitionCompatibility> {
        val checkpoint =
            persistence.checkpointStore.load(name, context.workflowId)
                ?: throw WorkflowResumeException(
                    "No checkpoint exists for workflow '$name' and workflowId='${context.workflowId}'",
                )
        // Identity gate before any other resume validation: whole-identity continuity
        // (never run-id-only) and fail-closed decoding of partial attribution.
        requireGovernedRunAttributionContinuity(
            workflowName = name,
            workflowId = context.workflowId,
            persisted = decodeGovernedRunAttribution(checkpoint.workflowId, checkpoint.metadata),
            requested = governedRunIdentity,
        )
        checkpoint.requireResumePreconditions(name, steps.size)
        val persistedDefinitionCompatibility =
            checkpoint.requireWorkflowDefinitionCompatibility(
                workflowName = name,
                workflowId = context.workflowId,
            )
        requireCompatibleDefinition(
            workflowName = name,
            workflowId = context.workflowId,
            persisted = persistedDefinitionCompatibility,
            current = definitionCompatibility,
        )
        return checkpoint to persistedDefinitionCompatibility
    }

    private suspend fun executeTopLevelSteps(
        startIndex: Int,
        state: S,
        stepCounter: StepCounter,
        frame: WorkflowExecutionFrame<S>,
    ): S {
        var currentState = state
        val services = executionServices()
        for (index in startIndex until steps.size) {
            val step = steps[index]
            val request =
                WorkflowStepExecutionRequest(
                    workflowName = name,
                    state = currentState,
                    context = frame.context,
                    observer = frame.observer,
                    stepCounter = stepCounter,
                    persistenceSession = frame.persistenceSession,
                    topLevelStepIndex = index,
                    resumedCheckpointMetadata = if (index == startIndex) frame.resumedCheckpointMetadata else null,
                    services = services,
                    executeNestedSteps = { nestedSteps, nestedState ->
                        executeSteps(
                            steps = nestedSteps,
                            state = nestedState,
                            context = frame.context,
                            observer = frame.observer,
                            stepCounter = stepCounter,
                            services = services,
                        )
                    },
                )
            val stepResult =
                if (frame.governedRunIdentity == null) {
                    stepExecutor.executeStep(step, request)
                } else {
                    // 0.7.1d: the canonical governed identity is established for the whole
                    // step execution, so subsystems invoked from application step code (the
                    // engine, evidence emitters) read the same run identity instead of
                    // minting their own. Nested steps inherit this coroutine frame.context.
                    withContext(GovernedRunScope(frame.governedRunIdentity)) {
                        stepExecutor.executeStep(step, request)
                    }
                }
            when (val result = stepResult) {
                is WorkflowStepExecutionResult.Completed -> currentState = result.state

                WorkflowStepExecutionResult.Suspended -> throw WorkflowSuspendedException(
                    "Workflow '$name' suspended at step '${step.name}' for workflowId='${frame.context.workflowId}'",
                )
            }
            frame.persistenceSession?.saveCheckpoint(
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
            val request =
                WorkflowStepExecutionRequest(
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

    private fun executionServices(): WorkflowStepExecutionServices =
        WorkflowStepExecutionServices(
            clock = clock,
            httpTransport = httpTransport ?: JdkHttpTransport(httpClient),
            outboundNetworkPolicy = outboundNetworkPolicy,
            externalStepExecutorResolver = externalStepExecutorResolver,
            failureDiagnosticObserver = failureDiagnosticObserver,
        )

    /**
     * Terminal failure handling for one run: a cancellation aborts under [NonCancellable]
     * without notifying the observer as a failure, anything else aborts and is reported.
     * Never returns — the failure is always rethrown to the caller.
     */
    private suspend fun failRun(
        error: Throwable,
        persistenceSession: WorkflowPersistenceSession<S>?,
        observer: FailureIsolatingWorkflowObserver,
        context: WorkflowContext,
    ): Nothing {
        persistenceSession?.runCatchingAbort(error)
        observer.onWorkflowFailed(name, error, context)
        throw error
    }
}

/**
 * Everything the top-level step loop needs to stay stable across one run: the run context,
 * its observer, the session that checkpoints it, the metadata recovered on resume, and the
 * governed attribution it must carry unchanged. Threaded as one value so the loop cannot be
 * called with a half-updated shape.
 *
 * Operational per-step state (current state, step index, per-step metadata) is deliberately
 * NOT here: that belongs to the loop, not to the frame.
 */
internal data class WorkflowExecutionFrame<S>(
    val context: WorkflowContext,
    val observer: WorkflowObserver,
    val persistenceSession: WorkflowPersistenceSession<S>?,
    val resumedCheckpointMetadata: Map<String, String>?,
    val governedRunIdentity: GovernedRunIdentity?,
)
