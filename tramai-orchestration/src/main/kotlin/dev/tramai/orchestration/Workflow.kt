package dev.tramai.orchestration
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.net.http.HttpClient
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType
import kotlin.reflect.typeOf
/**
 * Workflow-level execution metadata.
 */
data class WorkflowContext(
    val workflowId: String = UUID.randomUUID().toString(),
    val attributes: Map<String, Any?> = emptyMap(),
)

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
 * Workflow schedule metadata supplied by optional scheduler modules.
 */
interface WorkflowScheduleDefinition {
    val kind: String
    val expression: String
    val zoneId: ZoneId
    fun validate()
    fun canonicalForm(): String = "$kind:${zoneId.id}:$expression"
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
    fun onScheduledTick(
        workflowName: String,
        scheduledFireAt: Instant,
        context: WorkflowContext,
    ) = Unit
    fun onSkippedTick(
        workflowName: String,
        scheduledFireAt: Instant,
        reason: String,
        context: WorkflowContext,
    ) = Unit
    fun onMissedTick(
        workflowName: String,
        scheduledFireAt: Instant,
        reason: String,
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
 * Raised when a workflow has durably checkpointed itself and yielded execution
 * so it can be resumed by an external scheduler or caller.
 */
class WorkflowSuspendedException(
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
    val definitionVersion: String,
    val stateType: KType,
    val resultType: KType,
    val schedule: WorkflowScheduleDefinition?,
    private val steps: List<InternalWorkflowStep<S>>,
    private val resultSelector: (S) -> R,
    private val stopPolicy: StopPolicy,
    private val clock: Clock,
    private val externalStepExecutorResolver: ExternalStepExecutorResolver,
    private val httpClient: HttpClient = WorkflowHttpClients.default,
) {
    private val definitionCompatibility: WorkflowDefinitionCompatibility = workflowDefinitionCompatibility(
        workflowName = name,
        definitionVersion = definitionVersion,
        schedule = schedule,
        stopPolicy = stopPolicy,
        steps = steps,
    )
    suspend fun run(
        initialState: S,
        context: WorkflowContext = WorkflowContext(),
        observer: WorkflowObserver = NoOpWorkflowObserver,
        persistence: WorkflowPersistence<S>? = null,
    ): R {
        if (persistence != null) {
            rememberWorkerWorkflowBinding(this, persistence)
        }
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
        rememberWorkerWorkflowBinding(this, persistence)
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
        } catch (error: Throwable) {
            persistenceSession.runCatchingAbort(error)
            observer.onWorkflowFailed(name, error, context)
            throw error
        }
    }

    fun requiredExternalStepTypes(): Set<String> = collectPluginStepTypes(steps)

    internal fun checkpointMetadata(): Map<String, String> = definitionCompatibility.toCheckpointMetadata()

    internal fun stepNameAt(index: Int): String? = steps.getOrNull(index)?.name

    internal fun topLevelStepNames(): Set<String> = steps.mapTo(linkedSetOf()) { it.name }

    internal suspend fun replayDescriptorAt(
        stepIndex: Int,
        state: S,
        context: WorkflowContext,
    ): WorkflowStepReplayDescriptor? = steps.getOrNull(stepIndex)?.replayDescriptor(state, context)

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
        for (index in startIndex until steps.size) {
            val step = steps[index]
            when (val result = executeStep(
                step = step,
                state = currentState,
                context = context,
                observer = observer,
                stepCounter = stepCounter,
                persistenceSession = persistenceSession,
                topLevelStepIndex = index,
                resumedCheckpointMetadata = if (index == startIndex) resumedCheckpointMetadata else null,
            )) {
                is StepExecutionResult.Completed -> currentState = result.state
                StepExecutionResult.Suspended -> throw WorkflowSuspendedException(
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
    ): S {
        var currentState = state
        for (step in steps) {
            when (val result = executeStep(
                step = step,
                state = currentState,
                context = context,
                observer = observer,
                stepCounter = stepCounter,
                persistenceSession = null,
                topLevelStepIndex = null,
                resumedCheckpointMetadata = null,
            )) {
                is StepExecutionResult.Completed -> currentState = result.state
                StepExecutionResult.Suspended -> throw WorkflowSuspendedException(
                    "Workflow '$name' suspended at nested step '${step.name}', but nested delay checkpointing is not supported",
                )
            }
        }
        return currentState
    }
    private suspend fun executeStep(
        step: InternalWorkflowStep<S>,
        state: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
        stepCounter: StepCounter,
        persistenceSession: WorkflowPersistenceSession<S>?,
        topLevelStepIndex: Int?,
        resumedCheckpointMetadata: Map<String, String>?,
    ): StepExecutionResult<S> {
        stepCounter.beforeStep(name, step.name)
        observer.onStepStarted(name, step.name, context)
        val nextState = try {
            when (step) {
                is LocalWorkflowStep -> step.transform(state, context)
                is AiWorkflowStep<S, *, *> -> step.execute(state, context)
                is HttpWorkflowStep<S> -> step.execute(
                    workflowName = name,
                    state = state,
                    context = context,
                    observer = observer,
                    httpClient = httpClient,
                )
                is ShellWorkflowStep<S> -> step.execute(
                    workflowName = name,
                    state = state,
                    context = context,
                    observer = observer,
                )
                is HermesWorkflowStep<S> -> step.execute(
                    workflowName = name,
                    state = state,
                    context = context,
                    observer = observer,
                )
                is CodexWorkflowStep<S> -> step.execute(
                    workflowName = name,
                    state = state,
                    context = context,
                    observer = observer,
                )
                is McpWorkflowStep<S> -> step.execute(
                    workflowName = name,
                    state = state,
                    context = context,
                    observer = observer,
                )
                is PluginWorkflowStep<S> -> step.execute(
                    workflowName = name,
                    state = state,
                    context = context,
                    executorResolver = externalStepExecutorResolver,
                )
                is GateWorkflowStep -> step.execute(state, context)
                is DelayWorkflowStep -> {
                    val result = step.execute(
                        workflowName = name,
                        state = state,
                        context = context,
                        observer = observer,
                        persistenceSession = persistenceSession,
                        topLevelStepIndex = topLevelStepIndex,
                        stepExecutions = stepCounter.stepExecutions,
                        resumedCheckpointMetadata = resumedCheckpointMetadata,
                        clock = clock,
                    )
                    if (result == DelayExecutionResult.Suspended) {
                        return StepExecutionResult.Suspended
                    }
                    state
                }
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
        return StepExecutionResult.Completed(nextState)
    }
}
class WorkflowBuilder<S> constructor(
    private val workflowName: String,
    private val definitionVersion: String,
    private val stateType: KType,
) : AbstractWorkflowBuilder<S>() {
    var schedule: WorkflowScheduleDefinition? = null

    inline fun <reified R> build(
        stopPolicy: StopPolicy = StopPolicy(),
        clock: Clock = Clock.systemUTC(),
        externalStepExecutorResolver: ExternalStepExecutorResolver = NoOpExternalStepExecutorResolver,
        noinline resultSelector: (S) -> R,
    ): Workflow<S, R> = buildTyped(
        stopPolicy = stopPolicy,
        clock = clock,
        externalStepExecutorResolver = externalStepExecutorResolver,
        resultType = typeOf<R>(),
        resultSelector = resultSelector,
    )

    @PublishedApi
    internal fun <R> buildTyped(
        stopPolicy: StopPolicy,
        clock: Clock,
        externalStepExecutorResolver: ExternalStepExecutorResolver,
        resultType: KType,
        resultSelector: (S) -> R,
    ): Workflow<S, R> {
        val snapshot = stepsSnapshot()
        schedule?.validate()
        validateWorkflowDefinition(
            workflowName = workflowName,
            steps = snapshot,
        )
        return Workflow(
            name = workflowName,
            definitionVersion = definitionVersion,
            stateType = stateType,
            resultType = resultType,
            schedule = schedule,
            steps = snapshot,
            resultSelector = resultSelector,
            stopPolicy = stopPolicy,
            clock = clock,
            externalStepExecutorResolver = externalStepExecutorResolver,
        )
    }
}
inline fun <reified S> workflow(
    name: String,
    definitionVersion: String = DEFAULT_WORKFLOW_DEFINITION_VERSION,
    configure: WorkflowBuilder<S>.() -> Unit,
): WorkflowBuilder<S> {
    require(name.isNotBlank()) {
        "Workflow.name must not be blank"
    }
    require(definitionVersion.isNotBlank()) {
        "Workflow.definitionVersion must not be blank"
    }
    return WorkflowBuilder<S>(
        workflowName = name,
        definitionVersion = definitionVersion,
        stateType = typeOf<S>(),
    ).apply(configure)
}
abstract class AbstractWorkflowBuilder<S> {
    private val steps = mutableListOf<InternalWorkflowStep<S>>()
    fun localStep(
        name: String,
        transform: suspend (S, WorkflowContext) -> S,
    ) = apply {
        appendStep(LocalWorkflowStep(name = name, transform = transform))
    }
    /**
     * Legacy aiStep overload — defaults to [ReplayPolicy.IDEMPOTENT] for backward
     * compatibility. Prefer the [WorkflowContext]-based overload below for new code.
     *
     * **Replay policy note:** The legacy overload defaults to `IDEMPOTENT` while the
     * [WorkflowContext]-based overload (which accepts `(S, WorkflowContext) -> I`)
     * defaults to `NON_REPLAYABLE`. This semantic split is intentional:
     * - Legacy: no access to [WorkflowContext], cannot compute idempotency keys.
     * - New: explicit replay policy with optional [WorkflowContext]-aware idempotency key.
     */
    fun <I, O> aiStep(
        name: String,
        input: (S) -> I,
        invoke: suspend (I) -> O,
        merge: (S, O) -> S,
    ) = apply {
        validateAiReplayPolicy(name, ReplayPolicy.IDEMPOTENT, idempotencyKeyConfigured = false)
        appendStep(AiWorkflowStep(
            name = name,
            replayDescriptor = fixedAiReplayDescriptor(ReplayPolicy.IDEMPOTENT),
            input = { state, _ -> input(state) },
            invoke = { value, _ -> invoke(value) },
            merge = { state, output, _ -> merge(state, output) },
        ))
    }

    fun <I, O> aiStep(
        name: String,
        replayPolicy: ReplayPolicy,
        input: (S) -> I,
        invoke: suspend (I) -> O,
        merge: (S, O) -> S,
    ) = apply {
        validateAiReplayPolicy(name, replayPolicy, idempotencyKeyConfigured = false)
        appendStep(AiWorkflowStep(
            name = name,
            replayDescriptor = fixedAiReplayDescriptor(replayPolicy),
            input = { state, _ -> input(state) },
            invoke = { value, _ -> invoke(value) },
            merge = { state, output, _ -> merge(state, output) },
        ))
    }

    fun <I, O> aiStep(
        name: String,
        replayPolicy: ReplayPolicy,
        idempotencyKey: (S, WorkflowContext) -> String?,
        input: (S) -> I,
        invoke: suspend (I) -> O,
        merge: (S, O) -> S,
    ) = apply {
        validateAiReplayPolicy(name, replayPolicy, idempotencyKeyConfigured = true)
        appendStep(AiWorkflowStep(
            name = name,
            replayDescriptor = aiReplayDescriptor(replayPolicy, idempotencyKey),
            input = { state, _ -> input(state) },
            invoke = { value, _ -> invoke(value) },
            merge = { state, output, _ -> merge(state, output) },
        ))
    }

    /**
     * aiStep overload with full [WorkflowContext] access — defaults to
     * [ReplayPolicy.NON_REPLAYABLE] for new code. This is the preferred overload
     * for all new workflow definitions.
     *
     * **Replay policy:** Unlike the legacy overload (which defaults to `IDEMPOTENT`),
     * this overload defaults to `NON_REPLAYABLE` because [WorkflowContext] access
     * enables explicit idempotency-key computation. Pass a custom [replayPolicy] and
     * [idempotencyKey] lambda when the step must be safely replayed by a worker.
     */
    fun <I, O> aiStep(
        name: String,
        replayPolicy: ReplayPolicy = ReplayPolicy.NON_REPLAYABLE,
        idempotencyKey: ((S, WorkflowContext) -> String?)? = null,
        input: (S, WorkflowContext) -> I,
        invoke: suspend (I, WorkflowContext) -> O,
        merge: (S, O, WorkflowContext) -> S,
    ) = apply {
        validateAiReplayPolicy(name, replayPolicy, idempotencyKeyConfigured = idempotencyKey != null)
        appendStep(AiWorkflowStep(
            name = name,
            replayDescriptor = aiReplayDescriptor(replayPolicy, idempotencyKey),
            input = input,
            invoke = invoke,
            merge = merge,
        ))
    }
    fun httpStep(
        name: String,
        config: HttpStepConfig = HttpStepConfig(),
        request: suspend (S, WorkflowContext) -> HttpRequest,
        merge: suspend (S, HttpResponse, WorkflowContext) -> S,
    ) = apply {
        appendStep(HttpWorkflowStep(
            name = name,
            requestBuilder = request,
            merge = merge,
            config = config,
        ))
    }
    fun shellStep(
        name: String,
        config: ShellStepConfig = ShellStepConfig(),
        definition: ShellCommandDefinition = ShellCommandDefinition(),
        command: suspend (S, WorkflowContext) -> ShellCommand,
        merge: suspend (S, ShellResult, WorkflowContext) -> S,
    ) = apply {
        appendStep(ShellWorkflowStep(
            name = name,
            definition = definition,
            commandBuilder = command,
            merge = merge,
            config = config,
        ))
    }
    fun hermesStep(
        name: String,
        config: HermesStepConfig = HermesStepConfig(),
        prompt: suspend (S, WorkflowContext) -> String,
        merge: suspend (S, String, WorkflowContext) -> S,
    ) = apply {
        appendStep(HermesWorkflowStep(
            name = name,
            promptBuilder = prompt,
            merge = merge,
            config = config,
        ))
    }
    fun <T> hermesStep(
        name: String,
        config: HermesStepConfig = HermesStepConfig(),
        prompt: suspend (S, WorkflowContext) -> String,
        decode: suspend (String) -> T,
        merge: suspend (S, T, WorkflowContext) -> S,
    ) = hermesStep(
        name = name,
        config = config,
        prompt = prompt,
        merge = { state, response, context ->
            merge(state, decode(response), context)
        },
    )
    fun codexStep(
        name: String,
        config: CodexStepConfig = CodexStepConfig(),
        prompt: suspend (S, WorkflowContext) -> String,
        merge: suspend (S, String, WorkflowContext) -> S,
    ) = apply {
        appendStep(CodexWorkflowStep(
            name = name,
            promptBuilder = prompt,
            merge = merge,
            config = config,
        ))
    }
    fun <T> codexStep(
        name: String,
        config: CodexStepConfig = CodexStepConfig(),
        prompt: suspend (S, WorkflowContext) -> String,
        decode: suspend (String) -> T,
        merge: suspend (S, T, WorkflowContext) -> S,
    ) = codexStep(
        name = name,
        config = config,
        prompt = prompt,
        merge = { state, response, context ->
            merge(state, decode(response), context)
        },
    )
    fun mcpStep(
        name: String,
        config: McpStepConfig = McpStepConfig(),
        definition: McpToolCallDefinition,
        toolCall: suspend (S, WorkflowContext) -> McpToolCall,
        merge: suspend (S, McpToolResult, WorkflowContext) -> S,
    ) = apply {
        appendStep(McpWorkflowStep(
            name = name,
            definition = definition,
            toolCallBuilder = toolCall,
            merge = merge,
            config = config,
        ))
    }
    fun <T> mcpStep(
        name: String,
        config: McpStepConfig = McpStepConfig(),
        definition: McpToolCallDefinition,
        toolCall: suspend (S, WorkflowContext) -> McpToolCall,
        decode: suspend (McpToolResult) -> T,
        merge: suspend (S, T, WorkflowContext) -> S,
    ) = mcpStep(
        name = name,
        config = config,
        definition = definition,
        toolCall = toolCall,
        merge = { state, result, context ->
            merge(state, decode(result), context)
        },
    )

    fun pluginStep(
        name: String,
        type: String,
        config: Map<String, Any?> = emptyMap(),
    ) = apply {
        appendStep(PluginWorkflowStep(
            name = name,
            type = type,
            config = config.toMap(),
            merge = ::mergePluginStepResult,
        ))
    }

    fun pluginStep(
        name: String,
        type: String,
        config: Map<String, Any?> = emptyMap(),
        merge: suspend (S, Map<String, Any?>, WorkflowContext) -> S,
    ) = apply {
        appendStep(PluginWorkflowStep(
            name = name,
            type = type,
            config = config.toMap(),
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
    fun delayStep(
        name: String,
        duration: Long,
        unit: TimeUnit,
    ) = apply {
        require(duration >= 0) {
            "Workflow delay step '$name' duration must not be negative"
        }
        appendStep(DelayWorkflowStep<S>(
            name = name,
            duration = duration,
            unit = unit,
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
        require(step.name.isNotBlank()) {
            "Workflow step name must not be blank"
        }
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
        require(key.isNotBlank()) { "Workflow branch key must not be blank" }
        require(!branches.containsKey(key)) {
            "Workflow branch key '$key' is already configured"
        }
        branches[key] = BranchWorkflowBuilder<S>().apply(configure).stepsSnapshot()
    }
    fun default(
        configure: BranchWorkflowBuilder<S>.() -> Unit,
    ) {
        require(defaultSteps == null) {
            "Workflow default branch is already configured"
        }
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
    val replayDescriptor: (S, WorkflowContext) -> WorkflowStepReplayDescriptor,
    val input: (S, WorkflowContext) -> I,
    val invoke: suspend (I, WorkflowContext) -> O,
    val merge: (S, O, WorkflowContext) -> S,
) : InternalWorkflowStep<S> {
    suspend fun execute(
        state: S,
        context: WorkflowContext,
    ): S = merge(state, invoke(input(state, context), context), context)
}

private fun validateAiReplayPolicy(
    stepName: String,
    replayPolicy: ReplayPolicy,
    idempotencyKeyConfigured: Boolean,
) {
    require(replayPolicy != ReplayPolicy.PURE) {
        "Workflow ai step '$stepName' does not support ReplayPolicy.PURE"
    }
    require(replayPolicy != ReplayPolicy.EXTERNALLY_IDEMPOTENT || idempotencyKeyConfigured) {
        "Workflow ai step '$stepName' requires an idempotencyKey when ReplayPolicy.EXTERNALLY_IDEMPOTENT is used"
    }
}

private fun <S> fixedAiReplayDescriptor(
    replayPolicy: ReplayPolicy,
): (S, WorkflowContext) -> WorkflowStepReplayDescriptor = { _, _ ->
    WorkflowStepReplayDescriptor(replayPolicy)
}

private fun <S> aiReplayDescriptor(
    replayPolicy: ReplayPolicy,
    idempotencyKey: ((S, WorkflowContext) -> String?)? = null,
): (S, WorkflowContext) -> WorkflowStepReplayDescriptor = { state, context ->
    val resolvedIdempotencyKey = if (replayPolicy == ReplayPolicy.EXTERNALLY_IDEMPOTENT) {
        requireNotNull(idempotencyKey) {
            "Workflow ai step replay descriptor requires an idempotencyKey when ReplayPolicy.EXTERNALLY_IDEMPOTENT is used"
        }
            .invoke(state, context)
            ?.takeUnless { it.isBlank() }
            ?: throw IllegalArgumentException(
                "Workflow ai step replay descriptor requires a non-blank idempotencyKey when ReplayPolicy.EXTERNALLY_IDEMPOTENT is used",
            )
    } else {
        null
    }
    WorkflowStepReplayDescriptor(
        replayPolicy = replayPolicy,
        idempotencyKey = resolvedIdempotencyKey,
    )
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
private data class PluginWorkflowStep<S>(
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
}
private data class DelayWorkflowStep<S>(
    override val name: String,
    val duration: Long,
    val unit: TimeUnit,
) : InternalWorkflowStep<S> {
    suspend fun execute(
        workflowName: String,
        state: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
        persistenceSession: WorkflowPersistenceSession<S>?,
        topLevelStepIndex: Int?,
        stepExecutions: Int,
        resumedCheckpointMetadata: Map<String, String>?,
        clock: Clock,
    ): DelayExecutionResult {
        val now = clock.instant()
        val resumedResumeAt = resumedCheckpointMetadata?.delayResumeAt(workflowName, context.workflowId, name)
        val resumeAt = resumedResumeAt ?: now.plusMillis(unit.toMillis(duration))
        if (!resumeAt.isAfter(now)) {
            observer.onWorkflowEvent(
                workflowName = workflowName,
                name = "tramai.workflow.delay.resumed",
                attributes = delayAttributes(context.workflowId, name, resumeAt),
                context = context,
            )
            return DelayExecutionResult.Completed
        }
        val session = persistenceSession
            ?: throw WorkflowResumeException(
                "Workflow '$workflowName' delay step '$name' requires WorkflowPersistence to checkpoint the delayed run",
            )
        val stepIndex = topLevelStepIndex
            ?: throw WorkflowResumeException(
                "Workflow '$workflowName' delay step '$name' must be a top-level step because checkpoints resume at top-level step boundaries",
            )
        session.saveCheckpoint(
            state = state,
            nextStepIndex = stepIndex,
            lastCompletedStepName = null,
            stepExecutions = stepExecutions,
            extraMetadata = delayMetadata(name, resumeAt),
        )
        session.scheduleDelayWakeup(
            stepName = name,
            resumeAt = resumeAt,
        )
        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = if (resumedResumeAt == null) {
                "tramai.workflow.delay.started"
            } else {
                "tramai.workflow.delay.waiting"
            },
            attributes = delayAttributes(context.workflowId, name, resumeAt),
            context = context,
        )
        return DelayExecutionResult.Suspended
    }
}
private data class BranchWorkflowStep<S>(
    override val name: String,
    val select: (S) -> String,
    val branches: Map<String, List<InternalWorkflowStep<S>>>,
    val defaultSteps: List<InternalWorkflowStep<S>>?,
) : InternalWorkflowStep<S>

private suspend fun <S> InternalWorkflowStep<S>.replayDescriptor(
    state: S,
    context: WorkflowContext,
): WorkflowStepReplayDescriptor = when (this) {
    is LocalWorkflowStep -> WorkflowStepReplayDescriptor(ReplayPolicy.PURE)
    is AiWorkflowStep<S, *, *> -> replayDescriptor(state, context)
    is HttpWorkflowStep<S> -> replayDescriptor(state, context)
    is ShellWorkflowStep<S> -> WorkflowStepReplayDescriptor(ReplayPolicy.NON_REPLAYABLE)
    is HermesWorkflowStep<S> -> WorkflowStepReplayDescriptor(ReplayPolicy.NON_REPLAYABLE)
    is CodexWorkflowStep<S> -> WorkflowStepReplayDescriptor(ReplayPolicy.NON_REPLAYABLE)
    is McpWorkflowStep<S> -> WorkflowStepReplayDescriptor(ReplayPolicy.NON_REPLAYABLE)
    is PluginWorkflowStep<S> -> WorkflowStepReplayDescriptor(ReplayPolicy.NON_REPLAYABLE)
    is GateWorkflowStep -> WorkflowStepReplayDescriptor(ReplayPolicy.PURE)
    is DelayWorkflowStep -> WorkflowStepReplayDescriptor(ReplayPolicy.PURE)
    is BranchWorkflowStep<S> -> WorkflowStepReplayDescriptor(ReplayPolicy.PURE)
    is ParallelWorkflowStep<S, *, *> -> WorkflowStepReplayDescriptor(ReplayPolicy.NON_REPLAYABLE)
}

private suspend fun <S> HttpWorkflowStep<S>.replayDescriptor(
    state: S,
    context: WorkflowContext,
): WorkflowStepReplayDescriptor {
    val request = requestBuilder(state, context)
    val method = request.method.trim().uppercase()
    return when (method) {
        "GET",
        "HEAD",
        "OPTIONS",
        // HTTP defines PUT and DELETE as idempotent, but replay safety still assumes the remote
        // application handler avoids additional side effects when the same request is repeated.
        "PUT",
        "DELETE",
        -> WorkflowStepReplayDescriptor(ReplayPolicy.IDEMPOTENT)

        "POST",
        "PATCH",
        -> {
            val idempotencyKey = request.headers.entries.firstOrNull { (name, _) ->
                name.equals("Idempotency-Key", ignoreCase = true)
            }?.value
            if (idempotencyKey.isNullOrBlank()) {
                WorkflowStepReplayDescriptor(ReplayPolicy.NON_REPLAYABLE)
            } else {
                WorkflowStepReplayDescriptor(
                    replayPolicy = ReplayPolicy.EXTERNALLY_IDEMPOTENT,
                    idempotencyKey = idempotencyKey,
                )
            }
        }

        else -> WorkflowStepReplayDescriptor(ReplayPolicy.NON_REPLAYABLE)
    }
}
private enum class DelayExecutionResult {
    Completed,
    Suspended,
}
private sealed interface StepExecutionResult<out S> {
    data class Completed<S>(val state: S) : StepExecutionResult<S>
    data object Suspended : StepExecutionResult<Nothing>
}
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
                runCatching { invoke(item) }
                    .onSuccess { observer.onStepCompleted(workflowName, "$name[$index]", context) }
                    .onFailure { observer.onStepFailed(workflowName, "$name[$index]", it, context) }
                    .getOrThrow()
            }
        }.awaitAll()
        merge(state, results)
    }
}
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
private fun <S> validateWorkflowDefinition(
    workflowName: String,
    steps: List<InternalWorkflowStep<S>>,
) {
    val names = linkedSetOf<String>()
    collectStepNames(steps, names) { duplicate ->
        throw IllegalArgumentException(
            "Workflow '$workflowName' has duplicate step name '$duplicate'. Step names must be unique across the full workflow definition.",
        )
    }
    validateStaticCommandPolicies(workflowName, steps)
}

private fun <S> validateStaticCommandPolicies(
    workflowName: String,
    steps: List<InternalWorkflowStep<S>>,
) {
    for (step in steps) {
        when (step) {
            is ShellWorkflowStep<*> -> step.validateStaticCommandPolicy(workflowName)
            is McpWorkflowStep<*> -> step.validateStaticCommandPolicy(workflowName)
            is BranchWorkflowStep -> {
                step.branches.values.forEach { validateStaticCommandPolicies(workflowName, it) }
                step.defaultSteps?.let { validateStaticCommandPolicies(workflowName, it) }
            }
            else -> Unit
        }
    }
}
private fun <S> collectStepNames(
    steps: List<InternalWorkflowStep<S>>,
    names: MutableSet<String>,
    onDuplicate: (String) -> Nothing,
) {
    for (step in steps) {
        if (!names.add(step.name)) {
            onDuplicate(step.name)
        }
        if (step is BranchWorkflowStep) {
            for (branchSteps in step.branches.values) {
                collectStepNames(branchSteps, names, onDuplicate)
            }
            val defaultSteps = step.defaultSteps
            if (defaultSteps != null) {
                collectStepNames(defaultSteps, names, onDuplicate)
            }
        }
    }
}
private fun <S> workflowDefinitionCompatibility(
    workflowName: String,
    definitionVersion: String,
    schedule: WorkflowScheduleDefinition?,
    stopPolicy: StopPolicy,
    steps: List<InternalWorkflowStep<S>>,
): WorkflowDefinitionCompatibility {
    val canonical = buildString {
        append("workflow:")
        append(workflowName)
        append('\n')
        append("stop_policy.max_step_executions:")
        append(stopPolicy.maxStepExecutions)
        append('\n')
        append("stop_policy.max_parallel_branches:")
        append(stopPolicy.maxParallelBranches)
        append('\n')
        append("schedule:")
        append(schedule?.canonicalForm() ?: "none")
        append('\n')
        append(renderStepsCanonical(steps))
    }
    return WorkflowDefinitionCompatibility(
        version = definitionVersion,
        digest = sha256Hex(canonical),
        digestAlgorithm = WORKFLOW_DEFINITION_DIGEST_ALGORITHM,
    )
}
private fun <S> renderStepsCanonical(
    steps: List<InternalWorkflowStep<S>>,
): String = buildString {
    for (step in steps) {
        when (step) {
            is LocalWorkflowStep -> {
                append("local:")
                append(step.name)
                append('\n')
            }
            is AiWorkflowStep<*, *, *> -> {
                append("ai:")
                append(step.name)
                append('\n')
            }
            is HttpWorkflowStep<*> -> {
                append("http:")
                append(step.name)
                append(':')
                append(step.config.timeoutSeconds)
                append(':')
                append(step.config.maxResponseBytes)
                append(':')
                append(step.config.maxRetries)
                append(':')
                append(step.config.retryOnStatus.sorted().joinToString(","))
                append('\n')
            }
            is ShellWorkflowStep<*> -> {
                append("shell:")
                append(step.name)
                append(':')
                append(step.config.timeoutSeconds)
                append(':')
                append(step.config.maxOutputBytes)
                append(':')
                append(step.config.failOnNonZeroExit)
                append(':')
                append(step.config.failOnStderr)
                append(':')
                append(step.config.allowedCommands.sorted().joinToString(","))
                append(':')
                append(step.config.deniedCommands.sorted().joinToString(","))
                append(':')
                append(step.definition.hasWorkdir)
                append(':')
                append(step.definition.envKeys.sorted().joinToString(","))
                append('\n')
            }
            is HermesWorkflowStep<*> -> {
                append("hermes:")
                append(step.name)
                append(':')
                append(step.config.timeoutSeconds)
                append(':')
                append(step.config.maxOutputBytes)
                append(':')
                append(step.config.cliPath)
                append(':')
                append(step.config.model)
                append('\n')
            }
            is CodexWorkflowStep<*> -> {
                append("codex:")
                append(step.name)
                append(':')
                append(step.config.timeoutSeconds)
                append(':')
                append(step.config.maxOutputBytes)
                append(':')
                append(step.config.cliPath)
                append(':')
                append(step.config.workdir ?: "*")
                append('\n')
            }
            is McpWorkflowStep<*> -> {
                append("mcp:")
                append(step.name)
                append(':')
                append(step.config.timeoutSeconds)
                append(':')
                append(step.config.maxOutputBytes)
                append(':')
                append(step.config.reconnect)
                append(':')
                append(step.config.toolAllowlist?.sorted()?.joinToString(",") ?: "*")
                append(':')
                append(
                    if (step.config.enforceCommandAllowlist) {
                        step.config.allowedCommands.sorted().joinToString(",")
                    } else {
                        "*"
                    },
                )
                append(':')
                append(step.config.deniedCommands.sorted().joinToString(","))
                append(':')
                append(step.definition.serverCommand.joinToString(","))
                append(':')
                append(step.definition.serverEnv.map { (k, v) -> "$k=$v" }.sorted().joinToString(","))
                append(':')
                append(step.definition.toolName)
                append(':')
                append(step.definition.argumentKeys.sorted().joinToString(","))
                append('\n')
            }
            is PluginWorkflowStep<*> -> {
                append("plugin:")
                append(step.name)
                append(':')
                append(step.type)
                append(':')
                append(renderPluginValueCanonical(step.config))
                append('\n')
            }
            is GateWorkflowStep -> {
                append("gate:")
                append(step.name)
                append('\n')
            }
            is DelayWorkflowStep -> {
                append("delay:")
                append(step.name)
                append(':')
                append(step.duration)
                append(':')
                append(step.unit.name)
                append('\n')
            }
            is ParallelWorkflowStep<*, *, *> -> {
                append("parallel:")
                append(step.name)
                append('\n')
            }
            is BranchWorkflowStep -> {
                append("branch:")
                append(step.name)
                append('\n')
                for ((key, branchSteps) in step.branches) {
                    append("branch-key:")
                    append(key)
                    append('\n')
                    append(renderStepsCanonical(branchSteps))
                }
                val defaultSteps = step.defaultSteps
                if (defaultSteps != null) {
                    append("branch-default:")
                    append(step.name)
                    append('\n')
                    append(renderStepsCanonical(defaultSteps))
                }
            }
        }
    }
}
const val DEFAULT_WORKFLOW_DEFINITION_VERSION: String = "1"
private const val WORKFLOW_DEFINITION_VERSION_METADATA_KEY: String =
    "tramai.workflow.definition.version"
private const val WORKFLOW_DEFINITION_DIGEST_METADATA_KEY: String =
    "tramai.workflow.definition.digest"
private const val WORKFLOW_DEFINITION_DIGEST_ALGORITHM_METADATA_KEY: String =
    "tramai.workflow.definition.digest.algorithm"
private const val WORKFLOW_DEFINITION_DIGEST_ALGORITHM: String = "SHA-256"
private const val WORKFLOW_DELAY_STEP_METADATA_KEY: String =
    "tramai.workflow.delay.step"
private const val WORKFLOW_DELAY_RESUME_AT_EPOCH_MILLIS_METADATA_KEY: String =
    "tramai.workflow.delay.resume_at_epoch_millis"
private data class WorkflowDefinitionCompatibility(
    val version: String,
    val digest: String,
    val digestAlgorithm: String,
)
private class WorkflowPersistenceSession<S>(
    private val persistence: WorkflowPersistence<S>,
    private val workflowName: String,
    private val context: WorkflowContext,
    private val observer: WorkflowObserver,
    private val workflowDefinitionCompatibility: WorkflowDefinitionCompatibility,
    private var lease: WorkflowLease?,
    initialRevision: Long?,
) {
    private var currentRevision: Long? = initialRevision
    suspend fun saveCheckpoint(
        state: S,
        nextStepIndex: Int,
        lastCompletedStepName: String?,
        stepExecutions: Int,
        extraMetadata: Map<String, String> = emptyMap(),
    ) {
        val persisted = persistence.checkpointStore.save(
            checkpoint = WorkflowCheckpoint(
                workflowName = workflowName,
                workflowId = context.workflowId,
                nextStepIndex = nextStepIndex,
                stepExecutions = stepExecutions,
                lastCompletedStepName = lastCompletedStepName,
                statePayload = persistence.stateCodec.encode(state),
                metadata = workflowDefinitionCompatibility.toCheckpointMetadata() + extraMetadata,
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
                "definition_version" to workflowDefinitionCompatibility.version,
                "definition_digest_algorithm" to workflowDefinitionCompatibility.digestAlgorithm,
            ),
            context = context,
        )
        renewLeaseIfPresent()
    }
    suspend fun scheduleDelayWakeup(
        stepName: String,
        resumeAt: Instant,
    ) {
        persistence.delayWakeupScheduler?.scheduleDelayWakeup(
            runId = context.workflowId,
            stepId = stepName,
            resumeAt = resumeAt,
        )
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
    workflowDefinitionCompatibility: WorkflowDefinitionCompatibility,
    initialRevision: Long? = null,
): WorkflowPersistenceSession<S> = WorkflowPersistenceSession(
    persistence = this,
    workflowName = workflowName,
    context = context,
    observer = observer,
    workflowDefinitionCompatibility = workflowDefinitionCompatibility,
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
private fun WorkflowDefinitionCompatibility.toCheckpointMetadata(): Map<String, String> = mapOf(
    WORKFLOW_DEFINITION_VERSION_METADATA_KEY to version,
    WORKFLOW_DEFINITION_DIGEST_METADATA_KEY to digest,
    WORKFLOW_DEFINITION_DIGEST_ALGORITHM_METADATA_KEY to digestAlgorithm,
)
private fun delayMetadata(
    stepName: String,
    resumeAt: Instant,
): Map<String, String> = mapOf(
    WORKFLOW_DELAY_STEP_METADATA_KEY to stepName,
    WORKFLOW_DELAY_RESUME_AT_EPOCH_MILLIS_METADATA_KEY to resumeAt.toEpochMilli().toString(),
)
private fun delayAttributes(
    workflowId: String,
    stepName: String,
    resumeAt: Instant,
): Map<String, Any?> = mapOf(
    "workflow_id" to workflowId,
    "step_name" to stepName,
    "resume_at_epoch_millis" to resumeAt.toEpochMilli(),
)
private fun Map<String, String>.delayResumeAt(
    workflowName: String,
    workflowId: String,
    stepName: String,
): Instant? {
    val persistedStepName = this[WORKFLOW_DELAY_STEP_METADATA_KEY] ?: return null
    if (persistedStepName != stepName) {
        throw WorkflowResumeException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' contains delay metadata for step '$persistedStepName', but resume reached step '$stepName'",
        )
    }
    val rawResumeAt = this[WORKFLOW_DELAY_RESUME_AT_EPOCH_MILLIS_METADATA_KEY]
        ?: throw WorkflowResumeException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' is missing delay resume-at metadata for step '$stepName'",
        )
    val epochMillis = rawResumeAt.toLongOrNull()
        ?: throw WorkflowResumeException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' has invalid delay resume-at metadata '$rawResumeAt' for step '$stepName'",
        )
    return Instant.ofEpochMilli(epochMillis)
}
private fun WorkflowCheckpoint.requireWorkflowDefinitionCompatibility(
    workflowName: String,
    workflowId: String,
): WorkflowDefinitionCompatibility {
    val version = metadata[WORKFLOW_DEFINITION_VERSION_METADATA_KEY]
        ?: throw missingDefinitionMetadataException(
            workflowName = workflowName,
            workflowId = workflowId,
            missingKey = WORKFLOW_DEFINITION_VERSION_METADATA_KEY,
        )
    val digest = metadata[WORKFLOW_DEFINITION_DIGEST_METADATA_KEY]
        ?: throw missingDefinitionMetadataException(
            workflowName = workflowName,
            workflowId = workflowId,
            missingKey = WORKFLOW_DEFINITION_DIGEST_METADATA_KEY,
        )
    val digestAlgorithm = metadata[WORKFLOW_DEFINITION_DIGEST_ALGORITHM_METADATA_KEY]
        ?: throw missingDefinitionMetadataException(
            workflowName = workflowName,
            workflowId = workflowId,
            missingKey = WORKFLOW_DEFINITION_DIGEST_ALGORITHM_METADATA_KEY,
        )
    return WorkflowDefinitionCompatibility(
        version = version,
        digest = digest,
        digestAlgorithm = digestAlgorithm,
    )
}
private fun requireCompatibleDefinition(
    workflowName: String,
    workflowId: String,
    persisted: WorkflowDefinitionCompatibility,
    current: WorkflowDefinitionCompatibility,
) {
    if (persisted.version != current.version) {
        throw WorkflowResumeException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' was created with definitionVersion='${persisted.version}', but the current workflow uses definitionVersion='${current.version}'",
        )
    }
    if (persisted.digestAlgorithm != current.digestAlgorithm) {
        throw WorkflowResumeException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' uses definition digest algorithm '${persisted.digestAlgorithm}', but the current workflow uses '${current.digestAlgorithm}'",
        )
    }
    if (persisted.digest != current.digest) {
        throw WorkflowResumeException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' was created from a different workflow definition digest. persisted='${persisted.digest}', current='${current.digest}'",
        )
    }
}
private fun missingDefinitionMetadataException(
    workflowName: String,
    workflowId: String,
    missingKey: String,
): WorkflowResumeException = WorkflowResumeException(
    "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' is missing required workflow definition metadata '$missingKey'. Checkpoints created before the stable resume-compatibility contract cannot be resumed.",
)
private fun sha256Hex(value: String): String = MessageDigest
    .getInstance(WORKFLOW_DEFINITION_DIGEST_ALGORITHM)
    .digest(value.toByteArray())
    .joinToString(separator = "") { byte ->
        byte.toInt().and(0xff).toString(16).padStart(2, '0')
    }

private fun collectPluginStepTypes(steps: List<InternalWorkflowStep<*>>): Set<String> = buildSet {
    steps.forEach { step ->
        when (step) {
            is PluginWorkflowStep<*> -> add(step.type)
            is BranchWorkflowStep<*> -> {
                step.branches.values.forEach { branchSteps ->
                    addAll(collectPluginStepTypes(branchSteps))
                }
                step.defaultSteps?.let { addAll(collectPluginStepTypes(it)) }
            }
            else -> Unit
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <S> mergePluginStepResult(
    state: S,
    result: Map<String, Any?>,
    @Suppress("UNUSED_PARAMETER") context: WorkflowContext,
): S = when (state) {
    is Map<*, *> -> LinkedHashMap<String, Any?>().apply {
        putAll(state as Map<String, Any?>)
        putAll(result)
    } as S
    else -> throw IllegalStateException(
        "Workflow plugin steps without an explicit merge function require a Map state. " +
            "State type '${state?.let { it::class.qualifiedName } ?: "null"}' is not supported.",
    )
}

private fun renderPluginValueCanonical(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries
        .sortedBy { it.key?.toString() ?: "" }
        .joinToString(prefix = "{", postfix = "}") { entry ->
            "${renderPluginValueCanonical(entry.key?.toString())}:${renderPluginValueCanonical(entry.value)}"
        }
    is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { element ->
        renderPluginValueCanonical(element)
    }
    is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { element ->
        renderPluginValueCanonical(element)
    }
    else -> renderPluginValueCanonical(value.toString())
}
