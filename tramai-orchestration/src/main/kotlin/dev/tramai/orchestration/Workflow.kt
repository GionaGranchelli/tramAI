package dev.tramai.orchestration

import java.net.http.HttpClient
import java.time.Clock
import java.time.ZoneId
import kotlin.reflect.KType
import kotlin.reflect.typeOf

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
 * Executable typed workflow: an immutable definition plus a thin runtime
 * facade. Definition/introspection concerns live here; run/resume lifecycle
 * coordination is delegated to [WorkflowRunner], step execution to
 * [WorkflowStepExecutor], and definition compatibility to
 * [WorkflowDefinitionCompatibility].
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
    private val httpTransport: HttpTransport? = null,
    private val outboundNetworkPolicy: OutboundNetworkPolicy = OutboundNetworkPolicies.defenceInDepth(),
    private val failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver = NoOpWorkflowStepFailureDiagnosticObserver,
) {
    private val definitionCompatibility: WorkflowDefinitionCompatibility = workflowDefinitionCompatibility(
        workflowName = name,
        definitionVersion = definitionVersion,
        schedule = schedule,
        stopPolicy = stopPolicy,
        steps = steps,
    )

    private val runner = WorkflowRunner(
        name = name,
        steps = steps,
        resultSelector = resultSelector,
        stopPolicy = stopPolicy,
        clock = clock,
        externalStepExecutorResolver = externalStepExecutorResolver,
        httpClient = httpClient,
        httpTransport = httpTransport,
        outboundNetworkPolicy = outboundNetworkPolicy,
        failureDiagnosticObserver = failureDiagnosticObserver,
        definitionCompatibility = definitionCompatibility,
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
        return runner.run(initialState, context, observer, persistence)
    }

    suspend fun resume(
        context: WorkflowContext,
        observer: WorkflowObserver = NoOpWorkflowObserver,
        persistence: WorkflowPersistence<S>,
    ): R {
        rememberWorkerWorkflowBinding(this, persistence)
        return runner.resume(context, observer, persistence)
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
}

const val DEFAULT_WORKFLOW_DEFINITION_VERSION: String = "1"

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

// ─── Definition-level introspection helpers ──────────────────────────────────
// Replay-descriptor dispatch and plugin-step-type collection are definition
// concerns (Epic 4.2 non-goal: they stay concrete).

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
        "PUT", // idempotent per HTTP; replay safety still assumes no extra side effects on repeat
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
