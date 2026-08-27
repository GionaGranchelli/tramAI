package dev.tramai.engine.streaming

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderCapabilityException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ConversationIdProvider
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.UsageMetrics
import dev.tramai.core.observation.NoOpOperationInterceptor
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.core.provider.StreamCapable
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
import dev.tramai.engine.provider.ProviderFallbackGate
import dev.tramai.engine.provider.ProviderInvocationGate
import dev.tramai.engine.provider.ProviderResolutionGate
import dev.tramai.engine.provider.ProviderRetryPolicy
import dev.tramai.engine.tool.ToolExposureCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class StreamingExecutionCoordinatorTest {

    @AiService
    private interface StreamingService {
        @Operation(prompt = "Answer", model = "logical-model")
        fun stream(input: String): Flow<StreamChunk>
    }

    @AiService
    private interface StreamingServiceWithRetries {
        @Operation(prompt = "Answer", model = "logical-model", providerRetries = 1)
        fun stream(input: String): Flow<StreamChunk>
    }

    private val defaultBudget = TokenBudgetSettings(hardMaxTokensPerOperation = 20)

    private fun operation() = ServiceDefinitionCompiler(
        OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory()),
    ).compile(StreamingService::class).operations.entries.single().value.definition

    private fun operationWithRetries() = ServiceDefinitionCompiler(
        OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory()),
    ).compile(StreamingServiceWithRetries::class).operations.entries.single().value.definition

    private class OrderedSink {
        val events = mutableListOf<String>()
        fun record(name: String) { events += name }
    }

    private fun List<String>.join() = joinToString(",")

    private class RecordingObservation(private val sink: OrderedSink) : OperationObservation {
        val completions = mutableListOf<Boolean?>()
        override fun onProviderResponse(response: ModelResponse) { sink.record("observation.provider-response") }
        override fun onProviderFailure(error: Throwable) { sink.record("observation.provider-failure") }
        override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) = Unit
        override fun onEngineEvent(name: String, attributes: Map<String, Any?>) { sink.record("observation.engine-event:$name") }
        override fun onCallCompleted(parseSuccess: Boolean?) { completions += parseSuccess; sink.record("observation.complete:$parseSuccess") }
        override fun onCallCancelled() { sink.record("observation.cancelled") }
    }

    private class RecordingOperationObserver(private val sink: OrderedSink) : OperationObserver {
        val observations = mutableListOf<RecordingObservation>()
        override fun onCallStarted(context: OperationCallContext): OperationObservation = RecordingObservation(sink).also {
            observations += it
            sink.record("observer.start")
        }
    }

    /** Observer whose [onCallStarted] throws on the N-th call — a pre-try escape. */
    private class ThrowingOnStartObserver(private val sink: OrderedSink, private val throwOnCall: Int) : OperationObserver {
        private var calls = 0
        val observations = mutableListOf<RecordingObservation>()
        override fun onCallStarted(context: OperationCallContext): OperationObservation {
            calls++
            if (calls == throwOnCall) throw CancellationException("pre-try observer escape")
            return RecordingObservation(sink).also { observations += it; sink.record("observer.start") }
        }
    }

    private class RecordingMemory(private val sink: OrderedSink? = null) : ChatMemory {
        var history: List<Message> = emptyList()
        val stored = mutableListOf<Pair<String, List<Message>>>()
        override fun get(conversationId: String): List<Message> = history
        override fun add(conversationId: String, messages: List<Message>) { sink?.record("memory.persist"); stored += conversationId to messages }
        override fun add(conversationId: String, message: Message) = add(conversationId, listOf(message))
        override fun clear(conversationId: String) = Unit
    }

    private class RecordingProvider(
        private val name: String,
        private val responder: RecordingProvider.(ModelRequest) -> Flow<StreamChunk>,
    ) : ModelProvider, StreamCapable {
        val streamRequests = mutableListOf<ModelRequest>()
        override suspend fun complete(request: ModelRequest): ModelResponse = error("complete is not used")
        override fun stream(request: ModelRequest): Flow<StreamChunk> {
            streamRequests += request
            sink?.record("provider.stream:$name")
            return responder(request)
        }
        var sink: OrderedSink? = null
        override fun providerId(): String = name
    }

    private class NonStreamingProvider(private val name: String) : ModelProvider {
        var calls = 0
        override suspend fun complete(request: ModelRequest): ModelResponse { calls++; return ModelResponse("unused") }
        override fun providerId(): String = name
    }

    private fun plan(vararg providers: Pair<String, ModelProvider>): ProviderRoutingPlan {
        val builder = ProviderRoutingPlan.builder()
        providers.forEach { (name, provider) -> builder.provider(name, provider) }
        if (providers.isNotEmpty()) {
            builder.model("logical-model", providers.first().first)
            providers.drop(1).forEach { (name, _) -> builder.fallbackProvider("logical-model", name) }
        }
        return builder.build()
    }

    private fun request(conversationId: String? = null, budget: TokenBudgetCoordinator = TokenBudgetCoordinator(defaultBudget)) =
        StreamingExecutionRequest(operation(), listOf("input"), budget.createTracker(), conversationId)

    private fun requestWithRetries(conversationId: String? = null, budget: TokenBudgetCoordinator = TokenBudgetCoordinator(defaultBudget)) =
        StreamingExecutionRequest(operationWithRetries(), listOf("input"), budget.createTracker(), conversationId)

    private fun coordinator(
        routingPlan: ProviderRoutingPlan,
        observer: OperationObserver,
        sink: OrderedSink? = null,
        memory: RecordingMemory = RecordingMemory(sink),
        circuitEnabled: Boolean = false,
        denyBeforeResponseReturn: Boolean = false,
        denyFallback: Boolean = false,
        budgetSettings: TokenBudgetSettings = defaultBudget,
        circuitBreaker: ProviderCircuitBreaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = circuitEnabled, failureThreshold = 1)),
        closed: AtomicBoolean = AtomicBoolean(false),
        qualifiedServiceName: String? = "test.StreamingService",
        retryPolicy: ProviderRetryPolicy = ProviderRetryPolicy(ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.0)) { 0.0 }),
    ): StreamingExecutionCoordinator {
        val recordingSink = sink ?: OrderedSink()
        val policy = PolicyEngine { PolicyDecision.Allow }
        fun denied() = PolicyViolationException(PolicyDecision.Deny("denied", "TEST"))
        return StreamingExecutionCoordinator(
            routingPlan, circuitBreaker, CoroutineScope(Dispatchers.Default), closed, "test.StreamingService", qualifiedServiceName, observer,
            NoOpOperationInterceptor,
            ToolExposureCoordinator(ToolRegistry(), PolicyEnforcementHelper(policy, AtomicBoolean(false))),
            ConversationMemoryCoordinator(memory, ConversationIdProvider { "cid" }), TokenBudgetCoordinator(budgetSettings),
            ModelRegistryEnforcer(object : ModelRegistry { override suspend fun findApprovedModel(providerId: String, modelName: String) = null }, ModelRegistrySettings(enabled = false)),
            retryPolicy,
            ProviderResolutionGate { _, _, _ -> recordingSink.record("policy.before-resolution") },
            ProviderInvocationGate { _, _, _, _ -> recordingSink.record("policy.before-invocation") },
            ProviderFallbackGate { _, _, _, _, _, _ -> recordingSink.record("policy.fallback"); if (denyFallback) throw denied() },
            StreamingBeforeResponseReturnGate { _, _, _ -> recordingSink.record("policy.before-response-return"); if (denyBeforeResponseReturn) throw denied() },
        )
    }

    @Test fun `engine already closed fails at collection without touching provider or memory`() {
        val sink = OrderedSink(); val provider = RecordingProvider("p") { flow { emit(StreamChunk.Token("unused")) } }; provider.sink = sink
        val memory = RecordingMemory(sink); val observer = RecordingOperationObserver(sink)
        val c = coordinator(plan("p" to provider), observer, sink, memory, closed = AtomicBoolean(true))
        assertThatThrownBy { runBlocking { c.execute(request("cid")).toList() } }.isInstanceOf(IllegalStateException::class.java).hasMessage("Tramai runtime is closed")
        assertThat(provider.streamRequests).isEmpty(); assertThat(memory.stored).isEmpty()
    }

    @Test fun `memory snapshot is taken at flow construction not collection`() {
        runTest {
        val provider = RecordingProvider("p") { flow { emit(StreamChunk.Complete("ok")) } }; val memory = RecordingMemory()
        memory.history = listOf(Message(MessageRole.USER, "H1")); val c = coordinator(plan("p" to provider), RecordingOperationObserver(OrderedSink()), memory = memory)
        val stream = c.execute(request("cid")); memory.history = listOf(Message(MessageRole.USER, "H2"))
        stream.toList()
        assertThat(provider.streamRequests.single().messages.map { it.content }).contains("H1").doesNotContain("H2")
        }
    }

    @Test fun `consumer cancellation after first chunk stops bridge and persists nothing`() {
        runBlocking {
        val cleaned = CompletableDeferred<Unit>(); val provider = RecordingProvider("p") { flow { try { emit(StreamChunk.Token("first")); awaitCancellation() } finally { cleaned.complete(Unit) } } }
        val memory = RecordingMemory(); val c = coordinator(plan("p" to provider), RecordingOperationObserver(OrderedSink()), memory = memory)
        assertThat(c.execute(request("cid")).take(1).toList()).containsExactly(StreamChunk.Token("first")); withTimeout(2_000) { cleaned.await() }; assertThat(memory.stored).isEmpty()
        }
    }

    @Test fun `successful completion closes the flow exactly once`() {
        runBlocking {
        val provider = RecordingProvider("p") { flow { emit(StreamChunk.Token("hello")); emit(StreamChunk.Token(" ")); emit(StreamChunk.Token("world")); emit(StreamChunk.Complete("hello world")) } }
        val chunks = coordinator(plan("p" to provider), RecordingOperationObserver(OrderedSink())).execute(request()).toList()
        assertThat(chunks).containsExactly(StreamChunk.Token("hello"), StreamChunk.Token(" "), StreamChunk.Token("world"), StreamChunk.Complete("hello world"))
        }
    }

    @Test fun `two text chunks preserve order and full text`() {
        runBlocking {
        val provider = RecordingProvider("p") { flow { emit(StreamChunk.Token("hello ")); emit(StreamChunk.Token("world")); emit(StreamChunk.Complete("hello world")) } }
        val chunks = coordinator(plan("p" to provider), RecordingOperationObserver(OrderedSink())).execute(request()).toList()
        assertThat(chunks.filterIsInstance<StreamChunk.Complete>().single().fullText).isEqualTo("hello world")
        assertThat(chunks.filterIsInstance<StreamChunk.Token>().map { it.text }).containsExactly("hello ", "world")
        }
    }

    @Test fun `successful stream persists accumulated assistant response`() {
        runBlocking {
        val sink = OrderedSink(); val provider = RecordingProvider("p") { flow { emit(StreamChunk.Token("hello ")); emit(StreamChunk.Complete("hello world")) } }; provider.sink = sink
        val memory = RecordingMemory(sink).also { it.history = listOf(Message(MessageRole.USER, "old")) }; val observer = RecordingOperationObserver(sink)
        coordinator(plan("p" to provider), observer, sink, memory).execute(request("cid")).toList()
        assertThat(memory.stored.single().second.last()).isEqualTo(Message(MessageRole.ASSISTANT, "hello world"))
        // completion is recorded before memory persistence — swapping would fail
        assertThat(sink.events.takeLast(2)).containsExactly("observation.complete:null", "memory.persist")
        }
    }

    @Test fun `usage is committed exactly once`() {
        runBlocking {
        val sink = OrderedSink(); val observer = RecordingOperationObserver(sink)
        val provider = RecordingProvider("p") { flow { emit(StreamChunk.Complete("ok", UsageMetrics(4, 2))) } }
        coordinator(plan("p" to provider), observer, sink).execute(request()).toList()
        assertThat(sink.events.count { it == "observation.complete:null" }).isEqualTo(1); assertThat(sink.events.count { it == "observation.provider-response" }).isEqualTo(1)
        }
    }

    @Test fun `before response return denial aborts before provider invocation`() {
        val sink = OrderedSink(); val provider = RecordingProvider("p") { flow { emit(StreamChunk.Complete("no")) } }; val observer = RecordingOperationObserver(sink)
        val c = coordinator(plan("p" to provider), observer, sink, denyBeforeResponseReturn = true)
        assertThatThrownBy { runBlocking { c.execute(request()).toList() } }.isInstanceOf(PolicyViolationException::class.java)
        assertThat(provider.streamRequests).isEmpty(); assertThat(sink.events).contains("observation.engine-event:tramai.route.selected").doesNotContain("observation.provider-response")
    }

    @Test fun `P0-A streaming retryable startup failure honors providerRetries before fallback`() {
        // Contract: providerRetries = 1 means exactly 2 provider attempts on the
        // SAME route; a retryable startup failure on attempt 1 must retry the
        // primary, and only the terminal attempt-2 success ends routing.
        // Fallback must not fire. (Current master: single attempt, immediate
        // fallback — this test is the 8.2h P0-A RED probe.)
        runBlocking {
        val sink = OrderedSink()
        var primaryAttempts = 0
        val primary = RecordingProvider("primary") {
            primaryAttempts++
            if (primaryAttempts == 1) flow { emit(StreamChunk.Error(ProviderException("down", retryable = true))) }
            else flow { emit(StreamChunk.Token("ok")); emit(StreamChunk.Complete("ok")) }
        }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Token("bad")); emit(StreamChunk.Complete("bad")) } }
        fallback.sink = sink
        val chunks = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(sink), sink).execute(requestWithRetries()).toList()
        assertThat(primary.streamRequests).hasSize(2)
        assertThat(fallback.streamRequests).isEmpty()
        assertThat(sink.events).doesNotContain("policy.fallback")
        assertThat(chunks).containsExactly(StreamChunk.Token("ok"), StreamChunk.Complete("ok"))
        }
    }

    @Test fun `retryable startup error before first token falls back to next route`() {
        runBlocking {
        val sink = OrderedSink(); val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Error(ProviderException("down", retryable = true))) } }; val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Token("ok")); emit(StreamChunk.Complete("ok")) } }; primary.sink = sink; fallback.sink = sink
        val chunks = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(sink), sink).execute(request()).toList()
        // Default providerRetries = 3 -> 4 attempts on the primary BEFORE the
        // exhausted failure falls back. The terminal attempt records the
        // breaker failure; the three intermediate retries emit retry.scheduled.
        assertThat(chunks).containsExactly(StreamChunk.Token("ok"), StreamChunk.Complete("ok")); assertThat(primary.streamRequests).hasSize(4); assertThat(fallback.streamRequests).hasSize(1); assertThat(sink.events).contains("observation.engine-event:tramai.streaming.startup_retry", "observation.engine-event:tramai.retry.scheduled", "policy.fallback")
        }
    }

    @Test fun `non retryable provider error does not fall back`() {
        runBlocking {
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Error(ProviderException("boom", retryable = false))) } }; val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("bad")) } }
        val chunks = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(OrderedSink())).execute(request()).toList()
        val error = chunks.single() as StreamChunk.Error
        assertThat(error.cause).isInstanceOf(ProviderException::class.java); assertThat(error.cause.message).isEqualTo("boom"); assertThat((error.cause as ProviderException).retryable).isFalse(); assertThat(fallback.streamRequests).isEmpty()
        }
    }

    @Test fun `first route that cannot stream fails with capability exception`() {
        val provider = NonStreamingProvider("p"); val c = coordinator(plan("p" to provider), RecordingOperationObserver(OrderedSink()))
        assertThatThrownBy { runBlocking { c.execute(request()).toList() } }.isInstanceOf(ProviderCapabilityException::class.java)
    }

    @Test fun `fallback gate denial prevents second provider invocation`() {
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Error(ProviderException("down", retryable = true))) } }; val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("bad")) } }
        val c = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(OrderedSink()), denyFallback = true)
        assertThatThrownBy { runBlocking { c.execute(request()).toList() } }.isInstanceOf(PolicyViolationException::class.java); assertThat(fallback.streamRequests).isEmpty()
    }

    @Test fun `H6 streaming success reaches the breaker and closes an open circuit`() {
        runBlocking {
        var now = 0L
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })
        var calls = 0
        val provider = RecordingProvider("p") { flow {
            calls++
            if (calls <= 4) emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0)))
            else emit(StreamChunk.Complete("ok"))
        } }
        val coordinator = coordinator(plan("p" to provider), RecordingOperationObserver(OrderedSink()), circuitEnabled = true, circuitBreaker = breaker)

        // Default providerRetries = 3 -> 4 attempts. Intermediate retries never
        // trip the breaker (8.2h P0-K); only the terminal exhausted failure on
        // attempt 4 records onFailure -> OPEN until t=100.
        coordinator.execute(request()).toList()
        assertThat(breaker.openUntilMillis("p")).isEqualTo(100)
        assertThat(provider.streamRequests).hasSize(4)

        // While open, the provider is skipped (no stream request) and the breaker stays open.
        coordinator.execute(request()).toList()
        assertThat(provider.streamRequests).hasSize(4)
        assertThat(breaker.openUntilMillis("p")).isEqualTo(100)

        // At exact expiry the probe is admitted; a successful stream must reach
        // onSuccess and CLOSE the circuit. The observable difference from a
        // stuck HALF_OPEN: a subsequent call is ADMITTED and reaches the provider.
        now = 100
        coordinator.execute(request()).toList()
        assertThat(provider.streamRequests).hasSize(5)
        now = 200
        coordinator.execute(request()).toList()
        assertThat(provider.streamRequests).hasSize(6)
        assertThat(breaker.openUntilMillis("p")).isNull()
        }
    }

    @Test fun `H11 streaming neutral probe outcome cannot strand recovery`() {
        runBlocking {
        var now = 0L
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })
        var calls = 0
        val provider = RecordingProvider("p") { flow {
            calls++
            if (calls <= 4) emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0)))
            else emit(StreamChunk.Error(ProviderException("permanent", retryable = false)))
        } }
        val coordinator = coordinator(plan("p" to provider), RecordingOperationObserver(OrderedSink()), circuitEnabled = true, circuitBreaker = breaker)

        // Default providerRetries = 3 -> 4 attempts; the terminal exhausted
        // retryable failure trips -> OPEN until t=100. Intermediate retries
        // never touch the breaker.
        coordinator.execute(request()).toList()
        assertThat(breaker.openUntilMillis("p")).isEqualTo(100)
        assertThat(provider.streamRequests).hasSize(4)

        // At exact expiry the probe is admitted; its NON-RETRYABLE error is a
        // neutral outcome: not a breaker failure, but it must release probe
        // ownership (reopen with a fresh deadline) instead of stranding the
        // streaming recovery in HALF_OPEN.
        now = 100
        coordinator.execute(request()).toList()
        assertThat(breaker.openUntilMillis("p")).isEqualTo(200)

        // At the new expiry a call is again admitted as the next probe.
        now = 200
        coordinator.execute(request()).toList()
        assertThat(provider.streamRequests).hasSize(6) // 4 + probe + probe
        assertThat(breaker.openUntilMillis("p")).isEqualTo(300)
        }
    }

    @Test fun `H7 streaming HALF_OPEN probe failure reopens and the next expiry admits again`() {
        runBlocking {
        var now = 0L
        val sink = OrderedSink()
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })
        val provider = RecordingProvider("p") { flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))) } }
        val coordinator = coordinator(plan("p" to provider), RecordingOperationObserver(sink), circuitEnabled = true, circuitBreaker = breaker)

        // Default providerRetries = 3 -> 4 attempts; only the terminal attempt
        // records the breaker failure. Intermediate retries emit retry.scheduled
        // and never touch the breaker (8.2h P0-K).
        coordinator.execute(request()).toList()
        assertThat(breaker.openUntilMillis("p")).isEqualTo(100)
        assertThat(sink.events.filter { it == "observation.engine-event:tramai.circuit.opened" }).hasSize(1)

        // At exact expiry the probe is admitted; its qualifying failure must
        // immediately reopen with a fresh deadline (now + openDuration) — NOT
        // get stuck in HALF_OPEN where every later call is rejected forever.
        now = 100
        coordinator.execute(request()).toList()
        assertThat(breaker.openUntilMillis("p")).isEqualTo(200)
        // A qualifying probe failure is a breaker TRIP: one more CIRCUIT_OPENED
        // event. (An abandoned/neutral probe would reopen WITHOUT the event —
        // this discriminates the two paths.)
        assertThat(sink.events.filter { it == "observation.engine-event:tramai.circuit.opened" }).hasSize(2)

        // At the new expiry a call is again admitted as the next probe.
        now = 200
        coordinator.execute(request()).toList()
        assertThat(provider.streamRequests).hasSize(12) // 4 attempts × 3 executes
        assertThat(breaker.openUntilMillis("p")).isEqualTo(300)
        assertThat(sink.events.filter { it == "observation.engine-event:tramai.circuit.opened" }).hasSize(3)
        }
    }

    @Test fun `H13 streaming token-budget exhaustion on the probe cannot strand recovery`() {
        runBlocking {
        var now = 0L
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })
        var calls = 0
        val provider = RecordingProvider("p") { flow {
            calls++
            if (calls <= 4) emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0)))
            else emit(StreamChunk.Complete("ok", usage = UsageMetrics(inputTokens = 0, outputTokens = 5)))
        } }
        val tightBudget = TokenBudgetSettings(hardMaxTokensPerAttempt = 1)
        val coordinator = coordinator(
            plan("p" to provider),
            RecordingOperationObserver(OrderedSink()),
            circuitEnabled = true,
            circuitBreaker = breaker,
            budgetSettings = tightBudget,
        )

        // Default providerRetries = 3 -> 4 attempts; the terminal exhausted
        // retryable failure trips -> OPEN until t=100.
        coordinator.execute(request(budget = TokenBudgetCoordinator(tightBudget))).toList()
        assertThat(breaker.openUntilMillis("p")).isEqualTo(100)

        // At exact expiry the probe is admitted; its Complete chunk exceeds the
        // token budget. The budget rejection is a NEUTRAL terminal outcome
        // (never a breaker failure), but it must release probe ownership
        // (reopen with fresh deadline) instead of stranding recovery.
        now = 100
        coordinator.execute(request(budget = TokenBudgetCoordinator(tightBudget))).toList()
        assertThat(breaker.openUntilMillis("p")).isEqualTo(200)

        // At the new expiry a call is again admitted as the next probe.
        now = 200
        coordinator.execute(request(budget = TokenBudgetCoordinator(tightBudget))).toList()
        assertThat(provider.streamRequests).hasSize(6) // 4 + probe + probe
        assertThat(breaker.openUntilMillis("p")).isEqualTo(300)
        }
    }

    @Test fun `H16 streaming pre-try observer escape cannot strand the HALF_OPEN probe`() {
        runBlocking {
        var now = 0L
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })
        var calls = 0
        val provider = RecordingProvider("p") { flow {
            calls++
            if (calls <= 4) emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0)))
            else emit(StreamChunk.Complete("ok", usage = UsageMetrics(inputTokens = 0, outputTokens = 5)))
        } }
        // Observer throws on the FIFTH onCallStarted — i.e. on the HALF_OPEN
        // probe's startStreamingObservation, AFTER the first route exhausted
        // its 4-attempt retry budget and tripped the breaker. Inside
        // startStreamingObservation, BEFORE executeStreamingRoute's own try.
        val coordinator = coordinator(
            plan("p" to provider),
            ThrowingOnStartObserver(OrderedSink(), throwOnCall = 5),
            circuitEnabled = true,
            circuitBreaker = breaker,
        )

        // First route exhausts its retry budget -> OPEN until t=100.
        coordinator.execute(request()).toList()
        assertThat(breaker.openUntilMillis("p")).isEqualTo(100)

        // At exact expiry the probe is admitted; startStreamingObservation
        // throws (pre-try escape). The structural scope guard must release the
        // probe -> OPEN(gen+1, fresh deadline) instead of stranding HALF_OPEN.
        now = 100
        assertThatThrownBy { runBlocking { coordinator.execute(request()).toList() } }
            .isInstanceOf(CancellationException::class.java)
        assertThat(breaker.openUntilMillis("p")).isEqualTo(200)

        // At the new expiry a call is again admitted as the next probe and the
        // stream completes: recovery was not stranded by the pre-try escape.
        now = 200
        coordinator.execute(request()).toList()
        assertThat(provider.streamRequests).hasSize(5) // 4 attempts + final probe
        assertThat(breaker.openUntilMillis("p")).isNull()
        }
    }

    @Test fun `open circuit skips route and uses next`() {
        runBlocking {
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1)); breaker.onFailure((breaker.beforeCall("primary") as dev.tramai.engine.CircuitBreakerAdmission.Allowed).permit, ProviderException("down", retryable = true))
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Complete("bad")) } }; val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("ok")) } }
        val chunks = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(OrderedSink()), circuitEnabled = true, circuitBreaker = breaker).execute(request()).toList()
        assertThat(primary.streamRequests).isEmpty(); assertThat(chunks).containsExactly(StreamChunk.Complete("ok"))
        }
    }

    @Test fun `all routes open circuit emits no available route chunk with circuit error`() {
        runBlocking {
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1)); breaker.onFailure((breaker.beforeCall("p") as dev.tramai.engine.CircuitBreakerAdmission.Allowed).permit, ProviderException("down", retryable = true))
        val provider = RecordingProvider("p") { flow { emit(StreamChunk.Complete("bad")) } }
        val chunks = coordinator(plan("p" to provider), RecordingOperationObserver(OrderedSink()), circuitEnabled = true, circuitBreaker = breaker).execute(request()).toList()
        val error = chunks.single() as StreamChunk.Error
        assertThat(error.cause).isInstanceOf(dev.tramai.core.exception.CircuitBreakerOpenException::class.java)
        assertThat(provider.streamRequests).isEmpty()
        }
    }

    @Test fun `stream error chunk semantics unchanged`() {
        runBlocking {
        val sink = OrderedSink(); val provider = RecordingProvider("p") { flow { emit(StreamChunk.Error(ProviderException("boom", retryable = false))) } }
        val chunks = coordinator(plan("p" to provider), RecordingOperationObserver(sink), sink).execute(request()).toList()
        val error = chunks.single() as StreamChunk.Error
        assertThat(error.cause).isInstanceOf(ProviderException::class.java); assertThat(error.cause.message).isEqualTo("boom"); assertThat(sink.events).contains("observation.provider-failure", "observation.complete:null")
        }
    }

    @Test fun `null qualified service name renders null in normalized streaming error`() {
        runBlocking {
        // Guards P1-A: normalizeStreamingError must interpolate the raw nullable
        // qualifiedName, not the normalized serviceTypeName fallback. A thrown
        // non-Tramai failure (anonymous class → null qualified name) must render
        // "null.stream" exactly as master did.
        val provider = RecordingProvider("p") { flow { throw IllegalStateException("boom") } }
        val chunks = coordinator(plan("p" to provider), RecordingOperationObserver(OrderedSink()), qualifiedServiceName = null).execute(request()).toList()
        val error = chunks.single() as StreamChunk.Error
        assertThat(error.cause).isInstanceOf(ProviderException::class.java)
        assertThat(error.cause.message).contains("failed while streaming null.stream")
        }
    }

    @Test fun `null qualified service name renders null in unterminated stream message`() {
        runBlocking {
        // The unterminated-stream path builds its message from qualifiedServiceName too.
        val provider = RecordingProvider("p") { flow { } }
        val chunks = coordinator(plan("p" to provider), RecordingOperationObserver(OrderedSink()), qualifiedServiceName = null).execute(request()).toList()
        val error = chunks.single() as StreamChunk.Error
        assertThat(error.cause.message).contains("ended streaming without a terminal chunk while invoking null.stream")
        }
    }

    @Test fun `failed stream does not persist memory`() {
        runBlocking {
        val provider = RecordingProvider("p") { flow { emit(StreamChunk.Error(ProviderException("boom", retryable = false))) } }; val memory = RecordingMemory()
        coordinator(plan("p" to provider), RecordingOperationObserver(OrderedSink()), memory = memory).execute(request("cid")).toList()
        assertThat(memory.stored).isEmpty()
        }
    }

    @Test fun `cancellation remains primary`() {
        runBlocking {
        val sink = OrderedSink(); val entered = CompletableDeferred<Unit>(); val provider = RecordingProvider("p") { flow { entered.complete(Unit); awaitCancellation() } }; val observer = RecordingOperationObserver(sink)
        val c = coordinator(plan("p" to provider), observer); val collector = async { c.execute(request()).toList() }; entered.await(); collector.cancel(); runCatching { collector.await() }
        // Cancellation stays a cancellation: the observation is never completed
        // and the bridge terminates without emitting chunks. The exact
        // onCallCancelled event is timing-dependent (depends on where the
        // cancellation lands in the bridge), so assert the deterministic state.
        assertThat(observer.observations.single().completions).isEmpty()
        assertThat(collector.isCancelled).isTrue()
        }
    }

    @Test fun `no available route raises configuration error when model has no provider`() {
        runBlocking {
        assertThatThrownBy { runBlocking { coordinator(ProviderRoutingPlan.builder().build(), RecordingOperationObserver(OrderedSink())).execute(request()).toList() } }
            .isInstanceOf(dev.tramai.core.exception.ConfigurationException::class.java)
            .hasMessageContaining("No provider is registered for model 'logical-model'")
        }
    }

    @Test fun `memory messages are injected and persisted without history`() {
        runBlocking {
        val history = listOf(Message(MessageRole.USER, "old")); val memory = RecordingMemory().also { it.history = history }; val provider = RecordingProvider("p") { flow { emit(StreamChunk.Complete("ok")) } }
        coordinator(plan("p" to provider), RecordingOperationObserver(OrderedSink()), memory = memory).execute(request("cid")).toList()
        assertThat(provider.streamRequests.single().messages.take(history.size)).isEqualTo(history)
        val stored = memory.stored.single().second
        // history prefix is dropped on persist; the accumulated assistant response is stored
        assertThat(stored.map { it.content }).doesNotContain("old").contains("ok")
        assertThat(stored.last().role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(stored.last().content).isEqualTo("ok")
        }
    }
}
