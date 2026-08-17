package dev.tramai.engine.structured

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.StructuredOutputException
import dev.tramai.core.exception.safeStructuredOutputFailure
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.observation.NoOpOperationInterceptor
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.structured.StructuredOutputContract
import dev.tramai.core.structured.StructuredOutputFailureCode
import dev.tramai.core.structured.StructuredOutputFailureDiagnosticEvent
import dev.tramai.core.structured.StructuredOutputFailureDiagnosticObserver
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.core.structured.StructuredOutputResult
import dev.tramai.core.structured.NoOpStructuredOutputFailureDiagnosticObserver
import dev.tramai.engine.CachedOperationResult
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ModelRegistryEnforcer
import dev.tramai.engine.OperationCacheKey
import dev.tramai.engine.OperationResponseCache
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.planning.OperationDefinitionCompiler
import dev.tramai.engine.planning.OperationFingerprintFactory
import dev.tramai.engine.planning.ServiceDefinitionCompiler
import dev.tramai.engine.memory.ConversationMemoryCoordinator
import dev.tramai.engine.cache.OperationCacheCoordinator
import dev.tramai.engine.cache.OperationCacheLookupResult
import dev.tramai.engine.cache.OperationCacheKeyRequest
import dev.tramai.engine.cache.OperationCacheLookupRequest
import dev.tramai.engine.cache.OperationCacheStoreRequest
import dev.tramai.core.memory.ChatMemory
import dev.tramai.engine.memory.PersistConversationTurnRequest
import dev.tramai.engine.memory.PersistStructuredConversationTurnRequest
import dev.tramai.engine.memory.PreparedConversationMessages
import dev.tramai.engine.provider.ProviderCallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Direct contract tests for [StructuredResponseCoordinator].
 *
 * The structured path is a security-sensitive orchestration: retry repair,
 * privileged diagnostics, redacted ordinary observations, safe exceptions,
 * cache/memory side effects and approval-resume semantics must survive the
 * extraction byte-for-byte. These tests drive the coordinator through
 * recording doubles so ordering is observable.
 */
class StructuredResponseCoordinatorTest {

    @AiService
    private interface StructuredService {
        @Operation(prompt = "Answer", model = "logical-model", providerRetries = 0)
        suspend fun answer(input: String): StructuredValue

        @Operation(prompt = "Answer", model = "logical-model", cacheable = true, providerRetries = 0)
        suspend fun cached(input: String): StructuredValue

        @Operation(prompt = "Answer", model = "logical-model", providerRetries = 0, maxRetries = 2)
        suspend fun repairable(input: String): StructuredValue
    }

    private data class StructuredValue(val value: String)

    private fun operation(name: String) = ServiceDefinitionCompiler(
        OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory()),
    ).compile(StructuredService::class).operations
        .entries.first { it.key.name == name }.value.definition

    private val identity = EngineExecutionIdentity(
        workflowRunId = "run-1",
        correlationId = "",
        workflowDigest = Sha256Digest.of("sha256:" + "a".repeat(64)),
        policyVersion = "v1",
        actorId = "actor",
    )

    private fun response(content: String) = ModelResponse(content = content)

    private class OrderedSink {
        val events = mutableListOf<String>()
        fun record(name: String) {
            events += name
        }
    }

    private class RecordingObservation(private val sink: OrderedSink? = null) : OperationObservation {
        val completions = mutableListOf<Boolean?>()
        val parseFailures = mutableListOf<String>()
        val engineEvents = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun onProviderResponse(response: ModelResponse) = Unit
        override fun onProviderFailure(error: Throwable) = Unit
        override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) {
            parseFailures += rawResponse
        }
        override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
            engineEvents += name to attributes
        }
        override fun onCallCompleted(parseSuccess: Boolean?) {
            sink?.record("observation.complete:$parseSuccess")
            completions += parseSuccess
        }
    }

    private class RecordingHandler(
        private val results: MutableList<StructuredOutputResult>,
        private val analyzeThrows: Throwable? = null,
        private val contractThrows: Throwable? = null,
    ) : StructuredOutputHandler {
        val analyzeCalls = AtomicInteger(0)
        override fun createContract(targetType: kotlin.reflect.KType): StructuredOutputContract {
            contractThrows?.let { throw it }
            return StructuredOutputContract(targetType, """{"type":"object"}""")
        }
        override fun analyze(rawResponse: String, targetType: kotlin.reflect.KType): StructuredOutputResult {
            analyzeThrows?.let { throw it }
            val next = results.removeAt(0)
            analyzeCalls.incrementAndGet()
            return next
        }
        override fun generateSchema(type: kotlin.reflect.KType) = "{}"
        override fun deserialize(input: Any, targetType: kotlin.reflect.KType): Any = error("unused")
        override fun serialize(value: Any): Any = value
    }

    private fun success(value: Any = StructuredValue("ok")) =
        StructuredOutputResult.Success(value, "raw-ok")

    private fun failure(feedback: String = "fix it", failure: Throwable? = null): StructuredOutputResult.Failure =
        StructuredOutputResult.Failure("raw-bad", "compat summary", feedback).also { it.failure = failure }

    private class RecordingAttemptExecutor(private val sink: OrderedSink? = null) : StructuredAttemptExecutor {
        val calls = mutableListOf<StructuredAttemptExecutionRequest>()
        /** Snapshot of [StructuredAttemptExecutionRequest.messages] at invocation time —
         *  the shared list is mutated between attempts, so a post-hoc look at
         *  [calls] would observe the FINAL list, not the attempt-time one. */
        val messageSnapshots = mutableListOf<List<Message>>()
        var result: ProviderCallResult = ProviderCallResult(
            response = ModelResponse(content = "raw-ok"),
            observation = RecordingObservation(sink),
            providerId = "p1",
            modelName = "logical-model",
            approvedModel = null,
        )
        override suspend fun execute(request: StructuredAttemptExecutionRequest): ProviderCallResult {
            calls += request
            messageSnapshots += request.messages.toList()
            return result
        }
    }

    private open class RecordingDiagnostics : StructuredOutputFailureDiagnosticObserver {
        val events = mutableListOf<StructuredOutputFailureDiagnosticEvent>()
        var throwOnFailure: Throwable? = null
        override suspend fun onFailure(event: StructuredOutputFailureDiagnosticEvent) {
            throwOnFailure?.let { throw it }
            events += event
        }
    }

    private class RecordingMemory(private val sink: OrderedSink? = null) : ChatMemory {
        val stored = mutableListOf<Pair<String, List<Message>>>()
        var history: List<Message> = emptyList()
        override fun get(conversationId: String): List<Message> = history
        override fun add(conversationId: String, messages: List<Message>) {
            sink?.record("memory.persist")
            stored += conversationId to messages
        }
        override fun add(conversationId: String, message: Message) = add(conversationId, listOf(message))
        override fun clear(conversationId: String) = Unit
    }

    private class RecordingCache(private val sink: OrderedSink? = null) : OperationResponseCache {
        val stored = mutableListOf<Pair<OperationCacheKey, CachedOperationResult>>()
        val invalidated = mutableListOf<OperationCacheKey>()
        var hit: CachedOperationResult? = null
        override fun get(key: OperationCacheKey): CachedOperationResult? = hit
        override fun put(key: OperationCacheKey, value: CachedOperationResult, ttlMillis: Long) {
            sink?.record("cache.store")
            stored += key to value
        }
        override fun invalidate(key: OperationCacheKey) {
            invalidated += key
        }
    }

    private class RecordingPolicyEngine(
        private val sink: OrderedSink? = null,
        private val denyAt: dev.tramai.core.policy.EnforcementPoint? = null,
        private val cancelAt: dev.tramai.core.policy.EnforcementPoint? = null,
        private val cancel: CancellationException = CancellationException("policy-cancel"),
    ) : PolicyEngine {
        val evaluated = mutableListOf<String>()
        override suspend fun evaluate(context: dev.tramai.core.policy.PolicyContext): PolicyDecision {
            sink?.record("policy.${context.enforcementPoint.name}")
            evaluated += context.enforcementPoint.name
            if (cancelAt == context.enforcementPoint) throw cancel
            return if (denyAt == context.enforcementPoint) {
                PolicyDecision.Deny(reason = "denied", reasonCode = "TEST")
            } else {
                PolicyDecision.Allow
            }
        }
    }

    private fun coordinator(
        handler: StructuredOutputHandler,
        attemptExecutor: RecordingAttemptExecutor = RecordingAttemptExecutor(),
        diagnostics: RecordingDiagnostics = RecordingDiagnostics(),
        memory: RecordingMemory = RecordingMemory(),
        cache: RecordingCache = RecordingCache(),
        policyEngine: RecordingPolicyEngine = RecordingPolicyEngine(),
    ) = StructuredResponseCoordinator(
        structuredOutputHandler = handler,
        structuredOutputFailureDiagnosticObserver = diagnostics,
        conversationMemoryCoordinator = ConversationMemoryCoordinator(memory, dev.tramai.core.memory.ConversationIdProvider { "gen" }),
        operationCacheCoordinator = OperationCacheCoordinator(
            responseCache = cache,
            operationInterceptor = NoOpOperationInterceptor,
            dlpInterceptor = NoOpDlpInterceptor,
            modelRegistrySettings = ModelRegistrySettings(enabled = false),
            modelRegistryEnforcer = ModelRegistryEnforcer(
                object : dev.tramai.core.model.ModelRegistry {
                    override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? = null
                },
                ModelRegistrySettings(enabled = false),
            ),
            policyHelper = PolicyEnforcementHelper(policyEngine, AtomicBoolean(false)),
        ),
        policyHelper = PolicyEnforcementHelper(policyEngine, AtomicBoolean(false)),
        attemptExecutor = attemptExecutor,
        serviceTypeName = "dev.tramai.engine.structured.StructuredService",
    )

    private fun executeRequest(
        op: dev.tramai.engine.OperationDefinition,
        conversationId: String? = null,
    ) = StructuredResponseRequest(
        operation = op,
        arguments = listOf("input"),
        tokenBudgetTracker = dev.tramai.engine.budget.TokenBudgetTracker(dev.tramai.engine.TokenBudgetSettings()),
        conversationId = conversationId,
        identity = identity,
        operationFingerprint = "fp",
    )

    private fun resumedRequest(
        op: dev.tramai.engine.OperationDefinition,
        loopResult: ProviderCallResult = ProviderCallResult(
            response = ModelResponse(content = "raw-ok"),
            observation = RecordingObservation(),
            providerId = "p1",
            modelName = "logical-model",
            approvedModel = null,
        ),
        messages: List<Message> = listOf(Message(MessageRole.USER, "input")),
        conversationId: String? = null,
    ) = ResumedStructuredResponseRequest(
        operation = op,
        loopResult = loopResult,
        messages = messages,
        correlationId = "cid",
        securityContext = ExecutionSecurityContext(),
        conversationId = conversationId,
        historySize = 0,
    )

    // ------------------------------------------------------------------
    // contract / cache short-circuit
    // ------------------------------------------------------------------

    @Test
    fun `contract failure short-circuits without provider call`() = runTest {
        val handler = RecordingHandler(mutableListOf<StructuredOutputResult>(), contractThrows = IllegalStateException("schema boom"))
        val executor = RecordingAttemptExecutor()
        val diagnostics = RecordingDiagnostics()
        val c = coordinator(handler, attemptExecutor = executor, diagnostics = diagnostics)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.execute(executeRequest(operation("answer"))) } }
            .isInstanceOf(StructuredOutputException::class.java)
        assertThat(executor.calls).isEmpty()
        assertThat(diagnostics.events.single().code).isEqualTo(StructuredOutputFailureCode.CONTRACT_FAILED)
    }

    @Test
    fun `cache hit short-circuits attempt executor`() = runTest {
        val handler = RecordingHandler(mutableListOf<StructuredOutputResult>())
        val executor = RecordingAttemptExecutor()
        val cache = RecordingCache()
        cache.hit = CachedOperationResult(
            value = StructuredValue("cached"),
            provenance = dev.tramai.engine.CachedResponseProvenance("p1", "logical-model", null, null),
        )
        val c = coordinator(handler, attemptExecutor = executor, cache = cache)

        val result = c.execute(executeRequest(operation("cached")))

        assertThat(result).isEqualTo(StructuredValue("cached"))
        assertThat(executor.calls).isEmpty()
    }

    // ------------------------------------------------------------------
    // first-attempt success ordering
    // ------------------------------------------------------------------

    @Test
    fun `first attempt success with conversation enforces policy before completion before memory persist`() = runTest {
        val op = operation("answer")
        val handler = RecordingHandler(mutableListOf(success()))
        val sink = OrderedSink()
        val executor = RecordingAttemptExecutor(sink)
        executor.result = ProviderCallResult(response("raw-ok"), RecordingObservation(sink), "p1", "logical-model", null)
        val memory = RecordingMemory(sink)
        val cache = RecordingCache(sink)
        val policy = RecordingPolicyEngine(sink)
        val c = coordinator(handler, executor, memory = memory, cache = cache, policyEngine = policy)

        val result = c.execute(executeRequest(op, conversationId = "cid"))

        assertThat(result).isEqualTo(StructuredValue("ok"))
        assertThat(sink.events).containsExactly(
            "policy.BEFORE_RESPONSE_RETURN",
            "observation.complete:true",
            "memory.persist",
        )
    }

    @Test
    fun `first attempt success without conversation stores cache after completion`() = runTest {
        val op = operation("cached")
        val handler = RecordingHandler(mutableListOf(success()))
        val sink = OrderedSink()
        val executor = RecordingAttemptExecutor(sink)
        executor.result = ProviderCallResult(response("raw-ok"), RecordingObservation(sink), "p1", "logical-model", null)
        val memory = RecordingMemory(sink)
        val cache = RecordingCache(sink)
        val policy = RecordingPolicyEngine(sink)
        val c = coordinator(handler, executor, memory = memory, cache = cache, policyEngine = policy)

        val result = c.execute(executeRequest(op))

        assertThat(result).isEqualTo(StructuredValue("ok"))
        assertThat(sink.events).containsExactly(
            "policy.BEFORE_RESPONSE_RETURN",
            "observation.complete:true",
            "cache.store",
        )
    }

    @Test
    fun `completion true is recorded before memory persistence`() = runTest {
        val op = operation("answer")
        val handler = RecordingHandler(mutableListOf(success()))
        val sink = OrderedSink()
        val executor = RecordingAttemptExecutor(sink)
        executor.result = ProviderCallResult(response("raw-ok"), RecordingObservation(sink), "p1", "logical-model", null)
        val memory = RecordingMemory(sink)
        val cache = RecordingCache(sink)
        val policy = RecordingPolicyEngine(sink)
        val c = coordinator(handler, executor, memory = memory, cache = cache, policyEngine = policy)

        c.execute(executeRequest(op, conversationId = "cid"))

        // completion true happens first; memory persistence follows (order frozen, not "fixed")
        assertThat(sink.events).containsExactly(
            "policy.BEFORE_RESPONSE_RETURN",
            "observation.complete:true",
            "memory.persist",
        )
        assertThat(cache.stored).isEmpty()
    }

    // ------------------------------------------------------------------
    // repair path
    // ------------------------------------------------------------------

    @Test
    fun `repairable failure appends raw assistant and feedback then retries`() = runTest {
        val op = operation("repairable")
        val handler = RecordingHandler(mutableListOf(failure(feedback = "fix it"), success()))
        val executor = RecordingAttemptExecutor()
        val c = coordinator(handler, executor)

        val result = c.execute(executeRequest(op))

        assertThat(result).isEqualTo(StructuredValue("ok"))
        assertThat(handler.analyzeCalls.get()).isEqualTo(2)
        // repair messages are snapshotted at attempt time: attempt 1 has none,
        // attempt 2 sees raw assistant + user repair feedback appended
        assertThat(executor.messageSnapshots).hasSize(2)
        assertThat(executor.messageSnapshots[0].takeLast(2)).doesNotContain(
            Message(MessageRole.ASSISTANT, "raw-bad"),
            Message(MessageRole.USER, "fix it"),
        )
        assertThat(executor.messageSnapshots[1].takeLast(2)).containsExactly(
            Message(MessageRole.ASSISTANT, "raw-bad"),
            Message(MessageRole.USER, "fix it"),
        )
    }

    @Test
    fun `repair exhaustion throws REPAIR_EXHAUSTED with exact count`() = runTest {
        val op = operation("repairable") // maxRetries = 2 → 3 attempts
        val handler = RecordingHandler(mutableListOf(failure(), failure(), failure()))
        val executor = RecordingAttemptExecutor()
        val c = coordinator(handler, executor)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.execute(executeRequest(op)) } }
            .isInstanceOfSatisfying(StructuredOutputException::class.java) { e ->
                assertThat(e.failureCode).isEqualTo(StructuredOutputFailureCode.REPAIR_EXHAUSTED)
                assertThat(e.attemptCount).isEqualTo(3)
            }
        assertThat(executor.calls).hasSize(3)
    }

    @Test
    fun `handler throw is sanitized as HANDLER_FAILED`() = runTest {
        val op = operation("answer")
        val handler = RecordingHandler(mutableListOf<StructuredOutputResult>(), analyzeThrows = IllegalStateException("handler secret"))
        val executor = RecordingAttemptExecutor()
        val diagnostics = RecordingDiagnostics()
        val c = coordinator(handler, executor, diagnostics = diagnostics)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.execute(executeRequest(op)) } }
            .isInstanceOfSatisfying(StructuredOutputException::class.java) { e ->
                assertThat(e.failureCode).isEqualTo(StructuredOutputFailureCode.HANDLER_FAILED)
            }
        assertThat(diagnostics.events.single().code).isEqualTo(StructuredOutputFailureCode.HANDLER_FAILED)
    }

    @Test
    fun `diagnostic observer throw is fail-open`() = runTest {
        val op = operation("answer")
        val handler = RecordingHandler(mutableListOf(failure()))
        val executor = RecordingAttemptExecutor()
        val diagnostics = RecordingDiagnostics().also { it.throwOnFailure = IllegalStateException("observer boom") }
        val c = coordinator(handler, executor, diagnostics = diagnostics)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.execute(executeRequest(op)) } }
            .isInstanceOf(StructuredOutputException::class.java)
            .isNotSameAs(diagnostics.throwOnFailure)
    }

    @Test
    fun `real parent cancellation during diagnostics stays primary`() = runTest {
        val op = operation("answer")
        val handler = RecordingHandler(mutableListOf(failure()))
        val executor = RecordingAttemptExecutor()
        val c = coordinator(handler, executor)

        val observerEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val observerRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
        val diagnostics = object : RecordingDiagnostics() {
            override suspend fun onFailure(event: StructuredOutputFailureDiagnosticEvent) {
                // Block inside diagnostic delivery so the parent can cancel mid-delivery
                observerEntered.complete(Unit)
                observerRelease.await()
            }
        }
        val c2 = StructuredResponseCoordinator(
            structuredOutputHandler = handler,
            structuredOutputFailureDiagnosticObserver = diagnostics,
            conversationMemoryCoordinator = ConversationMemoryCoordinator(RecordingMemory(), dev.tramai.core.memory.ConversationIdProvider { "gen" }),
            operationCacheCoordinator = OperationCacheCoordinator(
                responseCache = RecordingCache(),
                operationInterceptor = NoOpOperationInterceptor,
                dlpInterceptor = NoOpDlpInterceptor,
                modelRegistrySettings = ModelRegistrySettings(enabled = false),
                modelRegistryEnforcer = ModelRegistryEnforcer(
                    object : dev.tramai.core.model.ModelRegistry {
                        override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? = null
                    },
                    ModelRegistrySettings(enabled = false),
                ),
                policyHelper = PolicyEnforcementHelper(RecordingPolicyEngine(), AtomicBoolean(false)),
            ),
            policyHelper = PolicyEnforcementHelper(RecordingPolicyEngine(), AtomicBoolean(false)),
            attemptExecutor = executor,
            serviceTypeName = "dev.tramai.engine.structured.StructuredService",
        )

        val outcome = kotlinx.coroutines.CompletableDeferred<Throwable?>()
        kotlinx.coroutines.coroutineScope {
            val executionJob = launch {
                try {
                    c2.execute(executeRequest(op))
                    outcome.complete(null)
                } catch (t: Throwable) {
                    outcome.complete(t)
                }
            }
            observerEntered.await()
            // Genuine parent cancellation while the diagnostic is being delivered
            executionJob.cancel(CancellationException("parent-cancel"))
            observerRelease.complete(Unit)
            val terminal = outcome.await()
            assertThat(terminal).isInstanceOf(CancellationException::class.java)
            assertThat(terminal).isNotInstanceOf(StructuredOutputException::class.java)
        }
    }

    // ------------------------------------------------------------------
    // policy deny on success → no side effects
    // ------------------------------------------------------------------

    @Test
    fun `BEFORE_RESPONSE_RETURN deny with conversation prevents memory persistence`() = runTest {
        val op = operation("answer")
        val handler = RecordingHandler(mutableListOf(success()))
        val sink = OrderedSink()
        val executor = RecordingAttemptExecutor(sink)
        val memory = RecordingMemory(sink)
        val cache = RecordingCache(sink)
        val policy = RecordingPolicyEngine(sink, denyAt = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN)
        val c = coordinator(handler, executor, memory = memory, cache = cache, policyEngine = policy)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.execute(executeRequest(op, conversationId = "cid")) } }
            .isInstanceOf(dev.tramai.core.exception.PolicyViolationException::class.java)
        assertThat(memory.stored).isEmpty()
        assertThat(sink.events).containsExactly("policy.BEFORE_RESPONSE_RETURN")
    }

    @Test
    fun `BEFORE_RESPONSE_RETURN deny without conversation prevents cache store`() = runTest {
        val op = operation("cached")
        val handler = RecordingHandler(mutableListOf(success()))
        val sink = OrderedSink()
        val executor = RecordingAttemptExecutor(sink)
        val memory = RecordingMemory(sink)
        val cache = RecordingCache(sink)
        val policy = RecordingPolicyEngine(sink, denyAt = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN)
        val c = coordinator(handler, executor, memory = memory, cache = cache, policyEngine = policy)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.execute(executeRequest(op)) } }
            .isInstanceOf(dev.tramai.core.exception.PolicyViolationException::class.java)
        assertThat(cache.stored).isEmpty()
        assertThat(sink.events).containsExactly("policy.BEFORE_RESPONSE_RETURN")
    }

    // ------------------------------------------------------------------
    // resumed path
    // ------------------------------------------------------------------

    @Test
    fun `resumed success is single attempt with policy memory and observation`() = runTest {
        val op = operation("answer")
        val handler = RecordingHandler(mutableListOf(success()))
        val sink = OrderedSink()
        val executor = RecordingAttemptExecutor(sink)
        val obs = RecordingObservation(sink)
        val memory = RecordingMemory(sink)
        val cache = RecordingCache(sink)
        val policy = RecordingPolicyEngine(sink)
        val c = coordinator(handler, executor, memory = memory, cache = cache, policyEngine = policy)

        val result = c.finalizeResumed(
            resumedRequest(
                op,
                loopResult = ProviderCallResult(response("raw-ok"), obs, "p1", "logical-model", null),
                conversationId = "cid",
            ),
        )

        assertThat(result).isEqualTo(StructuredValue("ok"))
        assertThat(handler.analyzeCalls.get()).isEqualTo(1)
        assertThat(executor.calls).isEmpty() // no attempt executor on resume
        // resumed ordering deliberately differs from ordinary path: policy → memory → complete
        assertThat(sink.events).containsExactly(
            "policy.BEFORE_RESPONSE_RETURN",
            "memory.persist",
            "observation.complete:true",
        )
        assertThat(memory.stored.single().first).isEqualTo("cid")
    }

    @Test
    fun `resumed invalid response has no policy memory or cache side effects`() = runTest {
        val op = operation("answer")
        val handler = RecordingHandler(mutableListOf(failure()))
        val executor = RecordingAttemptExecutor()
        val memory = RecordingMemory()
        val cache = RecordingCache()
        val policy = RecordingPolicyEngine()
        val c = coordinator(handler, executor, memory = memory, cache = cache, policyEngine = policy)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.finalizeResumed(resumedRequest(op, conversationId = "cid")) } }
            .isInstanceOfSatisfying(StructuredOutputException::class.java) { e ->
                assertThat(e.failureCode).isEqualTo(StructuredOutputFailureCode.OUTPUT_REJECTED)
                assertThat(e.attemptCount).isEqualTo(1)
            }
        assertThat(policy.evaluated).isEmpty() // NO BEFORE_RESPONSE_RETURN on failure
        assertThat(memory.stored).isEmpty()
        assertThat(cache.stored).isEmpty()
    }

    @Test
    fun `resumed handler failure is HANDLER_FAILED`() = runTest {
        val op = operation("answer")
        val handler = RecordingHandler(mutableListOf<StructuredOutputResult>(), analyzeThrows = IllegalStateException("resume secret"))
        val executor = RecordingAttemptExecutor()
        val diagnostics = RecordingDiagnostics()
        val c = coordinator(handler, executor, diagnostics = diagnostics)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.finalizeResumed(resumedRequest(op)) } }
            .isInstanceOfSatisfying(StructuredOutputException::class.java) { e ->
                assertThat(e.failureCode).isEqualTo(StructuredOutputFailureCode.HANDLER_FAILED)
            }
        assertThat(diagnostics.events.single().code).isEqualTo(StructuredOutputFailureCode.HANDLER_FAILED)
    }

    @Test
    fun `resumed path never retries`() = runTest {
        val op = operation("repairable")
        val handler = RecordingHandler(mutableListOf(failure(), success()))
        val executor = RecordingAttemptExecutor()
        val c = coordinator(handler, executor)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.finalizeResumed(resumedRequest(op)) } }
            .isInstanceOf(StructuredOutputException::class.java)
        assertThat(handler.analyzeCalls.get()).isEqualTo(1) // exactly one parse, no repair
    }

    @Test
    fun `missing structured handler throws configuration error`() = runTest {
        val op = operation("answer")
        val c = StructuredResponseCoordinator(
            structuredOutputHandler = null,
            structuredOutputFailureDiagnosticObserver = NoOpStructuredOutputFailureDiagnosticObserver,
            conversationMemoryCoordinator = ConversationMemoryCoordinator(null, dev.tramai.core.memory.ConversationIdProvider { "gen" }),
            operationCacheCoordinator = OperationCacheCoordinator(
                responseCache = RecordingCache(),
                operationInterceptor = NoOpOperationInterceptor,
                dlpInterceptor = NoOpDlpInterceptor,
                modelRegistrySettings = ModelRegistrySettings(enabled = false),
                modelRegistryEnforcer = ModelRegistryEnforcer(
                    object : dev.tramai.core.model.ModelRegistry {
                        override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? = null
                    },
                    ModelRegistrySettings(enabled = false),
                ),
                policyHelper = PolicyEnforcementHelper(RecordingPolicyEngine(), AtomicBoolean(false)),
            ),
            policyHelper = PolicyEnforcementHelper(RecordingPolicyEngine(), AtomicBoolean(false)),
            attemptExecutor = RecordingAttemptExecutor(),
            serviceTypeName = "dev.tramai.engine.structured.StructuredService",
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.execute(executeRequest(op)) } }
            .isInstanceOf(ConfigurationException::class.java)
    }
}
