package dev.tramai.engine.provider

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.observation.NoOpToolFailureDiagnosticObserver
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.policy.NoOpPolicyDecisionAuditEmitter
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.engine.TramaiEngine
import dev.tramai.engine.components.EngineComponentFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 8.3b1 discriminators: retry jitter randomness lives at exactly one explicit
 * composition boundary. Randomness may modify only the magnitude of an
 * already-authorized retry delay; every retry/fallback decision stays
 * deterministic. No timing thresholds, no sleeps.
 */
class RetryJitterDiscriminatorTest {

    /** Queued source: deterministic nextDouble() sequence, counts SUCCESSFUL samples. */
    private class QueuedJitterSource(vararg samples: Double) : RetryJitterSource {
        private val queue = ArrayDeque(samples.toList())
        var calls = 0
            private set
        override fun nextDouble(): Double {
            val sample = queue.removeFirst()
            calls++
            return sample
        }
    }

    private fun retryableError(retryAfterMillis: Long? = null): ProviderException =
        ProviderException("retryable failure", retryable = true, retryAfterMillis = retryAfterMillis)

    // ── P0-A — exact injected jitter ─────────────────────────────────────────

    @Test
    fun `P0-A exact injected jitter - sample 0-25 gives 105ms`() {
        val policy = ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.20), QueuedJitterSource(0.25))
        assertThat(policy.delayMillis(retryableError(), fallbackDelayMillis = 100L)).isEqualTo(105L)
    }

    @Test
    fun `P0-A exact injected jitter - sample 0-75 gives 115ms`() {
        val policy = ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.20), QueuedJitterSource(0.75))
        assertThat(policy.delayMillis(retryableError(), fallbackDelayMillis = 100L)).isEqualTo(115L)
    }

    // ── P0-B — one sample per retry decision ────────────────────────────────

    @Test
    fun `P0-B one sample per retry decision - decision policy samples once per authorized retry`() {
        val source = QueuedJitterSource(0.25, 0.75)
        val retryPolicy = ProviderRetryPolicy(
            ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.20), source),
        )
        val decision = retryPolicy.decide(retryableError(), retryIndex = 0, maxAttempts = 2)
        // Authorized retry at index 0: fallback base = minOf(50 shl 0, 1000) = 50; 50 + 50*.2*.25 = 52.5 -> 52.
        assertThat((decision as ProviderRetryDecision.Retry).delayMillis).isEqualTo(52L)
        assertThat(source.calls).isEqualTo(1)
    }

    @Test
    fun `P0-B one sample per retry delay - injected sequence is authoritative`() {
        val source = QueuedJitterSource(0.25, 0.75)
        val policy = ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.20), source)
        // Two authorized retries at base 100: 100 + 100*.2*.25 = 105; 100 + 100*.2*.75 = 115.
        assertThat(policy.delayMillis(retryableError(), fallbackDelayMillis = 100L)).isEqualTo(105L)
        assertThat(policy.delayMillis(retryableError(), fallbackDelayMillis = 100L)).isEqualTo(115L)
        assertThat(source.calls).isEqualTo(2)
    }

    // ── P0-C — Stop consumes zero randomness ─────────────────────────────────

    @Test
    fun `P0-C non-retryable error stops without sampling`() {
        val source = QueuedJitterSource(0.25)
        val retryPolicy = ProviderRetryPolicy(
            ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.20), source),
        )
        assertThat(retryPolicy.decide(IllegalStateException("boom"), retryIndex = 0, maxAttempts = 3))
            .isEqualTo(ProviderRetryDecision.Stop)
        assertThat(source.calls).isZero()
    }

    @Test
    fun `P0-C exhausted budget stops without sampling`() {
        val source = QueuedJitterSource(0.25)
        val retryPolicy = ProviderRetryPolicy(
            ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.20), source),
        )
        assertThat(retryPolicy.decide(retryableError(), retryIndex = 1, maxAttempts = 2))
            .isEqualTo(ProviderRetryDecision.Stop)
        assertThat(source.calls).isZero()
    }

    // ── P0-D — retry-after capping precedes jitter ───────────────────────────

    @Test
    fun `P0-D retry-after is capped before jitter is applied`() {
        val policy = ProviderRetryDelayPolicy(
            RetryPolicySettings(jitterRatio = 0.20, maxRetryAfterMillis = 1_000L),
            QueuedJitterSource(0.50),
        )
        // retryAfter 10_000 capped to 1_000, then jittered: 1000 + 1000*.2*.5 = 1100.
        assertThat(policy.delayMillis(retryableError(retryAfterMillis = 10_000L), fallbackDelayMillis = 50L))
            .isEqualTo(1_100L)
    }

    // ── P0-F — invalid jitter samples fail closed ────────────────────────────

    @Test
    fun `P0-F invalid jitter sample fails closed`() {
        for (sample in listOf(-0.1, 1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val policy = ProviderRetryDelayPolicy(
                RetryPolicySettings(jitterRatio = 0.20),
                QueuedJitterSource(sample),
            )
            org.assertj.core.api.Assertions.assertThatThrownBy {
                policy.delayMillis(retryableError(), fallbackDelayMillis = 100L)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Retry jitter sample must be in [0.0, 1.0)")
        }
    }

    // ── P0-E — whole-engine composition consumes the injected source ─────────

    @AiService
    private interface ExplicitRetryService {
        @Operation(prompt = "Answer", model = "logical-model", providerRetries = 2)
        fun answer(input: String): String
    }

    private class FailingProvider : ModelProvider {
        override fun providerId(): String = "p0"
        override suspend fun complete(request: ModelRequest): ModelResponse {
            throw ProviderException("always retryable", retryable = true, retryAfterMillis = null)
        }
    }

    private class RetryEventCapture : OperationObserver {
        val retryScheduled = CompletableDeferred<Map<String, Any?>>()
        val invocationFailure = CompletableDeferred<Throwable>()
        val delayMillisSeen = mutableListOf<Long>()
        override fun onCallStarted(context: OperationCallContext): OperationObservation =
            RetryScheduledObservation(this)
    }

    private class RetryScheduledObservation(private val capture: RetryEventCapture) : OperationObservation {
        override fun onProviderResponse(response: ModelResponse) = Unit
        override fun onProviderFailure(error: Throwable) = Unit
        override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) = Unit
        override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
            if (name == RuntimeEvents.RETRY_SCHEDULED.name) {
                capture.delayMillisSeen += attributes[RuntimeAttributes.DELAY_MILLIS.name] as Long
                capture.retryScheduled.complete(attributes)
            }
        }
        override fun onCallCompleted(parseSuccess: Boolean?) = Unit
        override fun onCallCancelled() = Unit
    }

    @Test
    fun `P0-E engine composition graph consumes the injected jitter source`() = runBlocking {
        val source = QueuedJitterSource(0.25)
        val capture = RetryEventCapture()
        val registry = ProviderRegistry.singleProvider(FailingProvider())
        val components = EngineComponentFactory.create(
            providerRegistry = registry,
            structuredOutputHandler = null,
            toolRegistry = dev.tramai.engine.ToolRegistry(),
            operationObserver = capture,
            operationInterceptor = object : dev.tramai.core.observation.OperationInterceptor {},
            responseCache = dev.tramai.engine.NoOpOperationResponseCache,
            modelRegistry = dev.tramai.core.model.NoOpModelRegistry,
            modelRegistrySettings = dev.tramai.core.model.ModelRegistrySettings(),
            circuitBreakerSettings = dev.tramai.engine.CircuitBreakerSettings(),
            retryPolicySettings = RetryPolicySettings(jitterRatio = 0.20),
            tokenBudgetSettings = dev.tramai.engine.TokenBudgetSettings(),
            promptSanitizer = null,
            chatMemory = null,
            conversationIdProvider = dev.tramai.core.memory.UuidConversationIdProvider(),
            policyEngine = dev.tramai.core.policy.PolicyEngine { _ -> dev.tramai.core.policy.PolicyDecision.Allow },
            dlpInterceptor = dev.tramai.core.security.NoOpDlpInterceptor,
            dlpRedactionAuditEmitter = dev.tramai.core.security.NoOpDlpRedactionAuditEmitter,
            toolResultFilteringSettings = dev.tramai.engine.ToolResultFilteringSettings(),
            engineEventObserver = dev.tramai.engine.NoOpEngineEventObserver,
            toolFailureDiagnosticObserver = NoOpToolFailureDiagnosticObserver,
            policyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
            suspendedInvocationStore = dev.tramai.engine.InMemorySuspendedInvocationStore(),
            approvalContinuationStore = null,
            toolArgumentsDigester = null,
            approvalGateCoordinator = null,
            approvalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
            clock = java.time.Clock.systemUTC(),
            retryJitterSource = source,
        )
        val engine = TramaiEngine(components)
        val service = engine.create(ExplicitRetryService::class)
        val job = launch {
            try {
                service.answer("hello")
            } catch (t: Throwable) {
                capture.invocationFailure.complete(t)
            }
        }
        val outcome = kotlinx.coroutines.withTimeout(10_000) {
            kotlinx.coroutines.selects.select {
                capture.retryScheduled.onAwait { "retry" to it }
                capture.invocationFailure.onAwait { "failure" to null }
            }
        }
        check(outcome.first == "retry") {
            "expected RETRY_SCHEDULED, got invocation failure " +
                "(completed=${capture.invocationFailure.isCompleted}, timeout=10s)"
        }
        val attributes = outcome.second!!
        // fallback base for retryIndex 0 = minOf(50 shl 0, 1000) = 50; jitter .20, sample .25:
        // 50 + 50*.2*.25 = 52.5 -> 52.
        assertThat(attributes[RuntimeAttributes.DELAY_MILLIS.name]).isEqualTo(52L)

        // Freeze the invocation before asserting entropy consumption: the engine-owned
        // coroutine must be definitively terminated, not merely signalled to cancel.
        // The source carries exactly ONE sample, so no later attempt can mint a second
        // call even if cancellation delivery races the engine's retry delay.
        job.cancelAndJoin()

        assertThat(source.calls).isEqualTo(1)
        engine.close()
    }
}
