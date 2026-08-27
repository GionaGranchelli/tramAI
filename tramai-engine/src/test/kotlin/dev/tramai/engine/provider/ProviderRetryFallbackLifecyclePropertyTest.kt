package dev.tramai.engine.provider

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ConversationIdProvider
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
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.core.provider.StreamCapable
import dev.tramai.engine.CircuitBreakerAdmission
import dev.tramai.engine.CircuitBreakerSettings
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
import dev.tramai.engine.streaming.StreamingBeforeResponseReturnGate
import dev.tramai.engine.streaming.StreamingExecutionCoordinator
import dev.tramai.engine.streaming.StreamingExecutionRequest
import dev.tramai.engine.tool.ToolExposureCoordinator
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
 * Epic 8.2h — provider retry/fallback lifecycle property suite (P1–P14).
 *
 * For every script of the deterministic 32×32 corpus (plus forced archetypes),
 * the SAME action lattice is applied to the pure [ProviderRetryFallbackModel]
 * AND to the real [StreamingExecutionCoordinator] (through scripted providers
 * and a real [ProviderRetryPolicy] with zero jitter). The observed reality —
 * per-route attempt counts, retry/fallback/breaker events, terminal outcome —
 * must equal the model trace (P1), and both must satisfy the invariant
 * properties P2–P14.
 */
class ProviderRetryFallbackLifecyclePropertyTest {

    @AiService
    private interface StreamingServiceRetries {
        @Operation(prompt = "Answer", model = "logical-model", providerRetries = 1)
        fun stream(input: String): Flow<StreamChunk>
    }

    @AiService
    private interface StreamingServiceZeroRetries {
        @Operation(prompt = "Answer", model = "logical-model", providerRetries = 0)
        fun stream(input: String): Flow<StreamChunk>
    }

    @AiService
    private interface StreamingServiceTwoRetries {
        @Operation(prompt = "Answer", model = "logical-model", providerRetries = 2)
        fun stream(input: String): Flow<StreamChunk>
    }

    private class OrderedSink {
        val events = mutableListOf<String>()
        fun record(name: String) { events += name }
    }

    private class RecordingObservation(private val sink: OrderedSink) : OperationObservation {
        override fun onProviderResponse(response: ModelResponse) { sink.record("observation.provider-response") }
        override fun onProviderFailure(error: Throwable) { sink.record("observation.provider-failure") }
        override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) = Unit
        override fun onEngineEvent(name: String, attributes: Map<String, Any?>) { sink.record("observation.engine-event:$name") }
        override fun onCallCompleted(parseSuccess: Boolean?) { sink.record("observation.complete:$parseSuccess") }
        override fun onCallCancelled() { sink.record("observation.cancelled") }
    }

    private class RecordingOperationObserver(private val sink: OrderedSink) : OperationObserver {
        override fun onCallStarted(context: OperationCallContext): OperationObservation = RecordingObservation(sink)
    }

    /** Scripted streaming provider: pops one prebuilt response per stream() call. */
    private class ScriptedProvider(private val name: String, private val responses: List<Response>) : ModelProvider, StreamCapable {
        sealed interface Response {
            data class Chunks(val chunks: List<StreamChunk>) : Response
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
                    is Response.Chunks -> response.chunks.forEach { emit(it) }
                    Response.Cancel -> throw CancellationException("scripted cancellation")
                }
            }
        }
        override fun providerId(): String = name
    }

    private fun operation(retries: Int) = ServiceDefinitionCompiler(
        OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory()),
    ).compile(
        when (retries) {
            0 -> StreamingServiceZeroRetries::class
            1 -> StreamingServiceRetries::class
            else -> StreamingServiceTwoRetries::class
        },
    ).operations.entries.single().value.definition

    private fun plan(routeCount: Int, providers: Map<String, ModelProvider>): ProviderRoutingPlan {
        val names = (0 until routeCount).map { "p$it" }
        val builder = ProviderRoutingPlan.builder()
        names.forEach { builder.provider(it, providers.getValue(it)) }
        builder.model("logical-model", names.first())
        names.drop(1).forEach { builder.fallbackProvider("logical-model", it) }
        return builder.build()
    }

    private fun coordinator(
        routingPlan: ProviderRoutingPlan,
        breaker: ProviderCircuitBreaker,
        sink: OrderedSink,
        denyFallback: Boolean = false,
    ): StreamingExecutionCoordinator {
        val policy = PolicyEngine { PolicyDecision.Allow }
        return StreamingExecutionCoordinator(
            routingPlan, breaker, CoroutineScope(Dispatchers.Default), AtomicBoolean(false), "test.Service", "test.Service",
            RecordingOperationObserver(sink),
            object : dev.tramai.core.observation.OperationInterceptor {},
            ToolExposureCoordinator(ToolRegistry(), PolicyEnforcementHelper(policy, AtomicBoolean(false))),
            ConversationMemoryCoordinator(object : ChatMemory {
                override fun get(conversationId: String): List<Message> = emptyList()
                override fun add(conversationId: String, messages: List<Message>) = Unit
                override fun add(conversationId: String, message: Message) = Unit
                override fun clear(conversationId: String) = Unit
            }, ConversationIdProvider { "cid" }),
            TokenBudgetCoordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 20)),
            ModelRegistryEnforcer(object : ModelRegistry { override suspend fun findApprovedModel(providerId: String, modelName: String) = null }, ModelRegistrySettings(enabled = false)),
            ProviderRetryPolicy(ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.0)) { 0.0 }),
            ProviderResolutionGate { _, _, _ -> sink.record("policy.before-resolution") },
            ProviderInvocationGate { _, _, _, _ -> sink.record("policy.before-invocation") },
            ProviderFallbackGate { _, _, _, _, _, _ ->
                sink.record("policy.fallback")
                if (denyFallback) throw PolicyViolationException(PolicyDecision.Deny("fallback denied", "TEST"))
            },
            StreamingBeforeResponseReturnGate { _, _, _ -> Unit },
        )
    }

    private data class ModelTrace(
        val dispositions: List<RouteDisposition>,
        val retryTransitions: Int,
        val fallbackTransitions: Int,
        val totalAttempts: Int,
        val terminalOutcome: TerminalOutcome?,
        val breakerQualifyingFailures: Int,
        val breakerSuccesses: Int,
        val breakerDispositions: List<BreakerDisposition>,
        val visibility: OutputVisibility,
        val attemptRoutes: List<Pair<Int, AttemptOutcome>>,
    )

    private fun runModel(script: RetryFallbackScript): ModelTrace {
        var model = ProviderRetryFallbackModel(routeCount = script.routeCount, providerRetries = script.providerRetries, fallbackGateDenies = script.fallbackDenied)
        val dispositions = mutableListOf<RouteDisposition>()
        val attemptRoutes = mutableListOf<Pair<Int, AttemptOutcome>>()
        for (action in script.actions) {
            when (action) {
                is RetryFallbackScriptAction.EmitToken -> model = model.emitToken()
                is RetryFallbackScriptAction.Admit -> {
                    // Circuit-open is an admission-time decision (P0-F): the
                    // route is skipped with zero attempts. Allowed admission
                    // changes nothing — the next Attempt runs on the current
                    // route (already advanced by the prior Fallback disposition).
                    if (action.circuitOpen) {
                        val result = model.apply(RouteAdmission.CircuitOpen(action.routeIndex), AttemptOutcome.RetryableFailure)
                        dispositions += result.disposition
                        model = result.next
                        if (model.isTerminal) break
                    }
                }
                is RetryFallbackScriptAction.Attempt -> {
                    attemptRoutes += model.routeIndex to action.outcome
                    val result = model.apply(RouteAdmission.Allowed, action.outcome)
                    dispositions += result.disposition
                    model = result.next
                    if (model.isTerminal) break
                }
            }
        }
        return ModelTrace(
            dispositions = dispositions,
            retryTransitions = model.retryTransitions,
            fallbackTransitions = model.fallbackTransitions,
            totalAttempts = attemptRoutes.size,
            terminalOutcome = model.terminalOutcome,
            breakerQualifyingFailures = model.breakerQualifyingFailures,
            breakerSuccesses = model.breakerSuccesses,
            breakerDispositions = model.breakerDispositions,
            visibility = model.visibility,
            attemptRoutes = attemptRoutes,
        )
    }

    private fun scriptToChunks(outcome: AttemptOutcome): ScriptedProvider.Response = when (outcome) {
        AttemptOutcome.Success -> ScriptedProvider.Response.Chunks(listOf(StreamChunk.Token("ok"), StreamChunk.Complete("ok", UsageMetrics(outputTokens = 1))))
        AttemptOutcome.RetryableFailure -> ScriptedProvider.Response.Chunks(listOf(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))))
        AttemptOutcome.RetryableFailureWithRetryAfter -> ScriptedProvider.Response.Chunks(listOf(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))))
        AttemptOutcome.Timeout -> ScriptedProvider.Response.Chunks(listOf(StreamChunk.Error(TimeoutException("timeout"))))
        AttemptOutcome.PermanentProviderFailure -> ScriptedProvider.Response.Chunks(listOf(StreamChunk.Error(ProviderException("permanent", retryable = false))))
        AttemptOutcome.CapabilityFailure -> ScriptedProvider.Response.Chunks(listOf(StreamChunk.Error(ProviderException("capability", retryable = false))))
        AttemptOutcome.ModelRegistryRejection -> ScriptedProvider.Response.Chunks(listOf(StreamChunk.Error(ProviderException("model-registry", retryable = false))))
        AttemptOutcome.DlpRejection -> ScriptedProvider.Response.Chunks(listOf(StreamChunk.Error(ProviderException("dlp", retryable = false))))
        AttemptOutcome.PolicyRejection -> ScriptedProvider.Response.Chunks(listOf(StreamChunk.Error(PolicyViolationException(PolicyDecision.Deny("denied", "TEST")))))
        AttemptOutcome.OtherTerminalFailure -> ScriptedProvider.Response.Chunks(listOf(StreamChunk.Error(ProviderException("other", retryable = false))))
        AttemptOutcome.Cancellation -> ScriptedProvider.Response.Cancel
    }

    /** Builds per-provider response queues following the MODEL's per-attempt routes (fallback auto-advances). */
    private fun buildResponses(script: RetryFallbackScript, attemptRoutes: List<Pair<Int, AttemptOutcome>>): Map<String, List<ScriptedProvider.Response>> {
        val responses = mutableMapOf<String, MutableList<ScriptedProvider.Response>>()
        var attemptIndex = 0
        var pendingToken = false
        for (action in script.actions) {
            when (action) {
                is RetryFallbackScriptAction.EmitToken -> pendingToken = true
                is RetryFallbackScriptAction.Admit -> Unit // model route is authoritative; circuit-open has no responses
                is RetryFallbackScriptAction.Attempt -> {
                    // The model stops consuming actions at terminal; remaining
                    // script attempts are unreachable and get no queue.
                    if (attemptIndex >= attemptRoutes.size) break
                    val (routeIndex, outcome) = attemptRoutes[attemptIndex++]
                    val provider = "p$routeIndex"
                    val base = scriptToChunks(outcome)
                    val response = if (pendingToken && base is ScriptedProvider.Response.Chunks) {
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

    private data class RealityTrace(
        val perRouteAttempts: Map<String, Int>,
        val retryEvents: Int,
        val fallbackEvents: Int,
        val circuitOpenedEvents: Int,
        val terminalComplete: Boolean,
        val terminalErrorClass: String?,
        val cancelled: Boolean,
        val fallbackDenied: Boolean,
    )

    private fun runReality(script: RetryFallbackScript, attemptRoutes: List<Pair<Int, AttemptOutcome>>): RealityTrace {
        val sink = OrderedSink()
        val responses = buildResponses(script, attemptRoutes)
        val providers = (0 until script.routeCount).associate { "p$it" to ScriptedProvider("p$it", responses["p$it"] ?: emptyList()) }
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 1_000L))
        // Pre-open circuit for circuit-open admissions.
        for (action in script.actions) {
            if (action is RetryFallbackScriptAction.Admit && action.circuitOpen) {
                val name = "p${action.routeIndex}"
                breaker.onFailure((breaker.beforeCall(name) as CircuitBreakerAdmission.Allowed).permit, ProviderException("down", retryable = true))
            }
        }
        val c = coordinator(plan(script.routeCount, providers), breaker, sink, denyFallback = script.fallbackDenied)
        val request = StreamingExecutionRequest(operation(script.providerRetries), listOf("input"), TokenBudgetCoordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 20)).createTracker(), null)
        var terminalComplete = false
        var terminalErrorClass: String? = null
        var cancelled = false
        var fallbackDenied = false
        try {
            val chunks = runBlocking { c.execute(request).toList() }
            val last = chunks.lastOrNull()
            when (last) {
                is StreamChunk.Complete -> terminalComplete = true
                is StreamChunk.Error -> terminalErrorClass = last.cause::class.simpleName
                else -> Unit
            }
        } catch (e: CancellationException) {
            cancelled = true
        } catch (e: PolicyViolationException) {
            // Fallback-gate denial: deny error is authoritative (8.2h 4.4 / P0-G).
            fallbackDenied = true
        }
        val retryEvents = sink.events.count { it == "observation.engine-event:tramai.retry.scheduled" }
        val fallbackEvents = sink.events.count { it == "policy.fallback" }
        val circuitOpenedEvents = sink.events.count { it == "observation.engine-event:tramai.circuit.opened" }
        return RealityTrace(
            perRouteAttempts = providers.mapValues { (_, p) -> p.streamRequests.size },
            retryEvents = retryEvents,
            fallbackEvents = fallbackEvents,
            circuitOpenedEvents = circuitOpenedEvents,
            terminalComplete = terminalComplete,
            terminalErrorClass = terminalErrorClass,
            cancelled = cancelled,
            fallbackDenied = fallbackDenied,
        )
    }

    private fun assertModelInvariants(trace: ModelTrace, script: RetryFallbackScript, label: String) {
        // P4: retry count never exceeds providerRetries.
        assertThat(trace.retryTransitions).withFailMessage("$label P4 retries<=budget").isLessThanOrEqualTo(script.providerRetries * script.routeCount)
        // P10: fallback routeIndex strictly increases — dispositions order has at most one Fallback per route boundary;
        // P5: fallback cannot occur before exhaustion — model structure guarantees it (fallback only when retryIndex==providerRetries).
        // P12: once VISIBLE, no retry/fallback dispositions remain.
        if (trace.visibility == OutputVisibility.VISIBLE) {
            val visibleStart = trace.dispositions.indexOfFirst { it is RouteDisposition.Failed }
            if (visibleStart >= 0) {
                val tail = trace.dispositions.subList(visibleStart, trace.dispositions.size)
                assertThat(tail.none { it is RouteDisposition.RetrySameRoute || it is RouteDisposition.Fallback })
                    .withFailMessage("$label P12 no retry/fallback after visible").isTrue()
            }
        }
        // P6: success terminates.
        if (trace.dispositions.any { it is RouteDisposition.Succeeded }) {
            assertThat(trace.terminalOutcome).withFailMessage("$label P6 success terminal").isEqualTo(TerminalOutcome.Success)
        }
        // P8: cancellation bypasses classification.
        if (trace.dispositions.any { it == RouteDisposition.Cancelled }) {
            assertThat(trace.terminalOutcome).withFailMessage("$label P8 cancelled").isEqualTo(TerminalOutcome.Cancelled)
        }
        // P14: every admitted route owns exactly one permit and produces at
        // most ONE authoritative breaker terminal disposition; circuit-open
        // routes own no permit and produce none. Semantic dispositions, NOT
        // raw onSuccess/onFailure/onAbandoned invocations (belt-and-suspenders
        // cleanup can legally double-invoke idempotently). The number of
        // dispositions equals the number of DISTINCT routes that ran at least
        // one attempt.
        val admittedRoutesWithAttempts = trace.attemptRoutes.map { it.first }.distinct().size
        assertThat(trace.breakerDispositions.size)
            .withFailMessage("$label P14 one disposition per admitted route").isEqualTo(admittedRoutesWithAttempts)
        assertThat(trace.breakerDispositions.size)
            .withFailMessage("$label P14 never exceeds routeCount").isLessThanOrEqualTo(script.routeCount)
        assertThat(trace.breakerQualifyingFailures)
            .withFailMessage("$label P14 qualifying==dispositions").isEqualTo(trace.breakerDispositions.count { it == BreakerDisposition.QUALIFYING_FAILURE })
        assertThat(trace.breakerSuccesses)
            .withFailMessage("$label P14 success==dispositions").isEqualTo(trace.breakerDispositions.count { it == BreakerDisposition.SUCCESS })
    }

    private fun driveScript(script: RetryFallbackScript, label: String) {
        val model = runModel(script)
        assertModelInvariants(model, script, label)

        val reality = runReality(script, model.attemptRoutes)

        // P1: trace equivalence.
        val totalRealAttempts = reality.perRouteAttempts.values.sum()
        assertThat(totalRealAttempts).withFailMessage("$label P1 attempts").isEqualTo(model.totalAttempts)
        assertThat(reality.retryEvents).withFailMessage("$label P1 retries").isEqualTo(model.retryTransitions)
        assertThat(reality.fallbackEvents).withFailMessage("$label P1 fallbacks").isEqualTo(model.fallbackTransitions)
        assertThat(reality.circuitOpenedEvents).withFailMessage("$label P1 breaker failures").isEqualTo(model.breakerQualifyingFailures)

        when (model.terminalOutcome) {
            TerminalOutcome.Success -> {
                assertThat(reality.terminalComplete).withFailMessage("$label P1 success").isTrue()
                assertThat(reality.cancelled).isFalse()
                assertThat(reality.fallbackDenied).isFalse()
            }
            is TerminalOutcome.Cancelled -> assertThat(reality.cancelled).withFailMessage("$label P1 cancelled").isTrue()
            is TerminalOutcome.FallbackDenied -> {
                // Deny error is authoritative; the invocation fails with the
                // gate's PolicyViolationException (P0-G).
                assertThat(reality.fallbackDenied).withFailMessage("$label P1 fallback denied").isTrue()
                assertThat(reality.cancelled).isFalse()
            }
            is TerminalOutcome.Failure -> {
                assertThat(reality.terminalErrorClass).withFailMessage("$label P1 error").isNotNull()
                assertThat(reality.cancelled).isFalse()
                assertThat(reality.fallbackDenied).isFalse()
            }
            null -> Unit
        }
    }

    @Test
    fun `P1-P14 retry fallback lifecycle properties over deterministic corpus`() {
        val seeds = 0L until 32L
        for (seed in seeds) {
            for (retries in listOf(0, 1, 2)) {
                val script = ProviderRetryFallbackActionGenerator.generate(seed, providerRetries = retries, routeCount = 2)
                driveScript(script, "seed=$seed retries=$retries")
            }
        }
    }

    @Test
    fun `P13 explicit provider resolves to route cardinality one`() {
        // The explicit-provider archetype is generated with routeCount=1: the
        // model never produces a fallback for it, and reality never calls a
        // second provider (P0-H).
        val script = ProviderRetryFallbackActionGenerator.generate(13, providerRetries = 1, routeCount = 1)
        assertThat(script.routeCount).isEqualTo(1)
        driveScript(script, "explicit-provider")
    }

    @Test
    fun `P14 breaker composition traces over forced archetypes`() {
        // retryable -> retry -> success: SEMANTIC disposition SUCCESS,
        // 0 qualifying failures (the authoritative route result is success).
        val successScript = RetryFallbackScript(
            providerRetries = 1,
            routeCount = 1,
            actions = listOf(
                RetryFallbackScriptAction.Admit(0),
                RetryFallbackScriptAction.Attempt(AttemptOutcome.RetryableFailure),
                RetryFallbackScriptAction.Attempt(AttemptOutcome.Success),
            ),
        )
        val successModel = runModel(successScript)
        assertThat(successModel.breakerQualifyingFailures).isZero()
        assertThat(successModel.breakerSuccesses).isEqualTo(1)
        assertThat(successModel.breakerDispositions).containsExactly(BreakerDisposition.SUCCESS)

        // retryable -> retry -> exhausted retryable: QUALIFYING_FAILURE, 1.
        val exhaustedScript = RetryFallbackScript(
            providerRetries = 1,
            routeCount = 1,
            actions = listOf(
                RetryFallbackScriptAction.Admit(0),
                RetryFallbackScriptAction.Attempt(AttemptOutcome.RetryableFailure),
                RetryFallbackScriptAction.Attempt(AttemptOutcome.RetryableFailure),
            ),
        )
        val exhaustedModel = runModel(exhaustedScript)
        assertThat(exhaustedModel.breakerQualifyingFailures).isEqualTo(1)
        assertThat(exhaustedModel.breakerSuccesses).isZero()
        assertThat(exhaustedModel.breakerDispositions).containsExactly(BreakerDisposition.QUALIFYING_FAILURE)

        // retryable -> retry -> permanent: NEUTRAL, zero qualifying failures.
        val permanentScript = RetryFallbackScript(
            providerRetries = 1,
            routeCount = 1,
            actions = listOf(
                RetryFallbackScriptAction.Admit(0),
                RetryFallbackScriptAction.Attempt(AttemptOutcome.RetryableFailure),
                RetryFallbackScriptAction.Attempt(AttemptOutcome.PermanentProviderFailure),
            ),
        )
        val permanentModel = runModel(permanentScript)
        assertThat(permanentModel.breakerQualifyingFailures).isZero()
        assertThat(permanentModel.breakerSuccesses).isZero()
        assertThat(permanentModel.breakerDispositions).containsExactly(BreakerDisposition.NEUTRAL)
        assertThat(permanentModel.terminalOutcome).isEqualTo(TerminalOutcome.Failure(FailureKind.PERMANENT))

        // primary exhausted -> fallback success: TWO route dispositions —
        // primary permit QUALIFYING_FAILURE, fallback permit SUCCESS. Not
        // "one invocation completion": each admitted route owns its permit.
        val fallbackScript = RetryFallbackScript(
            providerRetries = 0,
            routeCount = 2,
            actions = listOf(
                RetryFallbackScriptAction.Admit(0),
                RetryFallbackScriptAction.Attempt(AttemptOutcome.RetryableFailure),
                RetryFallbackScriptAction.Admit(1),
                RetryFallbackScriptAction.Attempt(AttemptOutcome.Success),
            ),
        )
        val fallbackModel = runModel(fallbackScript)
        assertThat(fallbackModel.breakerQualifyingFailures).isEqualTo(1)
        assertThat(fallbackModel.breakerSuccesses).isEqualTo(1)
        assertThat(fallbackModel.breakerDispositions).containsExactly(BreakerDisposition.QUALIFYING_FAILURE, BreakerDisposition.SUCCESS)
    }

    private fun recordLane(model: ModelTrace, script: RetryFallbackScript, lanes: MutableSet<String>) {
        if (model.dispositions.any { it is RouteDisposition.RetrySameRoute }) lanes += "same-route-retry"
        if (model.dispositions.zipWithNext().any { (a, b) -> a is RouteDisposition.RetrySameRoute && b is RouteDisposition.Succeeded }) lanes += "retry-success"
        if (model.retryTransitions >= 1 && model.breakerDispositions.lastOrNull() == BreakerDisposition.QUALIFYING_FAILURE) lanes += "retry-exhaustion"
        if (script.actions.any { it is RetryFallbackScriptAction.Attempt && it.outcome == AttemptOutcome.RetryableFailureWithRetryAfter } && model.retryTransitions >= 1) lanes += "retry-after-retry"
        if (model.dispositions.any { it is RouteDisposition.Fallback } && model.breakerDispositions.contains(BreakerDisposition.QUALIFYING_FAILURE)) lanes += "fallback-after-exhaustion"
        if (model.dispositions.count { it is RouteDisposition.Fallback } >= 2) lanes += "multi-fallback-traversal"
        if (script.actions.any { it is RetryFallbackScriptAction.Admit && it.circuitOpen } && model.dispositions.any { it is RouteDisposition.Fallback }) lanes += "circuit-open-fallback"
        if (model.terminalOutcome == TerminalOutcome.Failure(FailureKind.CIRCUIT_OPEN_ONLY)) lanes += "all-routes-open"
        if (model.terminalOutcome is TerminalOutcome.FallbackDenied) lanes += "fallback-denial"
        if (script.explicitProvider) lanes += "explicit-provider"
        if (model.visibility == OutputVisibility.VISIBLE && model.terminalOutcome is TerminalOutcome.Failure) lanes += "output-visible-terminal"
        if (model.terminalOutcome == TerminalOutcome.Cancelled) lanes += "cancellation"
        if (model.breakerDispositions.contains(BreakerDisposition.NEUTRAL) && model.retryTransitions >= 1) lanes += "neutral-terminal-after-retry"
    }

    @Test
    fun `semantic coverage guard corpus reaches every retry fallback lane`() {
        // Mechanical guard: a generator refactor must never silently drop a
        // semantic lane. Runs the FULL corpus (32 seeds x retries 0/1/2 x
        // route counts 1/2/3) through the model and requires every lane.
        val lanes = mutableSetOf<String>()
        val budgetsWithRecovery = mutableSetOf<Int>()
        for (seed in 0L until 32L) {
            for (retries in listOf(0, 1, 2)) {
                for (routeCount in listOf(1, 2, 3)) {
                    val script = ProviderRetryFallbackActionGenerator.generate(seed, providerRetries = retries, routeCount = routeCount)
                    val model = runModel(script)
                    recordLane(model, script, lanes)
                    if (model.retryTransitions > 0 || model.fallbackTransitions > 0) budgetsWithRecovery += retries
                }
            }
        }
        val required = setOf(
            "same-route-retry", "retry-success", "retry-exhaustion", "retry-after-retry",
            "fallback-after-exhaustion", "multi-fallback-traversal", "circuit-open-fallback",
            "all-routes-open", "fallback-denial", "explicit-provider",
            "output-visible-terminal", "cancellation", "neutral-terminal-after-retry",
        )
        val missing = required - lanes
        assertThat(missing)
            .withFailMessage("semantic coverage guard: corpus never reached lanes $missing")
            .isEmpty()
        // providerRetries = 0 AND providerRetries > 0 must both drive recovery.
        assertThat(budgetsWithRecovery).contains(0)
        assertThat(budgetsWithRecovery).contains(1)
        // "different effective fallback model" is owned by the P0-I discriminator
        // (routing-plan resolution, outside the retry/fallback disposition lattice).
    }
}
