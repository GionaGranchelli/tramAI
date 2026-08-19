package dev.tramai.orchestration

import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Build-time DSL and validation for typed workflows.
 *
 * Construction-time concerns live here: step-addition DSL, duplicate-name
 * checks, static command-policy validation, and nested-suspension rejection.
 * Runtime execution does not live here — see [WorkflowRunner] and
 * [WorkflowStepExecutor].
 */
class WorkflowBuilder<S> constructor(
    private val workflowName: String,
    private val definitionVersion: String,
    private val stateType: KType,
) : AbstractWorkflowBuilder<S>() {
    var schedule: WorkflowScheduleDefinition? = null
    /** Outbound policy for every httpStep; frozen at build(). NOT on [AbstractWorkflowBuilder] so branch builders cannot set a policy the workflow ignores. */
    var outboundNetworkPolicy: OutboundNetworkPolicy = OutboundNetworkPolicies.defenceInDepth()
    var failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver = NoOpWorkflowStepFailureDiagnosticObserver

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
            httpTransport = httpTransport,
            outboundNetworkPolicy = outboundNetworkPolicy,
            failureDiagnosticObserver = failureDiagnosticObserver,
        )
    }
}

abstract class AbstractWorkflowBuilder<S> {
    private val steps = mutableListOf<InternalWorkflowStep<S>>()
    internal var httpTransport: HttpTransport? = null

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
     *
     * **Security note:** `aiStep` has no framework-owned prompt defense layer. If
     * `invoke` calls an LLM, that application code remains responsible for prompt
     * injection handling inside the invoked logic.
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
     *
     * **Security note:** `aiStep` does not apply the framework-owned prompt defenses
     * used by `hermesStep` and `codexStep`. If `invoke` calls an LLM, prompt
     * injection handling belongs inside that application-owned invocation path.
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

    /** Hostname pre-resolution is defence-in-depth only; the JDK transport cannot prove the connected peer address. Redirects are deny-by-default; deployments should enforce egress at the firewall, proxy, or service-mesh layer. */
    fun httpStep(name: String, config: HttpStepConfig = HttpStepConfig(), request: suspend (S, WorkflowContext) -> HttpRequest,
        merge: suspend (S, HttpResponse, WorkflowContext) -> S) = apply {
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
        definition: ShellCommandDefinition,
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

    fun branch(key: String, configure: BranchWorkflowBuilder<S>.() -> Unit) {
        require(key.isNotBlank()) { "Workflow branch key must not be blank" }
        require(!branches.containsKey(key)) { "Workflow branch key '$key' is already configured" }
        branches[key] = BranchWorkflowBuilder<S>().apply(configure).stepsSnapshot()
    }

    fun default(configure: BranchWorkflowBuilder<S>.() -> Unit) {
        require(defaultSteps == null) { "Workflow default branch is already configured" }
        defaultSteps = BranchWorkflowBuilder<S>().apply(configure).stepsSnapshot()
    }
}

class BranchWorkflowBuilder<S> : AbstractWorkflowBuilder<S>() {
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
    validateNoNestedSuspendingSteps(workflowName, steps)
}

private fun <S> validateNoNestedSuspendingSteps(
    workflowName: String,
    steps: List<InternalWorkflowStep<S>>,
) {
    for (step in steps) {
        if (step is BranchWorkflowStep) {
            step.branches.values.forEach { branchSteps ->
                rejectNestedSuspendingSteps(workflowName, branchSteps)
            }
            step.defaultSteps?.let { rejectNestedSuspendingSteps(workflowName, it) }
        }
    }
}

private fun <S> rejectNestedSuspendingSteps(
    workflowName: String,
    steps: List<InternalWorkflowStep<S>>,
) {
    for (step in steps) {
        if (step.suspensionMode != WorkflowStepSuspensionMode.NONE) {
            throw IllegalArgumentException(
                "Workflow '$workflowName' step '${step.name}' uses ${step.suspensionMode} suspension inside a nested branch. Checkpoint-suspending steps must be top-level.",
            )
        }
        if (step is BranchWorkflowStep) {
            step.branches.values.forEach { rejectNestedSuspendingSteps(workflowName, it) }
            step.defaultSteps?.let { rejectNestedSuspendingSteps(workflowName, it) }
        }
    }
}

private fun <S> validateStaticCommandPolicies(
    workflowName: String,
    steps: List<InternalWorkflowStep<S>>,
) {
    for (step in steps) {
        when (step) {
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
    else -> error(
        "Workflow plugin steps without an explicit merge function require a Map state. " +
            "State type '${state?.let { it::class.qualifiedName } ?: "null"}' is not supported.",
    )
}
