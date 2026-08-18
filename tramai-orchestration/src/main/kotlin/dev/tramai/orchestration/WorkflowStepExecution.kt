package dev.tramai.orchestration

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

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

// ─── Built-in step implementations (simple steps) ───────────────────────────

internal data class LocalWorkflowStep<S>(
    override val name: String,
    val transform: suspend (S, WorkflowContext) -> S,
) : InternalWorkflowStep<S> {
    override suspend fun execute(
        request: WorkflowStepExecutionRequest<S>,
    ): WorkflowStepExecutionResult<S> = WorkflowStepExecutionResult.Completed(
        transform(request.state, request.context),
    )
}

internal data class AiWorkflowStep<S, I, O>(
    override val name: String,
    val replayDescriptor: (S, WorkflowContext) -> WorkflowStepReplayDescriptor,
    val input: (S, WorkflowContext) -> I,
    val invoke: suspend (I, WorkflowContext) -> O,
    val merge: (S, O, WorkflowContext) -> S,
) : InternalWorkflowStep<S> {
    suspend fun execute(
        state: S,
        context: WorkflowContext,
    ): S = merge(state, invoke(input(state, context), context), context)

    override suspend fun execute(
        request: WorkflowStepExecutionRequest<S>,
    ): WorkflowStepExecutionResult<S> = WorkflowStepExecutionResult.Completed(
        execute(request.state, request.context),
    )
}

internal data class GateWorkflowStep<S>(
    override val name: String,
    val decide: suspend (S, WorkflowContext) -> GateDecision,
) : InternalWorkflowStep<S> {
    override suspend fun execute(
        request: WorkflowStepExecutionRequest<S>,
    ): WorkflowStepExecutionResult<S> {
        val decision = decide(request.state, request.context)
        if (!decision.allowed) {
            throw WorkflowGateRejectedException(
                "Workflow gate '$name' rejected execution: ${decision.reason}",
            )
        }
        return WorkflowStepExecutionResult.Completed(request.state)
    }
}

internal data class PluginWorkflowStep<S>(
    override val name: String,
    val type: String,
    val config: Map<String, Any?>,
    val merge: suspend (S, Map<String, Any?>, WorkflowContext) -> S,
) : InternalWorkflowStep<S> {
    suspend fun execute(
        workflowName: String,
        state: S,
        context: WorkflowContext,
        executorResolver: ExternalStepExecutorResolver,
    ): S {
        require(type.isNotBlank()) {
            "Workflow '$workflowName' plugin step '$name' type must not be blank"
        }
        val result = executorResolver.create(type).execute(config)
        return merge(state, result, context)
    }

    override suspend fun execute(
        request: WorkflowStepExecutionRequest<S>,
    ): WorkflowStepExecutionResult<S> = WorkflowStepExecutionResult.Completed(
        execute(
            workflowName = request.workflowName,
            state = request.state,
            context = request.context,
            executorResolver = request.services.externalStepExecutorResolver,
        ),
    )
}

// ─── External step executor support (plugin steps) ──────────────────────────

interface ExternalStepExecutorFactory {
    val typeId: String
    fun create(): ExternalStepExecutor
}

fun interface ExternalStepExecutor {
    suspend fun execute(spec: Map<String, Any?>): Map<String, Any?>
}

class ExternalStepExecutorNotRegisteredException(
    typeId: String,
) : RuntimeException("No external step executor is registered for plugin step type '$typeId'")

interface ExternalStepExecutorResolver {
    fun isRegistered(typeId: String): Boolean
    fun registeredTypeIds(): Set<String>
    fun create(typeId: String): ExternalStepExecutor
}

class ExternalStepExecutorRegistry : ExternalStepExecutorResolver {
    private val factories = ConcurrentHashMap<String, ExternalStepExecutorFactory>()

    fun register(factory: ExternalStepExecutorFactory) {
        require(factory.typeId.isNotBlank()) { "External step executor typeId must not be blank" }
        factories[factory.typeId] = factory
    }

    fun unregister(typeId: String) {
        factories.remove(typeId)
    }

    fun clear() {
        factories.clear()
    }

    fun replaceAll(factories: Collection<ExternalStepExecutorFactory>) {
        val replacements = linkedMapOf<String, ExternalStepExecutorFactory>()
        factories.forEach { factory ->
            require(factory.typeId.isNotBlank()) { "External step executor typeId must not be blank" }
            replacements[factory.typeId] = factory
        }
        this.factories.clear()
        this.factories.putAll(replacements)
    }

    override fun isRegistered(typeId: String): Boolean = factories.containsKey(typeId)

    override fun registeredTypeIds(): Set<String> = factories.keys.toSortedSet()

    fun typeIds(): Set<String> = registeredTypeIds()

    override fun create(typeId: String): ExternalStepExecutor =
        factories[typeId]?.create() ?: throw ExternalStepExecutorNotRegisteredException(typeId)
}

object NoOpExternalStepExecutorResolver : ExternalStepExecutorResolver {
    override fun isRegistered(typeId: String): Boolean = false

    override fun registeredTypeIds(): Set<String> = emptySet()

    override fun create(typeId: String): ExternalStepExecutor =
        throw ExternalStepExecutorNotRegisteredException(typeId)
}
