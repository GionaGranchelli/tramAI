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
import dev.tramai.engine.CircuitBreakerPermit
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
 * The SCRIPT is the single independent specification: every action declares
 * its own route, admission, and outcome. Model and reality consume the SAME
 * script independently — the model is never used to configure the real
 * coordinator's inputs (oracle independence). The generator's budget-aware
 * archetypes guarantee the script's declared route progression is consistent,
 * and [runModel] enforces it with require() on every action.
 *
 * Reality corpus: 32 seeds × retry budgets {0,1,2} = 96 coordinator executions
 * (plus forced archetypes and P13/P14 fixtures). Semantic coverage guard:
 * 32 × 3 budgets × 3 route counts = 288 model scripts.
 *
 * Deterministic only: injectable clock, zero retry jitter, no Thread.sleep.
 * "zero-delay" retry policy means jitterRatio = 0.0; timeout retries still use
 * the policy's normal backoff values (retryAfterMillis = 0 in the scripted
 * errors, so the delay resolves to 0 in practice).
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

    @AiService
    private interface ExplicitProviderStreamingService {
        @Operation(prompt = "Answer", model = "logical-model", provider = "p0", providerRetries = 1)
        fun stream(input: String): Flow<StreamChunk>
    }

    private class OrderedSink {
        val events = mutableListOf<String>()
        fun record(name: String) { events += name }
        fun count(name: String): Int = events.count { it == name }
    }

    private class RecordingObservation(private val sink: OrderedSink) : OperationObservation {
        override fun onProviderResponse(response: ModelResponse) { sink.record("observation.provider-response") }
        override fun onProviderFailure(error: Throwable) { sink.record("observation.provider-failure") }
        override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) = Unit
        override fun onEngineEvent(name: String, attributes: Map<String, Any?>) { sink.record("observation.engine-event:$name") }
        override fun onCallCompleted(parseSuccess: Boolean?) { sink.record("observation.complete:$parseSuccess") }
        override fun onCallCancelled() { sink.record("observation.cancelled") }
    }

    /** Records the ordered REAL (providerId, globalAttempt) trace from production. */
    private class AttemptRecordingObserver(private val sink: OrderedSink) : OperationObserver {
        val attempts = mutableListOf<Pair<String, Int>>()
        override fun onCallStarted(context: OperationCallContext): OperationObservation {
            attempts += context.providerId to context.attempt
            sink.record("observer.start:${context.providerId}:${context.attempt}")
            return RecordingObservation(sink)
        }
    }

    /**
     * Records the first SEMANTIC breaker disposition per permit. Distinct from
     * raw method invocations: the structural finally onAbandoned() fires after
     * an already-recorded disposition and is deduplicated by permit key.
     */
    private class RecordingCircuitBreaker(settings: CircuitBreakerSettings) : ProviderCircuitBreaker(settings) {
        val dispositions = mutableListOf<BreakerDisposition>()
        private val recorded = mutableSetOf<String>()
        private fun key(permit: CircuitBreakerPermit) = "${permit.providerId}#${permit.generation}"
        override fun onSuccess(permit: CircuitBreakerPermit) {
            if (recorded.add(key(permit))) dispositions += BreakerDisposition.SUCCESS
            super.onSuccess(permit)
        }
        override fun onFailure(permit: CircuitBreakerPermit, error: Throwable): Boolean {
            val qualifying = error is TimeoutException || (error is ProviderException && error.retryable)
            if (recorded.add(key(permit))) dispositions += if (qualifying) BreakerDisposition.QUALIFYING_FAILURE else BreakerDisposition.NEUTRAL
            return super.onFailure(permit, error)
        }
        override fun onAbandoned(permit: CircuitBreakerPermit) {
            if (recorded.add(key(permit))) dispositions += BreakerDisposition.NEUTRAL
            super.onAbandoned(permit)
        }
        fun resetRecording() { dispositions.clear(); recorded.clear() }
    }

    /** Scripted streaming provider: pops one prebuilt response per stream() call. */
    private class ScriptedProvider(private val name: String, private val sink: OrderedSink, private val responses: List<Response>) : ModelProvider, StreamCapable {
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
                    is Response.Chunks -> response.chunks.forEach { if (it is StreamChunk.Token) sink.record("stream.token"); emit(it) }
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

    private fun explicitOperation() = ServiceDefinitionCompiler(
        OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory()),
    ).compile(ExplicitProviderStreamingService::class).operations.entries.single().value.definition

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
        observer: AttemptRecordingObserver,
        denyFallback: Boolean = false,
    ): StreamingExecutionCoordinator {
        val policy = PolicyEngine { PolicyDecision.Allow }
        return StreamingExecutionCoordinator(
            routingPlan, breaker, CoroutineScope(Dispatchers.Default), AtomicBoolean(false), "test.Service", "test.Service",
            observer,
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
            ProviderFallbackGate { _, previousProviderId, _, nextProviderId, _, _ ->
                sink.record("policy.fallback")
                sink.record("fallback-edge:$previousProviderId->$nextProviderId")
                if (denyFallback) throw PolicyViolationException(PolicyDecision.Deny("fallback denied", "TEST"))
            },
            StreamingBeforeResponseReturnGate { _, _, _ -> Unit },
        )
    }

    private data class AttemptStep(val routeIndex: Int, val globalAttempt: Int, val outcome: AttemptOutcome)
    private data class DispositionTrace(
        val routeIndex: Int,
        val retryIndex: Int,
        val visibilityBefore: OutputVisibility,
        val disposition: RouteDisposition,
    )

    private data class ModelTrace(
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
     * The model consumes the SCRIPT's declared routes authoritatively. Every
     * action's routeIndex must equal where the model's own decisions have led;
     * a mismatch means either the generator produced an inconsistent script or
     * the model's routing decision is wrong (the property then fails loudly).
     */
    private fun runModel(script: RetryFallbackScript): ModelTrace {
        var model = ProviderRetryFallbackModel(routeCount = script.routeCount, providerRetries = script.providerRetries, fallbackGateDenies = script.fallbackDenied)
        val dispositions = mutableListOf<DispositionTrace>()
        val attemptTrace = mutableListOf<AttemptStep>()
        val fallbackEdges = mutableListOf<Pair<Int, Int>>()
        for (action in script.actions) {
            when (action) {
                is RetryFallbackScriptAction.EmitToken -> model = model.emitToken()
                is RetryFallbackScriptAction.Admit -> {
                    require(action.routeIndex == model.routeIndex) {
                        "script admits route ${action.routeIndex} but the model's decisions led to route ${model.routeIndex} — script inconsistent or model routing bug"
                    }
                    if (action.circuitOpen) {
                        val result = model.apply(RouteAdmission.CircuitOpen(action.routeIndex), AttemptOutcome.RetryableFailure)
                        dispositions += DispositionTrace(model.routeIndex, model.retryIndex, model.visibility, result.disposition)
                        when {
                            result.disposition is RouteDisposition.Fallback -> fallbackEdges += model.routeIndex to result.next.routeIndex
                            result.next.terminalOutcome is TerminalOutcome.FallbackDenied -> fallbackEdges += model.routeIndex to (model.routeIndex + 1)
                        }
                        model = result.next
                        if (model.isTerminal) break
                    }
                }
                is RetryFallbackScriptAction.Attempt -> {
                    require(action.routeIndex == model.routeIndex) {
                        "script attempt on route ${action.routeIndex} but the model's decisions led to route ${model.routeIndex} — script inconsistent or model routing bug"
                    }
                    attemptTrace += AttemptStep(model.routeIndex, model.globalAttempt, action.outcome)
                    val result = model.apply(RouteAdmission.Allowed, action.outcome)
                    dispositions += DispositionTrace(model.routeIndex, model.retryIndex, model.visibility, result.disposition)
                    when {
                        result.disposition is RouteDisposition.Fallback -> fallbackEdges += model.routeIndex to result.next.routeIndex
                        // A DENIED fallback still invoked the gate with the
                        // (route -> route+1) edge before the denial threw (P0-G).
                        result.next.terminalOutcome is TerminalOutcome.FallbackDenied -> fallbackEdges += model.routeIndex to (model.routeIndex + 1)
                    }
                    model = result.next
                    if (model.isTerminal) break
                }
            }
        }
        return ModelTrace(
            dispositions = dispositions,
            retryTransitions = model.retryTransitions,
            fallbackTransitions = model.fallbackTransitions,
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

    /** Builds per-provider response queues from the SCRIPT alone — never from the model. */
    private fun buildResponses(script: RetryFallbackScript, sink: OrderedSink): Map<String, List<ScriptedProvider.Response>> {
        val responses = mutableMapOf<String, MutableList<ScriptedProvider.Response>>()
        var pendingToken = false
        for (action in script.actions) {
            when (action) {
                is RetryFallbackScriptAction.EmitToken -> pendingToken = true
                is RetryFallbackScriptAction.Admit -> Unit
                is RetryFallbackScriptAction.Attempt -> {
                    val provider = "p${action.routeIndex}"
                    val base = scriptToChunks(action.outcome)
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

    private fun runReality(script: RetryFallbackScript): RealityTrace {
        val sink = OrderedSink()
        val responses = buildResponses(script, sink)
        val realityRouteCount = if (script.explicitProvider) 3 else script.routeCount
        val providers = (0 until realityRouteCount).associate { "p$it" to ScriptedProvider("p$it", sink, responses["p$it"] ?: emptyList()) }
        val breaker = RecordingCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 1_000L))
        // Pre-open circuit for circuit-open admissions (harness setup — excluded
        // from the recorded trace).
        for (action in script.actions) {
            if (action is RetryFallbackScriptAction.Admit && action.circuitOpen) {
                val name = "p${action.routeIndex}"
                breaker.onFailure((breaker.beforeCall(name) as CircuitBreakerAdmission.Allowed).permit, ProviderException("down", retryable = true))
            }
        }
        breaker.resetRecording()
        val observer = AttemptRecordingObserver(sink)
        val c = coordinator(plan(realityRouteCount, providers), breaker, sink, observer, denyFallback = script.fallbackDenied)
        val request = if (script.explicitProvider) {
            StreamingExecutionRequest(explicitOperation(), listOf("input"), TokenBudgetCoordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 20)).createTracker(), null)
        } else {
            StreamingExecutionRequest(operation(script.providerRetries), listOf("input"), TokenBudgetCoordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 20)).createTracker(), null)
        }
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
        val fallbackEdges = sink.events.filter { it.startsWith("fallback-edge:") }.mapNotNull { edge ->
            val body = edge.removePrefix("fallback-edge:")
            val parts = body.split("->")
            if (parts.size == 2) {
                val from = parts[0].substringAfter("p").toIntOrNull()
                val to = parts[1].substringAfter("p").toIntOrNull()
                if (from != null && to != null) from to to else null
            } else null
        }
        val firstTokenIndex = sink.events.indexOfFirst { it == "stream.token" }.takeIf { it >= 0 }
        val afterToken = if (firstTokenIndex != null) {
            sink.events.drop(firstTokenIndex + 1)
        } else {
            emptyList()
        }
        return RealityTrace(
            observedAttempts = observer.attempts,
            fallbackEdges = fallbackEdges,
            retryEvents = sink.count("observation.engine-event:tramai.retry.scheduled"),
            fallbackEvents = sink.count("policy.fallback"),
            circuitOpenedEvents = sink.count("observation.engine-event:tramai.circuit.opened"),
            breakerDispositions = breaker.dispositions,
            terminalComplete = terminalComplete,
            terminalErrorClass = terminalErrorClass,
            cancelled = cancelled,
            fallbackDenied = fallbackDenied,
            firstTokenIndex = firstTokenIndex,
            perRouteAttempts = providers.mapValues { (_, p) -> p.streamRequests.size },
            retryEventsAfterToken = afterToken.count { it == "observation.engine-event:tramai.retry.scheduled" },
            fallbackEventsAfterToken = afterToken.count { it == "policy.fallback" },
            circuitOpenedEventsAfterToken = afterToken.count { it == "observation.engine-event:tramai.circuit.opened" },
        )
    }

    private fun assertModelInvariants(trace: ModelTrace, script: RetryFallbackScript, label: String) {
        // P4 (per-route, the real contract): every admitted route consumes at
        // most providerRetries + 1 attempts; retries(route) <= providerRetries.
        trace.attemptTrace.map { it.routeIndex }.distinct().forEach { route ->
            val attempts = trace.attemptTrace.count { it.routeIndex == route }
            assertThat(attempts)
                .withFailMessage("$label P4 attempts(route $route)=$attempts > providerRetries+1=${script.providerRetries + 1}")
                .isLessThanOrEqualTo(script.providerRetries + 1)
        }
        // P12: OUTPUT_VISIBLE is irreversible at disposition time — a VISIBLE
        // attempt never yields retry or fallback.
        trace.dispositions.forEach { d ->
            if (d.visibilityBefore == OutputVisibility.VISIBLE) {
                assertThat(d.disposition is RouteDisposition.RetrySameRoute || d.disposition is RouteDisposition.Fallback)
                    .withFailMessage("$label P12 visible disposition $d must not retry/fallback").isFalse()
            }
        }
        // P6: success terminates.
        if (trace.dispositions.any { it.disposition is RouteDisposition.Succeeded }) {
            assertThat(trace.terminalOutcome).withFailMessage("$label P6 success terminal").isEqualTo(TerminalOutcome.Success)
        }
        // P8: cancellation bypasses classification.
        if (trace.dispositions.any { it.disposition == RouteDisposition.Cancelled }) {
            assertThat(trace.terminalOutcome).withFailMessage("$label P8 cancelled").isEqualTo(TerminalOutcome.Cancelled)
        }
        // P11: global attempt counter strictly increases across retries and fallbacks.
        val attempts = trace.attemptTrace.map { it.globalAttempt }
        assertThat(attempts).withFailMessage("$label P11 strictly increasing").isSorted()
        assertThat(attempts.zipWithNext().all { (a, b) -> b > a }).withFailMessage("$label P11 strictly increasing").isTrue()
        // P14: every admitted route produces at most ONE semantic breaker
        // disposition; circuit-open routes produce none. The count equals the
        // number of DISTINCT routes that ran at least one attempt.
        val admittedRoutesWithAttempts = trace.attemptTrace.map { it.routeIndex }.distinct().size
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

        val reality = runReality(script)

        // P1: ordered attempt-trace equivalence — model (route, globalAttempt)
        // vs reality (provider, attempt). A swapped provider distribution can
        // keep the same TOTAL; the ordered trace cannot.
        val modelAttempts = model.attemptTrace.map { "p${it.routeIndex}" to it.globalAttempt }
        assertThat(reality.observedAttempts).withFailMessage("$label P1 ordered attempt trace").containsExactlyElementsOf(modelAttempts)
        // P1: ordered fallback edges (P10: routes strictly advance, never revisited).
        assertThat(reality.fallbackEdges).withFailMessage("$label P1 fallback edges").containsExactlyElementsOf(model.fallbackEdges)
        // P1: retry/fallback/breaker totals.
        assertThat(reality.retryEvents).withFailMessage("$label P1 retries").isEqualTo(model.retryTransitions)
        assertThat(reality.fallbackEvents).withFailMessage("$label P1 fallbacks").isEqualTo(model.fallbackTransitions)
        assertThat(reality.circuitOpenedEvents).withFailMessage("$label P1 breaker failures").isEqualTo(model.breakerQualifyingFailures)
        // P14: reality observes the SAME semantic breaker dispositions as the model.
        assertThat(reality.breakerDispositions).withFailMessage("$label P14 reality breaker dispositions").containsExactlyElementsOf(model.breakerDispositions)
        // P12: after the first REAL emitted token, no retry/fallback authority
        // remains. The breaker failure event is the TERMINAL disposition
        // recording (allowed after the token) — only recovery actions are
        // forbidden: RETRY_SCHEDULED and the fallback gate.
        if (reality.firstTokenIndex != null) {
            assertThat(reality.retryEventsAfterToken + reality.fallbackEventsAfterToken)
                .withFailMessage("$label P12 retry/fallback after first token").isZero()
        }

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
    fun `P13 explicit provider resolution keeps route cardinality one in reality`() {
        // The plan contains p0+p1+p2 (fallback chain), but the operation
        // declares @Operation(provider = "p0"): reality must run ONLY p0 —
        // model fallbacks are bypassed by explicit-provider resolution (P0-H).
        val script = ProviderRetryFallbackActionGenerator.generate(11, providerRetries = 1, routeCount = 1)
        assertThat(script.explicitProvider).isTrue()
        val model = runModel(script)
        assertThat(model.attemptTrace.map { it.routeIndex }.distinct()).containsExactly(0)
        assertThat(model.fallbackEdges).isEmpty()
        assertModelInvariants(model, script, "explicit-provider")

        val reality = runReality(script)
        assertThat(reality.observedAttempts.map { it.first }).withFailMessage("P13 only p0 executes").containsExactly("p0", "p0")
        assertThat(reality.perRouteAttempts["p1"] ?: 0).withFailMessage("P13 p1 never executes").isZero()
        assertThat(reality.perRouteAttempts["p2"] ?: 0).withFailMessage("P13 p2 never executes").isZero()
        assertThat(reality.fallbackEdges).isEmpty()
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
                RetryFallbackScriptAction.Attempt(0, AttemptOutcome.RetryableFailure),
                RetryFallbackScriptAction.Attempt(0, AttemptOutcome.Success),
            ),
        )
        val successModel = runModel(successScript)
        assertThat(successModel.breakerQualifyingFailures).isZero()
        assertThat(successModel.breakerSuccesses).isEqualTo(1)
        assertThat(successModel.breakerDispositions).containsExactly(BreakerDisposition.SUCCESS)
        val successReality = runReality(successScript)
        assertThat(successReality.breakerDispositions).withFailMessage("P14 reality SUCCESS").containsExactly(BreakerDisposition.SUCCESS)

        // retryable -> retry -> exhausted retryable: QUALIFYING_FAILURE, 1.
        val exhaustedScript = RetryFallbackScript(
            providerRetries = 1,
            routeCount = 1,
            actions = listOf(
                RetryFallbackScriptAction.Admit(0),
                RetryFallbackScriptAction.Attempt(0, AttemptOutcome.RetryableFailure),
                RetryFallbackScriptAction.Attempt(0, AttemptOutcome.RetryableFailure),
            ),
        )
        val exhaustedModel = runModel(exhaustedScript)
        assertThat(exhaustedModel.breakerQualifyingFailures).isEqualTo(1)
        assertThat(exhaustedModel.breakerDispositions).containsExactly(BreakerDisposition.QUALIFYING_FAILURE)
        val exhaustedReality = runReality(exhaustedScript)
        assertThat(exhaustedReality.breakerDispositions).withFailMessage("P14 reality QUALIFYING_FAILURE").containsExactly(BreakerDisposition.QUALIFYING_FAILURE)

        // retryable -> retry -> permanent: NEUTRAL, zero qualifying failures.
        val permanentScript = RetryFallbackScript(
            providerRetries = 1,
            routeCount = 1,
            actions = listOf(
                RetryFallbackScriptAction.Admit(0),
                RetryFallbackScriptAction.Attempt(0, AttemptOutcome.RetryableFailure),
                RetryFallbackScriptAction.Attempt(0, AttemptOutcome.PermanentProviderFailure),
            ),
        )
        val permanentModel = runModel(permanentScript)
        assertThat(permanentModel.breakerQualifyingFailures).isZero()
        assertThat(permanentModel.breakerDispositions).containsExactly(BreakerDisposition.NEUTRAL)
        assertThat(permanentModel.terminalOutcome).isEqualTo(TerminalOutcome.Failure(FailureKind.PERMANENT))
        val permanentReality = runReality(permanentScript)
        assertThat(permanentReality.breakerDispositions).withFailMessage("P14 reality NEUTRAL").containsExactly(BreakerDisposition.NEUTRAL)

        // primary exhausted -> fallback success: TWO route dispositions —
        // primary permit QUALIFYING_FAILURE, fallback permit SUCCESS. Not
        // "one invocation completion": each admitted route owns its permit.
        val fallbackScript = RetryFallbackScript(
            providerRetries = 0,
            routeCount = 2,
            actions = listOf(
                RetryFallbackScriptAction.Admit(0),
                RetryFallbackScriptAction.Attempt(0, AttemptOutcome.RetryableFailure),
                RetryFallbackScriptAction.Admit(1),
                RetryFallbackScriptAction.Attempt(1, AttemptOutcome.Success),
            ),
        )
        val fallbackModel = runModel(fallbackScript)
        assertThat(fallbackModel.breakerQualifyingFailures).isEqualTo(1)
        assertThat(fallbackModel.breakerSuccesses).isEqualTo(1)
        assertThat(fallbackModel.breakerDispositions).containsExactly(BreakerDisposition.QUALIFYING_FAILURE, BreakerDisposition.SUCCESS)
        val fallbackReality = runReality(fallbackScript)
        assertThat(fallbackReality.breakerDispositions)
            .withFailMessage("P14 reality two route dispositions").containsExactly(BreakerDisposition.QUALIFYING_FAILURE, BreakerDisposition.SUCCESS)
    }

    private fun recordLane(model: ModelTrace, script: RetryFallbackScript, lanes: MutableSet<String>) {
        if (model.dispositions.any { it.disposition is RouteDisposition.RetrySameRoute }) lanes += "same-route-retry"
        if (model.dispositions.zipWithNext().any { (a, b) -> a.disposition is RouteDisposition.RetrySameRoute && b.disposition is RouteDisposition.Succeeded }) lanes += "retry-success"
        if (model.retryTransitions >= 1 && model.breakerDispositions.lastOrNull() == BreakerDisposition.QUALIFYING_FAILURE) lanes += "retry-exhaustion"
        if (script.actions.any { it is RetryFallbackScriptAction.Attempt && it.outcome == AttemptOutcome.RetryableFailureWithRetryAfter } && model.retryTransitions >= 1) lanes += "retry-after-retry"
        if (model.dispositions.any { it.disposition is RouteDisposition.Fallback } && model.breakerDispositions.contains(BreakerDisposition.QUALIFYING_FAILURE)) lanes += "fallback-after-exhaustion"
        if (model.dispositions.count { it.disposition is RouteDisposition.Fallback } >= 2) lanes += "multi-fallback-traversal"
        if (script.actions.any { it is RetryFallbackScriptAction.Admit && it.circuitOpen } && model.dispositions.any { it.disposition is RouteDisposition.Fallback }) lanes += "circuit-open-fallback"
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
