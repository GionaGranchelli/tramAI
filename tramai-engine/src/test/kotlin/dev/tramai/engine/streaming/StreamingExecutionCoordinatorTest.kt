package dev.tramai.engine.streaming

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderCapabilityException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvents
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
import org.assertj.core.api.Assertions.catchThrowable
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

    @AiService
    private interface ExplicitProviderStreamingService {
        @Operation(prompt = "Answer", model = "logical-model", provider = "primary", providerRetries = 1)
        fun stream(input: String): Flow<StreamChunk>
    }

    @AiService
    private interface StreamingServiceZeroRetries {
        @Operation(prompt = "Answer", model = "logical-model", providerRetries = 0)
        fun stream(input: String): Flow<StreamChunk>
    }

    @AiService
    private interface StreamingServiceThreeRetries {
        @Operation(prompt = "Answer", model = "logical-model", providerRetries = 3)
        fun stream(input: String): Flow<StreamChunk>
    }

    private val defaultBudget = TokenBudgetSettings(hardMaxTokensPerOperation = 20)

    private fun operation() = ServiceDefinitionCompiler(
        OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory()),
    ).compile(StreamingService::class).operations.entries.single().value.definition

    private fun operationWithRetries() = ServiceDefinitionCompiler(
        OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory()),
    ).compile(StreamingServiceWithRetries::class).operations.entries.single().value.definition

    private fun operationWithExplicitProvider() = ServiceDefinitionCompiler(
        OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory()),
    ).compile(ExplicitProviderStreamingService::class).operations.entries.single().value.definition

    private fun operationWithZeroRetries() = ServiceDefinitionCompiler(
        OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory()),
    ).compile(StreamingServiceZeroRetries::class).operations.entries.single().value.definition

    private fun operationWithThreeRetries() = ServiceDefinitionCompiler(
        OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory()),
    ).compile(StreamingServiceThreeRetries::class).operations.entries.single().value.definition

    private class OrderedSink {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val stamps = java.util.concurrent.CopyOnWriteArrayList<Long>()
        fun record(name: String) { events += name; stamps += System.nanoTime() }
        fun count(name: String): Int = events.count { it == name }
        /** Monotonic elapsed nanos between the first occurrence of [from] and the first [to] that FOLLOWS it, or null when absent. */
        fun elapsedBetween(from: String, to: String): Long? {
            val fromIdx = events.indexOf(from).takeIf { it >= 0 } ?: return null
            val toIdx = (fromIdx + 1 until events.size).firstOrNull { events[it] == to } ?: return null
            return stamps[toIdx] - stamps[fromIdx]
        }
    }

    private fun List<String>.join() = joinToString(",")

    private class RecordingObservation(private val sink: OrderedSink) : OperationObservation {
        val completions = mutableListOf<Boolean?>()
        override fun onProviderResponse(response: ModelResponse) { sink.record("observation.provider-response") }
        override fun onProviderFailure(error: Throwable) { sink.record("observation.provider-failure") }
        override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) = Unit
        override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
            sink.record("observation.engine-event:$name")
            // RETRY_SCHEDULED carries the contract-bearing delay contract:
            // delay_millis + delay_source are the policy's observable output.
            if (name == RuntimeEvents.RETRY_SCHEDULED.name) {
                sink.record(
                    "retry.attr:delay=${attributes[RuntimeAttributes.DELAY_MILLIS.name]}" +
                        ":source=${attributes[RuntimeAttributes.DELAY_SOURCE.name]}" +
                        ":retryIndex=${attributes[RuntimeAttributes.RETRY_INDEX.name]}",
                )
            }
        }
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

    /** Observer recording (providerId, globalAttempt) per onCallStarted — 8.2h P0-J. */
    private class AttemptRecordingObserver(private val sink: OrderedSink) : OperationObserver {
        val attempts = mutableListOf<Pair<String, Int>>()
        override fun onCallStarted(context: OperationCallContext): OperationObservation {
            attempts += context.providerId to context.attempt
            sink.record("observer.start:${context.providerId}:${context.attempt}")
            return RecordingObservation(sink)
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

    private fun requestWithExplicitProvider(conversationId: String? = null, budget: TokenBudgetCoordinator = TokenBudgetCoordinator(defaultBudget)) =
        StreamingExecutionRequest(operationWithExplicitProvider(), listOf("input"), budget.createTracker(), conversationId)

    private fun requestWithZeroRetries(conversationId: String? = null, budget: TokenBudgetCoordinator = TokenBudgetCoordinator(defaultBudget)) =
        StreamingExecutionRequest(operationWithZeroRetries(), listOf("input"), budget.createTracker(), conversationId)

    private fun requestWithThreeRetries(conversationId: String? = null, budget: TokenBudgetCoordinator = TokenBudgetCoordinator(defaultBudget)) =
        StreamingExecutionRequest(operationWithThreeRetries(), listOf("input"), budget.createTracker(), conversationId)

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

    @Test fun `P0-B retry budget N means exactly N plus 1 provider attempts before fallback`() {
        // providerRetries = 1 -> maxAttempts = 2. Both attempts fail retryably;
        // fallback fires only AFTER the budget is exhausted. Event shape:
        // startup_retry once (first retryable failure) + retry.scheduled once
        // (the single retry) + fallback transition once (exhaustion).
        runBlocking {
        val sink = OrderedSink()
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))) } }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("ok")) } }
        fallback.sink = sink
        val chunks = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(sink), sink).execute(requestWithRetries()).toList()
        assertThat(primary.streamRequests).hasSize(2) // N + 1 attempts
        assertThat(fallback.streamRequests).hasSize(1) // fallback only after exhaustion
        assertThat(chunks).containsExactly(StreamChunk.Complete("ok"))
        assertThat(sink.events.filter { it == "observation.engine-event:tramai.retry.scheduled" }).hasSize(1)
        assertThat(sink.events.filter { it == "observation.engine-event:tramai.streaming.startup_retry" }).hasSize(1)
        assertThat(sink.events.filter { it == "policy.fallback" }).hasSize(1)
        }
    }

    @Test fun `P0-C retry success short-circuits fallback completely`() {
        // providerRetries = 1: attempt 1 fails retryably, retry succeeds.
        // No fallback transition, no fallback provider call, retry.scheduled
        // exactly once, startup_retry once.
        runBlocking {
        val sink = OrderedSink()
        var attempts = 0
        val primary = RecordingProvider("primary") {
            attempts++
            if (attempts == 1) flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))) }
            else flow { emit(StreamChunk.Token("ok")); emit(StreamChunk.Complete("ok")) }
        }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("bad")) } }
        fallback.sink = sink
        val chunks = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(sink), sink).execute(requestWithRetries()).toList()
        assertThat(chunks).containsExactly(StreamChunk.Token("ok"), StreamChunk.Complete("ok"))
        assertThat(primary.streamRequests).hasSize(2)
        assertThat(fallback.streamRequests).isEmpty()
        assertThat(sink.events).doesNotContain("policy.fallback")
        assertThat(sink.events.filter { it == "observation.engine-event:tramai.retry.scheduled" }).hasSize(1)
        assertThat(sink.events.filter { it == "observation.engine-event:tramai.streaming.startup_retry" }).hasSize(1)
        }
    }

    @Test fun `P0-J global attempt numbering stays continuous across retries and route changes`() {
        // providerRetries = 1 -> primary attempts 0 and 1, then the exhausted
        // failure advances to the fallback route which runs as attempt 2.
        // The engine-level attempt counter must never reset on fallback.
        runBlocking {
        val sink = OrderedSink()
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))) } }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("ok")) } }
        fallback.sink = sink
        val observer = AttemptRecordingObserver(sink)
        coordinator(plan("primary" to primary, "fallback" to fallback), observer, sink).execute(requestWithRetries()).toList()
        assertThat(observer.attempts).containsExactly(
            "primary" to 0,
            "primary" to 1,
            "fallback" to 2,
        )
        }
    }

    @Test fun `P0-K breaker sees one terminal route outcome when retries succeed`() {
        // failure -> retry -> success: the breaker records ZERO failures and
        // ONE success. Intermediate retryable attempts never call onFailure
        // (8.2g boundary); only the terminal success completes breaker
        // authority via onSuccess.
        runBlocking {
        val sink = OrderedSink()
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100))
        var attempts = 0
        val provider = RecordingProvider("p") {
            attempts++
            if (attempts == 1) flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))) }
            else flow { emit(StreamChunk.Complete("ok")) }
        }
        val coordinator = coordinator(plan("p" to provider), RecordingOperationObserver(sink), sink, circuitEnabled = true, circuitBreaker = breaker)
        coordinator.execute(requestWithRetries()).toList()
        assertThat(breaker.openUntilMillis("p")).isNull() // never tripped
        assertThat(sink.events).doesNotContain("observation.engine-event:tramai.circuit.opened")
        }
    }

    @Test fun `P0-K breaker sees one terminal route outcome when retries exhaust`() {
        // failure -> retry -> exhausted: the breaker records EXACTLY ONE
        // terminal failure (on the exhausted attempt). Not two, not zero.
        // The single qualifying onFailure trips -> OPEN with a fresh deadline.
        runBlocking {
        val sink = OrderedSink()
        var now = 0L
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })
        val provider = RecordingProvider("p") { flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))) } }
        val coordinator = coordinator(plan("p" to provider), RecordingOperationObserver(sink), sink, circuitEnabled = true, circuitBreaker = breaker)
        coordinator.execute(requestWithRetries()).toList()
        assertThat(breaker.openUntilMillis("p")).isEqualTo(100)
        assertThat(sink.events.filter { it == "observation.engine-event:tramai.circuit.opened" }).hasSize(1)
        }
    }

    @Test fun `P0-D after first streaming token retryable failure is terminal no retry no fallback`() {
        // Once ANY token has escaped to the consumer, retry/fallback authority
        // is permanently gone: a retryable failure after a token is surfaced
        // as a terminal error. Provider A called exactly once; provider B never;
        // zero retry events; zero fallback transitions.
        runBlocking {
        val sink = OrderedSink()
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Token("visible")); emit(StreamChunk.Error(ProviderException("down", retryable = true))) } }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("bad")) } }
        fallback.sink = sink
        val chunks = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(sink), sink).execute(requestWithRetries()).toList()
        val error = chunks.last() as StreamChunk.Error
        assertThat(error.cause).isInstanceOf(ProviderException::class.java)
        assertThat((error.cause as ProviderException).retryable).isTrue()
        assertThat(primary.streamRequests).hasSize(1) // no retry after token
        assertThat(fallback.streamRequests).isEmpty() // no fallback after token
        assertThat(sink.events).doesNotContain("observation.engine-event:tramai.retry.scheduled", "policy.fallback")
        assertThat(sink.events.filter { it == "observation.engine-event:tramai.streaming.startup_retry" }).isEmpty()
        }
    }

    @Test fun `P0-E cancellation during provider call performs no retry or fallback classification`() {
        // Cancellation is absolute terminal control flow: it never enters
        // retry/fallback classification, never calls the fallback provider,
        // never fires a fallback transition. providerRetries=1 is in effect
        // but the cancellation lands inside the provider stream, not on a
        // classified failure.
        runBlocking {
        val sink = OrderedSink()
        val entered = CompletableDeferred<Unit>()
        val primary = RecordingProvider("primary") { flow { entered.complete(Unit); awaitCancellation() } }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("bad")) } }
        fallback.sink = sink
        val c = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(sink), sink)
        val collector = async { c.execute(requestWithRetries()).toList() }
        entered.await()
        collector.cancel()
        runCatching { collector.await() }
        assertThat(collector.isCancelled).isTrue()
        assertThat(primary.streamRequests).hasSize(1) // no retry
        assertThat(fallback.streamRequests).isEmpty() // no fallback
        assertThat(sink.events).doesNotContain("policy.fallback", "observation.engine-event:tramai.retry.scheduled")
        }
    }

    @Test fun `P0-F circuit open route consumes zero attempts and advances exactly once`() {
        // A circuit-open route is skipped with ZERO provider attempts and ZERO
        // retry-budget consumption; the fallback route is the next candidate
        // and runs as the next global attempt. No retry events, no startup_retry.
        runBlocking {
        val sink = OrderedSink()
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100))
        breaker.onFailure((breaker.beforeCall("primary") as dev.tramai.engine.CircuitBreakerAdmission.Allowed).permit, ProviderException("down", retryable = true))
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Complete("bad")) } }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("ok")) } }
        fallback.sink = sink
        val observer = AttemptRecordingObserver(sink)
        val chunks = coordinator(plan("primary" to primary, "fallback" to fallback), observer, sink, circuitEnabled = true, circuitBreaker = breaker).execute(requestWithRetries()).toList()
        assertThat(chunks).containsExactly(StreamChunk.Complete("ok"))
        assertThat(primary.streamRequests).isEmpty() // zero attempts on the open route
        assertThat(fallback.streamRequests).hasSize(1)
        assertThat(observer.attempts).containsExactly("fallback" to 0) // advanced exactly once, attempt 0
        assertThat(sink.events).doesNotContain("observation.engine-event:tramai.retry.scheduled", "observation.engine-event:tramai.streaming.startup_retry")
        }
    }

    @Test fun `P0-G fallback gate denial is fail-closed next provider never executes`() {
        // The fallback gate is a fail-closed authorization boundary: denial
        // throws PolicyViolationException, the NEXT provider never executes,
        // and the original provider failure is preserved as suppressed.
        runBlocking {
        val sink = OrderedSink()
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))) } }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("bad")) } }
        fallback.sink = sink
        val c = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(sink), sink, denyFallback = true)
        val thrown = catchThrowable { runBlocking { c.execute(requestWithRetries()).toList() } }
        assertThat(thrown).isInstanceOf(PolicyViolationException::class.java)
        assertThat(fallback.streamRequests).isEmpty() // next provider never executed
        assertThat((thrown as PolicyViolationException).suppressed)
            .anySatisfy { assertThat(it).isInstanceOf(ProviderException::class.java).hasMessage("down") }
        }
    }

    @Test fun `P0-H explicit provider operation retries that provider but never enters model fallbacks`() {
        // @Operation(provider = "primary") resolves to exactly ONE route: the
        // explicit provider. Even though a model fallback is registered, the
        // exhausted retryable failure must NOT advance to it — the explicit
        // provider has no configured fallback chain.
        runBlocking {
        val sink = OrderedSink()
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))) } }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("bad")) } }
        fallback.sink = sink
        val chunks = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(sink), sink).execute(requestWithExplicitProvider()).toList()
        val error = chunks.single() as StreamChunk.Error
        assertThat(error.cause).isInstanceOf(ProviderException::class.java)
        assertThat(primary.streamRequests).hasSize(2) // retried the explicit provider
        assertThat(fallback.streamRequests).isEmpty() // never entered model fallbacks
        assertThat(sink.events).doesNotContain("policy.fallback")
        }
    }

    @Test fun `P0-I fallback route uses its own effective model not the requested model`() {
        // The fallback route registered via fallbackModel("logical-model",
        // "fallback-model", "fallback") must be invoked with the FALLBACK
        // route's effective model ("fallback-model"), never the primary or the
        // requested "logical-model".
        runBlocking {
        val sink = OrderedSink()
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))) } }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("ok")) } }
        fallback.sink = sink
        val builder = ProviderRoutingPlan.builder()
            .provider("primary", primary)
            .provider("fallback", fallback)
            .model("logical-model", "primary")
            .fallbackModel("logical-model", "fallback-model", "fallback")
        val chunks = coordinator(builder.build(), RecordingOperationObserver(sink), sink).execute(requestWithRetries()).toList()
        assertThat(chunks).containsExactly(StreamChunk.Complete("ok"))
        assertThat(fallback.streamRequests).hasSize(1)
        assertThat(fallback.streamRequests.single().model).isEqualTo("fallback-model")
        assertThat(primary.streamRequests).hasSize(2)
        }
    }

    @Test fun `P0-L terminal error precedence last executed provider failure beats circuit open`() {
        // Deterministic precedence: the LAST EXECUTED provider failure wins over
        // a circuit-open-only skip. Route 1 exhausts its retry budget (a real
        // provider failure); route 2 is circuit-open (skipped). The terminal
        // error must be the provider failure, NOT the circuit-open exception.
        runBlocking {
        val sink = OrderedSink()
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100))
        breaker.onFailure((breaker.beforeCall("fallback") as dev.tramai.engine.CircuitBreakerAdmission.Allowed).permit, ProviderException("down", retryable = true))
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Error(ProviderException("primary down", retryable = true, retryAfterMillis = 0))) } }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("bad")) } }
        fallback.sink = sink
        val chunks = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(sink), sink, circuitEnabled = true, circuitBreaker = breaker).execute(requestWithRetries()).toList()
        val error = chunks.single() as StreamChunk.Error
        assertThat(error.cause).isInstanceOf(ProviderException::class.java)
        assertThat(error.cause.message).isEqualTo("primary down") // last executed failure, not circuit-open
        assertThat(fallback.streamRequests).isEmpty()
        }
    }

    @Test fun `P0-L circuit open only skips produce the circuit open terminal error`() {
        // No provider was ever executed (all routes circuit-open): the terminal
        // error is the circuit-open exception from the LAST skipped route.
        runBlocking {
        val sink = OrderedSink()
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100))
        breaker.onFailure((breaker.beforeCall("primary") as dev.tramai.engine.CircuitBreakerAdmission.Allowed).permit, ProviderException("down", retryable = true))
        breaker.onFailure((breaker.beforeCall("fallback") as dev.tramai.engine.CircuitBreakerAdmission.Allowed).permit, ProviderException("down", retryable = true))
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Complete("bad")) } }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("bad")) } }
        fallback.sink = sink
        val chunks = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(sink), sink, circuitEnabled = true, circuitBreaker = breaker).execute(requestWithRetries()).toList()
        val error = chunks.single() as StreamChunk.Error
        assertThat(error.cause).isInstanceOf(CircuitBreakerOpenException::class.java)
        assertThat(primary.streamRequests).isEmpty()
        assertThat(fallback.streamRequests).isEmpty()
        }
    }

    @Test fun `P0-M startup retry event suppressed when providerRetries zero and no fallback route`() {
        // providerRetries = 0, no fallback route: the retryable startup failure
        // has NO recovery path, so STREAMING_STARTUP_RETRY must NOT be emitted —
        // the event name must never announce a retry that cannot happen
        // (Option 1: recovery-eligible marker).
        runBlocking {
        val sink = OrderedSink()
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))) } }
        primary.sink = sink
        val chunks = coordinator(plan("primary" to primary), RecordingOperationObserver(sink), sink).execute(requestWithZeroRetries()).toList()
        val error = chunks.single() as StreamChunk.Error
        assertThat(error.cause).isInstanceOf(ProviderException::class.java)
        assertThat(primary.streamRequests).hasSize(1) // no retry possible
        assertThat(sink.events).doesNotContain("observation.engine-event:tramai.streaming.startup_retry", "observation.engine-event:tramai.retry.scheduled", "policy.fallback")
        }
    }

    @Test fun `P0-M startup retry event emitted when providerRetries zero but fallback route exists`() {
        // providerRetries = 0 + fallback route: the recovery action is FALLBACK,
        // not retry. STREAMING_STARTUP_RETRY (recovery-eligible marker) fires
        // once; no RETRY_SCHEDULED (no same-route retry exists); one fallback
        // transition.
        runBlocking {
        val sink = OrderedSink()
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))) } }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("ok")) } }
        fallback.sink = sink
        val chunks = coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(sink), sink).execute(requestWithZeroRetries()).toList()
        assertThat(chunks).containsExactly(StreamChunk.Complete("ok"))
        assertThat(primary.streamRequests).hasSize(1) // zero retries
        assertThat(fallback.streamRequests).hasSize(1)
        assertThat(sink.events).contains("observation.engine-event:tramai.streaming.startup_retry", "policy.fallback")
        assertThat(sink.events).doesNotContain("observation.engine-event:tramai.retry.scheduled")
        }
    }

    @Test fun `P0-O early Stop from circuit-open leaves the route once and advances exactly one fallback`() {
        // CircuitBreakerOpenException is fallback-eligible (shouldFallbackFrom)
        // but NOT retryable (ProviderRetryPolicy), so the policy returns Stop
        // BEFORE the retry budget is exhausted. Stop must PERMANENTLY
        // relinquish same-route retry authority: the primary route is invoked
        // exactly ONCE, the fallback gate runs exactly ONCE, and the outer
        // candidate loop advances to the fallback provider exactly once.
        // Regression: return@repeat re-entered the same route on Stop.
        runBlocking {
        val sink = OrderedSink()
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Error(CircuitBreakerOpenException("primary", 0L))) } }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("ok")) } }
        fallback.sink = sink
        val observer = AttemptRecordingObserver(sink)
        val chunks = coordinator(plan("primary" to primary, "fallback" to fallback), observer, sink).execute(requestWithThreeRetries()).toList()
        assertThat(chunks).containsExactly(StreamChunk.Complete("ok"))
        assertThat(primary.streamRequests).withFailMessage("P0-O primary invoked once after Stop").hasSize(1)
        assertThat(fallback.streamRequests).withFailMessage("P0-O fallback invoked exactly once").hasSize(1)
        assertThat(sink.count("policy.fallback")).withFailMessage("P0-O gate invoked exactly once").isEqualTo(1)
        assertThat(sink.count("observation.engine-event:tramai.retry.scheduled")).withFailMessage("P0-O no same-route retry").isZero()
        assertThat(observer.attempts).withFailMessage("P0-O ordered attempt trace").containsExactly("primary" to 0, "fallback" to 1)
        }
    }

    @Test fun `P0-N retry delay contract delay source and cap are observable and suspension is real`() {
        // The retry policy produces contract-bearing delay semantics that the
        // lifecycle must propagate: retryAfterMillis honored, backoff for
        // timeouts, cap for oversized retryAfter, and the announced delay is
        // ACTUALLY applied (suspension elision is observable, not just the attr).
        runBlocking {
        // Case A: retryAfterMillis=100 -> delay_millis=100, source=retry_after,
        // and the coordinator actually suspends ~100ms before the next attempt.
        val sink = OrderedSink()
        val primary = RecordingProvider("primary") { flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 100))) } }
        primary.sink = sink
        val fallback = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("ok")) } }
        fallback.sink = sink
        coordinator(plan("primary" to primary, "fallback" to fallback), RecordingOperationObserver(sink), sink).execute(requestWithRetries()).toList()
        assertThat(sink.events).contains("retry.attr:delay=100:source=retry_after:retryIndex=0")
        val elapsed = sink.elapsedBetween("observation.engine-event:tramai.retry.scheduled", "policy.before-invocation")
            ?: error("retry/next-attempt markers missing")
        assertThat(elapsed).isGreaterThanOrEqualTo(80_000_000L) // 100ms announced -> >= 80ms actually suspended

        // Case B: TimeoutException at retryIndex=0 -> backoff delay 50, source=backoff.
        val sink2 = OrderedSink()
        val p2 = RecordingProvider("primary") { flow { emit(StreamChunk.Error(TimeoutException("timeout"))) } }
        p2.sink = sink2
        val f2 = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("ok")) } }
        f2.sink = sink2
        coordinator(plan("primary" to p2, "fallback" to f2), RecordingOperationObserver(sink2), sink2).execute(requestWithRetries()).toList()
        assertThat(sink2.events).contains("retry.attr:delay=50:source=backoff:retryIndex=0")

        // Case C: retryAfterMillis beyond the cap (maxRetryAfterMillis=200) is
        // clamped to the cap: delay_millis=200, source still retry_after.
        val sink3 = OrderedSink()
        val p3 = RecordingProvider("primary") { flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 500))) } }
        p3.sink = sink3
        val f3 = RecordingProvider("fallback") { flow { emit(StreamChunk.Complete("ok")) } }
        f3.sink = sink3
        val capped = ProviderRetryPolicy(ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.0, maxRetryAfterMillis = 200)) { 0.0 })
        coordinator(plan("primary" to p3, "fallback" to f3), RecordingOperationObserver(sink3), sink3, retryPolicy = capped).execute(requestWithRetries()).toList()
        assertThat(sink3.events).contains("retry.attr:delay=200:source=retry_after:retryIndex=0")
        }
    }

    @Test fun `P0-K breaker neutral terminal completion when retries end in permanent failure`() {
        // retryable -> retry -> PERMANENT failure: intermediate transient
        // failures do NOT leak into breaker accounting. The authoritative route
        // result is the permanent error — a NEUTRAL terminal completion: zero
        // qualifying breaker failures, zero successes, breaker never trips.
        runBlocking {
        val sink = OrderedSink()
        var now = 0L
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })
        var attempts = 0
        val provider = RecordingProvider("p") {
            attempts++
            if (attempts == 1) flow { emit(StreamChunk.Error(ProviderException("down", retryable = true, retryAfterMillis = 0))) }
            else flow { emit(StreamChunk.Error(ProviderException("permanent", retryable = false))) }
        }
        val coordinator = coordinator(plan("p" to provider), RecordingOperationObserver(sink), sink, circuitEnabled = true, circuitBreaker = breaker)
        coordinator.execute(requestWithRetries()).toList()
        assertThat(breaker.openUntilMillis("p")).isNull() // never tripped
        assertThat(sink.events).doesNotContain("observation.engine-event:tramai.circuit.opened")
        assertThat(sink.events.filter { it == "observation.engine-event:tramai.retry.scheduled" }).hasSize(1)
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
