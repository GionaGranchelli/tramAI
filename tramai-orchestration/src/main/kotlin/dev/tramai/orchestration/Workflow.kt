package dev.tramai.orchestration

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID

/**
 * Workflow-level execution metadata.
 */
data class WorkflowContext(
    val workflowId: String = UUID.randomUUID().toString(),
    val attributes: Map<String, Any?> = emptyMap(),
)

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
}

object NoOpWorkflowObserver : WorkflowObserver

/**
 * Raised when a workflow exceeds an explicit execution bound.
 */
class WorkflowLimitExceededException(
    message: String,
) : RuntimeException(message)

/**
 * Raised when a branch step selects a branch that is not configured.
 */
class WorkflowBranchSelectionException(
    message: String,
) : RuntimeException(message)

/**
 * Raised when an explicit workflow gate rejects further execution.
 */
class WorkflowGateRejectedException(
    message: String,
) : RuntimeException(message)

/**
 * Result returned by a first-class gate step.
 */
data class GateDecision(
    val allowed: Boolean,
    val reason: String? = null,
) {
    init {
        require(allowed || !reason.isNullOrBlank()) {
            "GateDecision.reason must be provided when a gate rejects execution"
        }
    }

    companion object {
        fun allow(): GateDecision = GateDecision(allowed = true)

        fun reject(reason: String): GateDecision = GateDecision(
            allowed = false,
            reason = reason,
        )
    }
}

/**
 * Executable typed workflow.
 */
class Workflow<S, R> internal constructor(
    val name: String,
    private val steps: List<InternalWorkflowStep<S>>,
    private val resultSelector: (S) -> R,
    private val stopPolicy: StopPolicy,
) {
    suspend fun run(
        initialState: S,
        context: WorkflowContext = WorkflowContext(),
        observer: WorkflowObserver = NoOpWorkflowObserver,
        persistence: WorkflowPersistence<S>? = null,
    ): R {
        observer.onWorkflowStarted(name, context)
        var persistenceSession: WorkflowPersistenceSession<S>? = null
        return try {
            val stepCounter = StepCounter(stopPolicy)
            persistenceSession = persistence?.session(
                workflowName = name,
                context = context,
                observer = observer,
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
            )
            persistenceSession?.complete(workflowName = name, context = context)
            observer.onWorkflowCompleted(name, context)
            resultSelector(finalState)
        } catch (error: Throwable) {
            persistenceSession?.runCatchingAbort(error)
            observer.onWorkflowFailed(name, error, context)
            throw error
        }
    }

    suspend fun resume(
        context: WorkflowContext,
        observer: WorkflowObserver = NoOpWorkflowObserver,
        persistence: WorkflowPersistence<S>,
    ): R {
        val checkpoint = persistence.checkpointStore.load(name, context.workflowId)
            ?: throw WorkflowResumeException(
                "No checkpoint exists for workflow '$name' and workflowId='${context.workflowId}'",
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
            ),
            context = context,
        )
        val persistenceSession: WorkflowPersistenceSession<S> = persistence.session(
            workflowName = name,
            context = context,
            observer = observer,
            initialRevision = checkpoint.revision,
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
            )
            persistenceSession.complete(workflowName = name, context = context)
            observer.onWorkflowCompleted(name, context)
            resultSelector(finalState)
        } catch (error: Throwable) {
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
    ): S {
        var currentState = state
        for (index in startIndex until steps.size) {
            val step = steps[index]
            currentState = executeStep(
                step = step,
                state = currentState,
                context = context,
                observer = observer,
                stepCounter = stepCounter,
            )
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
    ): S {
        var currentState = state
        for (step in steps) {
            currentState = executeStep(
                step = step,
                state = currentState,
                context = context,
                observer = observer,
                stepCounter = stepCounter,
            )
        }
        return currentState
    }

    private suspend fun executeStep(
        step: InternalWorkflowStep<S>,
        state: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
        stepCounter: StepCounter,
    ): S {
        stepCounter.beforeStep(name, step.name)
        observer.onStepStarted(name, step.name, context)
        val nextState = try {
            when (step) {
                is LocalWorkflowStep -> step.transform(state, context)
                is AiWorkflowStep<S, *, *> -> step.execute(state, context)
                is GateWorkflowStep -> step.execute(state, context)
                is BranchWorkflowStep -> {
                    val branchKey = step.select(state)
                    val branchSteps = step.branches[branchKey] ?: step.defaultSteps
                    if (branchSteps == null) {
                        throw WorkflowBranchSelectionException(
                            "Workflow '$name' selected unknown branch '$branchKey' at step '${step.name}'",
                        )
                    }
                    executeSteps(
                        steps = branchSteps,
                        state = state,
                        context = context,
                        observer = observer,
                        stepCounter = stepCounter,
                    )
                }
                is ParallelWorkflowStep<S, *, *> -> step.execute(
                    workflowName = name,
                    state = state,
                    context = context,
                    observer = observer,
                    stepCounter = stepCounter,
                )
            }
        } catch (error: Throwable) {
            observer.onStepFailed(name, step.name, error, context)
            throw error
        }
        observer.onStepCompleted(name, step.name, context)
        return nextState
    }
}

class WorkflowBuilder<S> internal constructor(
    private val workflowName: String,
) : AbstractWorkflowBuilder<S>() {
    fun <R> build(
        stopPolicy: StopPolicy = StopPolicy(),
        resultSelector: (S) -> R,
    ): Workflow<S, R> = Workflow(
        name = workflowName,
        steps = stepsSnapshot(),
        resultSelector = resultSelector,
        stopPolicy = stopPolicy,
    )
}

fun <S> workflow(
    name: String,
    configure: WorkflowBuilder<S>.() -> Unit,
): WorkflowBuilder<S> = WorkflowBuilder<S>(name).apply(configure)

abstract class AbstractWorkflowBuilder<S> {
    private val steps = mutableListOf<InternalWorkflowStep<S>>()

    fun localStep(
        name: String,
        transform: suspend (S, WorkflowContext) -> S,
    ) = apply {
        appendStep(LocalWorkflowStep(name = name, transform = transform))
    }

    fun <I, O> aiStep(
        name: String,
        input: (S) -> I,
        invoke: suspend (I) -> O,
        merge: (S, O) -> S,
    ) = apply {
        appendStep(AiWorkflowStep(
            name = name,
            input = input,
            invoke = invoke,
            merge = merge,
        ))
    }

    fun gateStep(
        name: String,
        decide: suspend (S, WorkflowContext) -> GateDecision,
    ) = apply {
        appendStep(GateWorkflowStep(
            name = name,
            decide = decide,
        ))
    }

    fun branchStep(
        name: String,
        select: (S) -> String,
        configure: BranchBuilder<S>.() -> Unit,
    ) = apply {
        val builder = BranchBuilder<S>().apply(configure)
        appendStep(BranchWorkflowStep(
            name = name,
            select = select,
            branches = builder.branches.toMap(),
            defaultSteps = builder.defaultSteps,
        ))
    }

    fun <I, O> parallelStep(
        name: String,
        items: (S) -> Iterable<I>,
        invoke: suspend (I) -> O,
        merge: (S, List<O>) -> S,
    ) = apply {
        appendStep(ParallelWorkflowStep(
            name = name,
            items = items,
            invoke = invoke,
            merge = merge,
        ))
    }

    internal fun appendStep(step: InternalWorkflowStep<S>) {
        steps += step
    }

    internal fun stepsSnapshot(): List<InternalWorkflowStep<S>> = steps.toList()
}

class BranchBuilder<S> {
    internal val branches = linkedMapOf<String, List<InternalWorkflowStep<S>>>()
    internal var defaultSteps: List<InternalWorkflowStep<S>>? = null

    fun branch(
        key: String,
        configure: BranchWorkflowBuilder<S>.() -> Unit,
    ) {
        branches[key] = BranchWorkflowBuilder<S>().apply(configure).stepsSnapshot()
    }

    fun default(
        configure: BranchWorkflowBuilder<S>.() -> Unit,
    ) {
        defaultSteps = BranchWorkflowBuilder<S>().apply(configure).stepsSnapshot()
    }
}

class BranchWorkflowBuilder<S> : AbstractWorkflowBuilder<S>() {
}

internal sealed interface InternalWorkflowStep<S> {
    val name: String
}

private data class LocalWorkflowStep<S>(
    override val name: String,
    val transform: suspend (S, WorkflowContext) -> S,
) : InternalWorkflowStep<S>

private data class AiWorkflowStep<S, I, O>(
    override val name: String,
    val input: (S) -> I,
    val invoke: suspend (I) -> O,
    val merge: (S, O) -> S,
) : InternalWorkflowStep<S> {
    suspend fun execute(
        state: S,
        @Suppress("UNUSED_PARAMETER") context: WorkflowContext,
    ): S = merge(state, invoke(input(state)))
}

private data class GateWorkflowStep<S>(
    override val name: String,
    val decide: suspend (S, WorkflowContext) -> GateDecision,
) : InternalWorkflowStep<S> {
    suspend fun execute(
        state: S,
        context: WorkflowContext,
    ): S {
        val decision = decide(state, context)
        if (!decision.allowed) {
            throw WorkflowGateRejectedException(
                "Workflow gate '$name' rejected execution: ${decision.reason}",
            )
        }
        return state
    }
}

private data class BranchWorkflowStep<S>(
    override val name: String,
    val select: (S) -> String,
    val branches: Map<String, List<InternalWorkflowStep<S>>>,
    val defaultSteps: List<InternalWorkflowStep<S>>?,
) : InternalWorkflowStep<S>

private data class ParallelWorkflowStep<S, I, O>(
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
        val pendingItems = items(state).toList()
        if (pendingItems.size > stepCounter.stopPolicy.maxParallelBranches) {
            throw WorkflowLimitExceededException(
                "Workflow '$workflowName' exceeded maxParallelBranches=${stepCounter.stopPolicy.maxParallelBranches} at step '$name'",
            )
        }

        val results = pendingItems.mapIndexed { index, item ->
            stepCounter.beforeParallelBranch(workflowName, name, index)
            observer.onStepStarted(workflowName, "$name[$index]", context)
            async {
                runCatching { invoke(item) }
                    .onSuccess { observer.onStepCompleted(workflowName, "$name[$index]", context) }
                    .onFailure { observer.onStepFailed(workflowName, "$name[$index]", it, context) }
                    .getOrThrow()
            }
        }.awaitAll()

        merge(state, results)
    }
}

private class StepCounter(
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

private class WorkflowPersistenceSession<S>(
    private val persistence: WorkflowPersistence<S>,
    private val workflowName: String,
    private val context: WorkflowContext,
    private val observer: WorkflowObserver,
    private var lease: WorkflowLease?,
    initialRevision: Long?,
) {
    private var currentRevision: Long? = initialRevision

    suspend fun saveCheckpoint(
        state: S,
        nextStepIndex: Int,
        lastCompletedStepName: String?,
        stepExecutions: Int,
    ) {
        val persisted = persistence.checkpointStore.save(
            checkpoint = WorkflowCheckpoint(
                workflowName = workflowName,
                workflowId = context.workflowId,
                nextStepIndex = nextStepIndex,
                stepExecutions = stepExecutions,
                lastCompletedStepName = lastCompletedStepName,
                statePayload = persistence.stateCodec.encode(state),
            ),
            expectedRevision = currentRevision,
        )
        currentRevision = persisted.revision
        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.checkpoint.saved",
            attributes = mapOf(
                "workflow_id" to persisted.workflowId,
                "next_step_index" to persisted.nextStepIndex,
                "step_executions" to persisted.stepExecutions,
                "revision" to persisted.revision,
                "has_last_completed_step" to (persisted.lastCompletedStepName != null),
            ),
            context = context,
        )
        renewLeaseIfPresent()
    }

    suspend fun complete(
        workflowName: String,
        context: WorkflowContext,
    ) {
        if (persistence.deleteCheckpointOnCompletion) {
            persistence.checkpointStore.delete(
                workflowName = workflowName,
                workflowId = context.workflowId,
                expectedRevision = currentRevision,
            )
        }
        releaseLeaseIfPresent()
    }

    suspend fun abort() {
        releaseLeaseIfPresent()
    }

    private suspend fun renewLeaseIfPresent() {
        val currentLease = lease ?: return
        val policy = persistence.leasePolicy ?: return
        val store = persistence.leaseStore ?: return
        lease = store.renew(
            lease = currentLease,
            checkpointRevision = currentRevision,
            leaseDurationMillis = policy.leaseDurationMillis,
        )
        val renewedLease = lease ?: return
        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.lease.renewed",
            attributes = leaseAttributes(renewedLease),
            context = context,
        )
    }

    private suspend fun releaseLeaseIfPresent() {
        val currentLease = lease ?: return
        val store = persistence.leaseStore ?: return
        store.release(currentLease)
        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.lease.released",
            attributes = leaseAttributes(currentLease),
            context = context,
        )
        lease = null
    }

    private fun leaseAttributes(lease: WorkflowLease): Map<String, Any?> = mapOf(
        "workflow_id" to lease.workflowId,
        "lease_id" to lease.leaseId,
        "owner_id" to lease.ownerId,
        "checkpoint_revision" to lease.checkpointRevision,
        "acquired_at_epoch_millis" to lease.acquiredAtEpochMillis,
        "expires_at_epoch_millis" to lease.expiresAtEpochMillis,
    )
}

private suspend fun <S> WorkflowPersistence<S>.session(
    workflowName: String,
    context: WorkflowContext,
    observer: WorkflowObserver,
    initialRevision: Long? = null,
): WorkflowPersistenceSession<S> = WorkflowPersistenceSession(
    persistence = this,
    workflowName = workflowName,
    context = context,
    observer = observer,
    lease = acquireLeaseIfConfigured(
        workflowName = workflowName,
        workflowId = context.workflowId,
        observer = observer,
        context = context,
        checkpointRevision = initialRevision,
    ),
    initialRevision = initialRevision,
)

private suspend fun <S> WorkflowPersistence<S>.acquireLeaseIfConfigured(
    workflowName: String,
    workflowId: String,
    observer: WorkflowObserver,
    context: WorkflowContext,
    checkpointRevision: Long?,
): WorkflowLease? {
    val store = leaseStore ?: return null
    val policy = leasePolicy ?: return null
    return try {
        store.claim(
            workflowName = workflowName,
            workflowId = workflowId,
            ownerId = policy.ownerId,
            checkpointRevision = checkpointRevision,
            leaseDurationMillis = policy.leaseDurationMillis,
        ).also { lease ->
            observer.onWorkflowEvent(
                workflowName = workflowName,
                name = "tramai.workflow.lease.claimed",
                attributes = mapOf(
                    "workflow_id" to lease.workflowId,
                    "lease_id" to lease.leaseId,
                    "owner_id" to lease.ownerId,
                    "checkpoint_revision" to lease.checkpointRevision,
                    "acquired_at_epoch_millis" to lease.acquiredAtEpochMillis,
                    "expires_at_epoch_millis" to lease.expiresAtEpochMillis,
                ),
                context = context,
            )
        }
    } catch (error: WorkflowLeaseConflictException) {
        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.lease.conflict",
            attributes = mapOf(
                "workflow_id" to workflowId,
                "owner_id" to policy.ownerId,
                "checkpoint_revision" to checkpointRevision,
                "error_type" to error::class.simpleName,
            ),
            context = context,
        )
        throw error
    }
}

private suspend fun <S> WorkflowPersistenceSession<S>.runCatchingAbort(
    error: Throwable,
) {
    runCatching { abort() }
        .onFailure { error.addSuppressed(it) }
}
