package dev.tramai.engine.provider

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ConversationIdProvider
import dev.tramai.core.model.ClassifiedDocument
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.UsageMetrics
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.core.provider.StreamCapable
import dev.tramai.engine.CircuitBreakerAdmission
import dev.tramai.engine.CircuitBreakerPermit
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.DefaultEngineIdentitySource
import dev.tramai.engine.ModelRegistryEnforcer
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.ProviderRetryDelayPolicy
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.engine.TokenBudgetSettings
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.budget.TokenBudgetCoordinator
import dev.tramai.engine.memory.ConversationMemoryCoordinator
import dev.tramai.engine.planning.OperationDefinitionCompiler
import dev.tramai.engine.planning.OperationFingerprintFactory
import dev.tramai.engine.planning.ServiceDefinitionCompiler
import dev.tramai.engine.provider.ProviderGovernanceConfiguration
import dev.tramai.engine.streaming.StreamingBeforeResponseReturnGate
import dev.tramai.engine.streaming.StreamingExecutionCoordinator
import dev.tramai.engine.streaming.StreamingExecutionRequest
import dev.tramai.engine.tool.ToolExposureCoordinator
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.governance.NamedTrustZone
import dev.tramai.security.governance.ProviderDeployment
import dev.tramai.security.governance.TrustZoneName
import dev.tramai.security.governance.TrustZonePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

/**
 * Support for the retry/fallback lifecycle property harness: the recorded doubles, the model and
 * reality traces, the invariant assertions and the lane recorders.
 *
 * Everything here was moved out of ProviderRetryFallbackLifecyclePropertyTest verbatim - the class
 * keeps its four tests plus the three members that carry Detekt baseline entries (runModel,
 * runReality, recordLane), because relocating a baselined member re-signs its findings as new.
 * Same package and top-level, so every call site reads exactly as before.
 */
@AiService
internal interface StreamingServiceRetries {
    @Operation(prompt = "Answer", model = "logical-model", providerRetries = 1)
    fun stream(input: String): Flow<StreamChunk>
}

@AiService
internal interface StreamingServiceZeroRetries {
    @Operation(prompt = "Answer", model = "logical-model", providerRetries = 0)
    fun stream(input: String): Flow<StreamChunk>
}

@AiService
internal interface StreamingServiceTwoRetries {
    @Operation(prompt = "Answer", model = "logical-model", providerRetries = 2)
    fun stream(input: String): Flow<StreamChunk>
}

@AiService
internal interface ExplicitProviderStreamingService {
    @Operation(prompt = "Answer", model = "logical-model", provider = "p0", providerRetries = 1)
    fun stream(input: String): Flow<StreamChunk>
}

internal class OrderedSink {
    val events = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun record(name: String) {
        events += name
    }

    fun count(name: String): Int = events.count { it == name }
}

internal class RetryFallbackObservation(
    private val sink: OrderedSink,
) : OperationObservation {
    override fun onProviderResponse(response: ModelResponse) {
        sink.record("observation.provider-response")
    }

    override fun onProviderFailure(error: Throwable) {
        sink.record("observation.provider-failure")
    }

    override fun onStructuredParseFailure(
        rawResponse: String,
        errorSummary: String,
    ) = Unit

    override fun onEngineEvent(
        name: String,
        attributes: Map<String, Any?>,
    ) {
        sink.record("observation.engine-event:$name")
    }

    override fun onCallCompleted(parseSuccess: Boolean?) {
        sink.record("observation.complete:$parseSuccess")
    }

    override fun onCallCancelled() {
        sink.record("observation.cancelled")
    }
}

/** Records the ordered REAL (providerId, globalAttempt) trace from production. */
internal class AttemptRecordingObserver(
    private val sink: OrderedSink,
) : OperationObserver {
    val attempts = mutableListOf<Pair<String, Int>>()

    override fun onCallStarted(context: OperationCallContext): OperationObservation {
        attempts += context.providerId to context.attempt
        sink.record("observer.start:${context.providerId}:${context.attempt}")
        return RetryFallbackObservation(sink)
    }
}

/**
 * Records the first SEMANTIC breaker disposition per permit. Distinct from
 * raw method invocations: the structural finally onAbandoned() fires after
 * an already-recorded disposition and is deduplicated by permit key.
 */
internal class RecordingCircuitBreaker(
    settings: CircuitBreakerSettings,
) : ProviderCircuitBreaker(settings) {
    val dispositions = mutableListOf<BreakerDisposition>()
    private val recorded = mutableSetOf<String>()

    private fun key(permit: CircuitBreakerPermit) = "${permit.providerId}#${permit.generation}"

    override fun onSuccess(permit: CircuitBreakerPermit) {
        if (recorded.add(key(permit))) dispositions += BreakerDisposition.SUCCESS
        super.onSuccess(permit)
    }

    override fun onFailure(
        permit: CircuitBreakerPermit,
        error: Throwable,
    ): Boolean {
        val qualifying = error is TimeoutException || (error is ProviderException && error.retryable)
        if (recorded.add(key(permit))) {
            dispositions +=
                if (qualifying) BreakerDisposition.QUALIFYING_FAILURE else BreakerDisposition.NEUTRAL
        }
        return super.onFailure(permit, error)
    }

    override fun onAbandoned(permit: CircuitBreakerPermit) {
        if (recorded.add(key(permit))) dispositions += BreakerDisposition.NEUTRAL
        super.onAbandoned(permit)
    }

    fun resetRecording() {
        dispositions.clear()
        recorded.clear()
    }
}

/** Scripted streaming provider: pops one prebuilt response per stream() call. */
internal class ScriptedProvider(
    private val name: String,
    private val sink: OrderedSink,
    private val responses: List<Response>,
) : ModelProvider,
    StreamCapable {
    sealed interface Response {
        data class Chunks(
            val chunks: List<StreamChunk>,
        ) : Response

        data object Cancel : Response
    }

    val streamRequests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse = error("complete is not used")

    override fun stream(request: ModelRequest): Flow<StreamChunk> {
        streamRequests += request
        val index = streamRequests.size - 1
        val response = responses.getOrElse(index) { Response.Chunks(emptyList()) }
        return flow {
            when (response) {
                is Response.Chunks -> {
                    response.chunks.forEach {
                        if (it is StreamChunk.Token) sink.record("stream.token")
                        emit(it)
                    }
                }

                Response.Cancel -> {
                    throw CancellationException("scripted cancellation")
                }
            }
        }
    }

    override fun providerId(): String = name

    override fun supportsCapability(capability: ProviderCapability): Boolean =
        capability ==
            ProviderCapability
                .STREAMING
}

internal fun operation(retries: Int) =
    ServiceDefinitionCompiler(
        OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory()),
    ).compile(
        when (retries) {
            0 -> StreamingServiceZeroRetries::class
            1 -> StreamingServiceRetries::class
            else -> StreamingServiceTwoRetries::class
        },
    ).operations.entries
        .single()
        .value.definition

internal fun explicitOperation() =
    ServiceDefinitionCompiler(
        OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory()),
    ).compile(ExplicitProviderStreamingService::class).operations.entries.single().value.definition

/**
 * The governed facts these properties now run under. 0.7.3 admits no provider invocation without a
 * candidate selected from a viable envelope derived for the same request, so the configured
 * topology, the admitted run and a classified input are request-side facts. The property
 * assertions themselves are unchanged: this is the execution contract these properties were
 * always describing, now enforced.
 */
internal val workloadIdentity =
    WorkloadDeploymentIdentity(
        WorkloadId("workload"),
        WorkloadConfigurationIdentity(ConfigurationId("config"), ConfigurationVersion("1")),
        EnvironmentId("env"),
        DeploymentId("deployment"),
    )

internal val governedRun = GovernedRunIdentity(workloadIdentity, RunId("run"))

internal val classifiedInput =
    ClassifiedDocument(
        "input",
        DataClassification.INTERNAL,
        ClassificationSource.DECLARED,
    )

internal fun governanceFor(routingPlan: ProviderRoutingPlan) =
    ProviderGovernanceConfiguration(
        workloadZones = mapOf(workloadIdentity to ProviderTrustZone.LOCAL),
        rules =
            mapOf(
                DataClassification.INTERNAL to
                    ClassificationRoutingRule(
                        allowedZones = setOf(ProviderTrustZone.LOCAL),
                        allowedFallbackZones = emptySet(),
                    ),
            ),
        trustZonePolicy = TrustZonePolicy(setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.LOCAL)),
        deploymentOf = { providerId ->
            if (routingPlan.providers.keys.none { it.value == providerId }) {
                null
            } else {
                ProviderDeployment(
                    "dep-$providerId",
                    providerId,
                    NamedTrustZone(TrustZoneName("zone-$providerId"), ProviderTrustZone.LOCAL),
                )
            }
        },
    )

internal fun plan(
    routeCount: Int,
    providers: Map<String, ModelProvider>,
): ProviderRoutingPlan {
    val names = (0 until routeCount).map { "p$it" }
    val builder = ProviderRoutingPlan.builder()
    names.forEach { builder.provider(it, providers.getValue(it)) }
    builder.model("logical-model", names.first())
    names.drop(1).forEach { builder.fallbackProvider("logical-model", it) }
    return builder.build()
}

internal fun coordinator(
    routingPlan: ProviderRoutingPlan,
    breaker: ProviderCircuitBreaker,
    sink: OrderedSink,
    observer: AttemptRecordingObserver,
    denyFallback: Boolean = false,
): StreamingExecutionCoordinator {
    val policy = PolicyEngine { PolicyDecision.Allow }
    return StreamingExecutionCoordinator(
        identitySource = DefaultEngineIdentitySource,
        routingPlan = routingPlan,
        circuitBreaker = breaker,
        lifecycleScope = CoroutineScope(Dispatchers.Default),
        isClosed = AtomicBoolean(false),
        serviceTypeName = "test.Service",
        qualifiedServiceName = "test.Service",
        operationObserver = observer,
        operationInterceptor = object : dev.tramai.core.observation.OperationInterceptor {},
        toolExposureCoordinator =
            ToolExposureCoordinator(
                ToolRegistry(),
                PolicyEnforcementHelper(policy, AtomicBoolean(false)),
            ),
        conversationMemoryCoordinator = ConversationMemoryCoordinator(noOpChatMemory, ConversationIdProvider { "cid" }),
        tokenBudgetCoordinator = TokenBudgetCoordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 20)),
        modelRegistryEnforcer = ModelRegistryEnforcer(nullModelRegistry, ModelRegistrySettings(enabled = false)),
        retryPolicy = ProviderRetryPolicy(ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.0)) { 0.0 }),
        beforeResolution = ProviderResolutionGate { _, _, _ -> sink.record("policy.before-resolution") },
        beforeInvocation = ProviderInvocationGate { _, _, _, _ -> sink.record("policy.before-invocation") },
        fallbackGate =
            ProviderFallbackGate { _, previousProviderId, _, nextProviderId, _, _ ->
                sink.record("policy.fallback")
                sink.record("fallback-edge:$previousProviderId->$nextProviderId")
                if (denyFallback) throw PolicyViolationException(PolicyDecision.Deny("fallback denied", "TEST"))
            },
        StreamingBeforeResponseReturnGate { _, _, _ -> Unit },
        governance = governanceFor(routingPlan),
    )
}

internal data class AttemptStep(
    val routeIndex: Int,
    val globalAttempt: Int,
    val outcome: AttemptOutcome,
)

internal data class DispositionTrace(
    val routeIndex: Int,
    val retryIndex: Int,
    val visibilityBefore: OutputVisibility,
    val disposition: RouteDisposition,
)

internal data class ModelTrace(
    val dispositions: List<DispositionTrace>,
    val retryTransitions: Int,
    val fallbackTransitions: Int,
    val totalAttempts: Int,
    val terminalOutcome: TerminalOutcome?,
    val breakerQualifyingFailures: Int,
    val breakerSuccesses: Int,
    val breakerDispositions: List<BreakerDisposition>,
    val visibility: OutputVisibility,
    val attemptTrace: List<AttemptStep>,
    val fallbackEdges: List<Pair<Int, Int>>,
)

/**
 * The model walk for one script: the routing decisions, the attempt trace and the fallback
 * edges the property's invariants are asserted over. Each action's handling is one step here
 * rather than another branch in a long method. The walk still stops as soon as the model
 * reaches a terminal state, which is what the branches' own breaks did.
 */
internal class ModelRun(
    private val script: RetryFallbackScript,
) {
    var model =
        ProviderRetryFallbackModel(
            routeCount = script.routeCount,
            providerRetries = script.providerRetries,
            fallbackGateDenies = script.fallbackDenied,
        )
    val dispositions = mutableListOf<DispositionTrace>()
    val attemptTrace = mutableListOf<AttemptStep>()
    val fallbackEdges = mutableListOf<Pair<Int, Int>>()

    // A transition onto a route whose circuit is open is not a transition that can happen: the
    // routed target has to be usable for the edge to exist. Without this the projection encodes
    // the pre-0.7.3h assumption that fallback lands on the next CONFIGURED route, which is the
    // behavior the authority boundary deliberately removed (no continuation target, no
    // transition — the gate is not consulted and no edge is recorded).
    val unusableRoutes =
        script.actions
            .filterIsInstance<RetryFallbackScriptAction.Admit>()
            .filter { it.circuitOpen }
            .map { it.routeIndex }
            .toSet()

    /** Transitions the projection must not count: the routed target cannot be reached at all. */
    private var unreachableTransitions = 0

    fun walk(): ModelTrace {
        for (action in script.actions) {
            when (action) {
                is RetryFallbackScriptAction.EmitToken -> model = model.emitToken()
                is RetryFallbackScriptAction.Admit -> admit(action)
                is RetryFallbackScriptAction.Attempt -> attempt(action)
            }
            if (model.isTerminal) break
        }
        return trace()
    }

    private fun admit(action: RetryFallbackScriptAction.Admit) {
        require(action.routeIndex == model.routeIndex) {
            "script admits route ${action.routeIndex} but the model's decisions led to " +
                "route ${model.routeIndex} — script inconsistent or model routing bug"
        }
        if (action.circuitOpen) {
            val result =
                model.apply(
                    RouteAdmission.CircuitOpen(action.routeIndex),
                    AttemptOutcome.RetryableFailure,
                )
            dispositions +=
                DispositionTrace(
                    model.routeIndex,
                    model.retryIndex,
                    model.visibility,
                    result.disposition,
                )
            when {
                result.disposition is RouteDisposition.Fallback -> {
                    if (result.next.routeIndex in
                        unusableRoutes
                    ) {
                        unreachableTransitions++
                    } else {
                        fallbackEdges +=
                            model.routeIndex to result.next.routeIndex
                    }
                }

                result.next.terminalOutcome is TerminalOutcome.FallbackDenied -> {
                    if (model.routeIndex + 1 in
                        unusableRoutes
                    ) {
                        unreachableTransitions++
                    } else {
                        fallbackEdges +=
                            model.routeIndex to (model.routeIndex + 1)
                    }
                }
            }
            model = result.next
        }
    }

    private fun attempt(action: RetryFallbackScriptAction.Attempt) {
        require(action.routeIndex == model.routeIndex) {
            "script attempt on route ${action.routeIndex} but the model's decisions led to " +
                "route ${model.routeIndex} — script inconsistent or model routing bug"
        }
        attemptTrace += AttemptStep(model.routeIndex, model.globalAttempt, action.outcome)
        val result = model.apply(RouteAdmission.Allowed, action.outcome)
        dispositions +=
            DispositionTrace(
                model.routeIndex,
                model.retryIndex,
                model.visibility,
                result.disposition,
            )
        when {
            result.disposition is RouteDisposition.Fallback -> {
                if (result.next.routeIndex in
                    unusableRoutes
                ) {
                    unreachableTransitions++
                } else {
                    fallbackEdges += model.routeIndex to result.next.routeIndex
                }
            }

            // A DENIED fallback still invoked the gate with the
            // (route -> route+1) edge before the denial threw (P0-G).
            result.next.terminalOutcome is TerminalOutcome.FallbackDenied -> {
                if (model.routeIndex + 1 in
                    unusableRoutes
                ) {
                    unreachableTransitions++
                } else {
                    fallbackEdges += model.routeIndex to (model.routeIndex + 1)
                }
            }
        }
        model = result.next
    }

    private fun trace(): ModelTrace =
        ModelTrace(
            dispositions = dispositions,
            retryTransitions = model.retryTransitions,
            fallbackTransitions = model.fallbackTransitions - unreachableTransitions,
            totalAttempts = attemptTrace.size,
            terminalOutcome = model.terminalOutcome,
            breakerQualifyingFailures = model.breakerQualifyingFailures,
            breakerSuccesses = model.breakerSuccesses,
            breakerDispositions = model.breakerDispositions,
            visibility = model.visibility,
            attemptTrace = attemptTrace,
            fallbackEdges = fallbackEdges,
        )
}

internal fun scriptToChunks(outcome: AttemptOutcome): ScriptedProvider.Response =
    when (outcome) {
        AttemptOutcome.Success -> {
            ScriptedProvider.Response.Chunks(
                listOf(StreamChunk.Token("ok"), StreamChunk.Complete("ok", UsageMetrics(outputTokens = 1))),
            )
        }

        AttemptOutcome.RetryableFailure -> {
            ScriptedProvider.Response.Chunks(
                listOf(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))),
            )
        }

        AttemptOutcome.RetryableFailureWithRetryAfter -> {
            ScriptedProvider.Response.Chunks(
                listOf(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))),
            )
        }

        AttemptOutcome.Timeout -> {
            ScriptedProvider.Response.Chunks(listOf(StreamChunk.Error(TimeoutException("timeout"))))
        }

        AttemptOutcome.PermanentProviderFailure -> {
            ScriptedProvider.Response.Chunks(
                listOf(StreamChunk.Error(ProviderException("permanent", retryable = false))),
            )
        }

        AttemptOutcome.CapabilityFailure -> {
            ScriptedProvider.Response.Chunks(
                listOf(StreamChunk.Error(ProviderException("capability", retryable = false))),
            )
        }

        AttemptOutcome.ModelRegistryRejection -> {
            ScriptedProvider.Response.Chunks(
                listOf(StreamChunk.Error(ProviderException("model-registry", retryable = false))),
            )
        }

        AttemptOutcome.DlpRejection -> {
            ScriptedProvider.Response.Chunks(
                listOf(StreamChunk.Error(ProviderException("dlp", retryable = false))),
            )
        }

        AttemptOutcome.PolicyRejection -> {
            ScriptedProvider.Response.Chunks(
                listOf(StreamChunk.Error(PolicyViolationException(PolicyDecision.Deny("denied", "TEST")))),
            )
        }

        AttemptOutcome.OtherTerminalFailure -> {
            ScriptedProvider.Response.Chunks(
                listOf(StreamChunk.Error(ProviderException("other", retryable = false))),
            )
        }

        AttemptOutcome.Cancellation -> {
            ScriptedProvider.Response.Cancel
        }
    }

/** Builds per-provider response queues from the SCRIPT alone — never from the model. */
internal fun buildResponses(script: RetryFallbackScript): Map<String, List<ScriptedProvider.Response>> {
    val responses = mutableMapOf<String, MutableList<ScriptedProvider.Response>>()
    var pendingToken = false
    for (action in script.actions) {
        when (action) {
            is RetryFallbackScriptAction.EmitToken -> {
                pendingToken = true
            }

            is RetryFallbackScriptAction.Admit -> {
                Unit
            }

            is RetryFallbackScriptAction.Attempt -> {
                val provider = "p${action.routeIndex}"
                val base = scriptToChunks(action.outcome)
                val response =
                    if (pendingToken && base is ScriptedProvider.Response.Chunks) {
                        ScriptedProvider.Response.Chunks(listOf(StreamChunk.Token("visible")) + base.chunks)
                    } else {
                        base
                    }
                responses.getOrPut(provider) { mutableListOf() } += response
                pendingToken = false
            }
        }
    }
    return responses
}

internal data class RealityTrace(
    val observedAttempts: List<Pair<String, Int>>,
    val fallbackEdges: List<Pair<Int, Int>>,
    val retryEvents: Int,
    val fallbackEvents: Int,
    val circuitOpenedEvents: Int,
    val breakerDispositions: List<BreakerDisposition>,
    val terminalComplete: Boolean,
    val terminalErrorClass: String?,
    val cancelled: Boolean,
    val fallbackDenied: Boolean,
    val firstTokenIndex: Int?,
    val perRouteAttempts: Map<String, Int>,
    val retryEventsAfterToken: Int,
    val fallbackEventsAfterToken: Int,
    val circuitOpenedEventsAfterToken: Int,
)

internal fun assertModelInvariants(
    trace: ModelTrace,
    script: RetryFallbackScript,
    label: String,
) {
    assertAttemptInvariants(trace, script, label)
    assertDispositionInvariants(trace, label)
    assertBreakerInvariants(trace, script, label)
}

internal fun assertAttemptInvariants(
    trace: ModelTrace,
    script: RetryFallbackScript,
    label: String,
) {
    // P4 (per-route, the real contract): every admitted route consumes at
    // most providerRetries + 1 attempts; retries(route) <= providerRetries.
    trace.attemptTrace.map { it.routeIndex }.distinct().forEach { route ->
        val attempts = trace.attemptTrace.count { it.routeIndex == route }
        assertThat(attempts)
            .withFailMessage(
                "$label P4 attempts(route $route)=$attempts > " +
                    "providerRetries+1=${script.providerRetries + 1}",
            ).isLessThanOrEqualTo(script.providerRetries + 1)
    }
    // P11: global attempt counter strictly increases across retries and fallbacks.
    val attempts = trace.attemptTrace.map { it.globalAttempt }
    assertThat(attempts).withFailMessage("$label P11 strictly increasing").isSorted()
    assertThat(attempts.zipWithNext().all { (a, b) -> b > a })
        .withFailMessage("$label P11 strictly increasing")
        .isTrue()
}

internal fun assertDispositionInvariants(
    trace: ModelTrace,
    label: String,
) {
    // P12: OUTPUT_VISIBLE is irreversible at disposition time — a VISIBLE
    // attempt never yields retry or fallback.
    trace.dispositions.forEach { d ->
        if (d.visibilityBefore == OutputVisibility.VISIBLE) {
            assertThat(
                d.disposition is RouteDisposition.RetrySameRoute ||
                    d.disposition is RouteDisposition.Fallback,
            ).withFailMessage("$label P12 visible disposition $d must not retry/fallback")
                .isFalse()
        }
    }
    // P6: success terminates.
    if (trace.dispositions.any { it.disposition is RouteDisposition.Succeeded }) {
        assertThat(trace.terminalOutcome)
            .withFailMessage("$label P6 success terminal")
            .isEqualTo(TerminalOutcome.Success)
    }
    // P8: cancellation bypasses classification.
    if (trace.dispositions.any { it.disposition == RouteDisposition.Cancelled }) {
        assertThat(trace.terminalOutcome)
            .withFailMessage("$label P8 cancelled")
            .isEqualTo(TerminalOutcome.Cancelled)
    }
}

internal fun assertBreakerInvariants(
    trace: ModelTrace,
    script: RetryFallbackScript,
    label: String,
) {
    // P14: every admitted route produces at most ONE semantic breaker
    // disposition; circuit-open routes produce none. The count equals the
    // number of DISTINCT routes that ran at least one attempt.
    val admittedRoutesWithAttempts =
        trace.attemptTrace
            .map { it.routeIndex }
            .distinct()
            .size
    assertThat(trace.breakerDispositions.size)
        .withFailMessage("$label P14 one disposition per admitted route")
        .isEqualTo(admittedRoutesWithAttempts)
    assertThat(trace.breakerDispositions.size)
        .withFailMessage("$label P14 never exceeds routeCount")
        .isLessThanOrEqualTo(script.routeCount)
    assertThat(trace.breakerQualifyingFailures)
        .withFailMessage("$label P14 qualifying==dispositions")
        .isEqualTo(
            trace.breakerDispositions.count {
                it ==
                    BreakerDisposition.QUALIFYING_FAILURE
            },
        )
    assertThat(trace.breakerSuccesses)
        .withFailMessage("$label P14 success==dispositions")
        .isEqualTo(
            trace.breakerDispositions.count {
                it ==
                    BreakerDisposition.SUCCESS
            },
        )
}

internal fun recordRetryLanes(
    model: ModelTrace,
    script: RetryFallbackScript,
    lanes: MutableSet<String>,
) {
    if (model.dispositions.any { it.disposition is RouteDisposition.RetrySameRoute }) lanes += "same-route-retry"
    if (model.dispositions.zipWithNext().any { (a, b) ->
            a.disposition is RouteDisposition.RetrySameRoute &&
                b.disposition is RouteDisposition.Succeeded
        }
    ) {
        lanes += "retry-success"
    }
    if (model.retryTransitions >= 1 &&
        model.breakerDispositions.lastOrNull() == BreakerDisposition.QUALIFYING_FAILURE
    ) {
        lanes += "retry-exhaustion"
    }
    val retryAfterAttempts =
        script.actions.any {
            it is RetryFallbackScriptAction.Attempt &&
                it.outcome == AttemptOutcome.RetryableFailureWithRetryAfter
        }
    if (retryAfterAttempts && model.retryTransitions >= 1) {
        lanes += "retry-after-retry"
    }
}

internal fun recordFallbackLanes(
    model: ModelTrace,
    script: RetryFallbackScript,
    lanes: MutableSet<String>,
) {
    if (model.dispositions.any { it.disposition is RouteDisposition.Fallback } &&
        model.breakerDispositions.contains(BreakerDisposition.QUALIFYING_FAILURE)
    ) {
        lanes += "fallback-after-exhaustion"
    }
    if (model.dispositions.count { it.disposition is RouteDisposition.Fallback } >= 2) {
        lanes += "multi-fallback-traversal"
    }
    if (script.actions.any { it is RetryFallbackScriptAction.Admit && it.circuitOpen } &&
        model.dispositions.any { it.disposition is RouteDisposition.Fallback }
    ) {
        lanes += "circuit-open-fallback"
    }
}

internal fun recordTerminalLanes(
    model: ModelTrace,
    script: RetryFallbackScript,
    lanes: MutableSet<String>,
) {
    if (model.terminalOutcome == TerminalOutcome.Failure(FailureKind.CIRCUIT_OPEN_ONLY)) lanes += "all-routes-open"
    if (model.terminalOutcome is TerminalOutcome.FallbackDenied) lanes += "fallback-denial"
    if (script.explicitProvider) lanes += "explicit-provider"
    if (model.visibility == OutputVisibility.VISIBLE &&
        model.terminalOutcome is TerminalOutcome.Failure
    ) {
        lanes += "output-visible-terminal"
    }
    if (model.terminalOutcome == TerminalOutcome.Cancelled) lanes += "cancellation"
    if (model.breakerDispositions.contains(BreakerDisposition.NEUTRAL) &&
        model.retryTransitions >= 1
    ) {
        lanes += "neutral-terminal-after-retry"
    }
}

/** A chat memory that stores nothing: memory writes are asserted through RecordingMemory. */
private val noOpChatMemory =
    object : ChatMemory {
        override fun get(conversationId: String): List<Message> = emptyList()

        override fun add(
            conversationId: String,
            messages: List<Message>,
        ) = Unit

        override fun add(
            conversationId: String,
            message: Message,
        ) = Unit

        override fun clear(conversationId: String) = Unit
    }

/** A registry that approves nothing: model approval is exercised by the registry's own tests. */
private val nullModelRegistry =
    object : ModelRegistry {
        override suspend fun findApprovedModel(
            providerId: String,
            modelName: String,
        ) = null
    }
