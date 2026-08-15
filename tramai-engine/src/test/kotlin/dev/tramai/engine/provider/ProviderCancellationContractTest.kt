package dev.tramai.engine.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.RetryPolicySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mandatory cancellation-contract tests (Epic 3.3 Task 11).
 *
 * Prove that CancellationException NEVER reaches retry classification, fallback
 * classification, or circuit-breaker failure recording — regardless of where in
 * the attempt lifecycle it is thrown. Uses counting doubles for the three policy
 * seams so the assertions are mutation-sensitive: if the executor or coordinator
 * is ever refactored to route cancellation into retry/fallback/CB handling,
 * these counters fail.
 */
class ProviderCancellationContractTest {

    private class CountingRetryPolicy : ProviderRetryPolicy(
        ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.0)) { 0.0 },
    ) {
        val decideCalls = AtomicInteger()
        override fun decide(error: Throwable, retryIndex: Int, maxAttempts: Int): ProviderRetryDecision {
            decideCalls.incrementAndGet()
            return super.decide(error, retryIndex, maxAttempts)
        }
    }

    private class CountingFallbackPolicy : ProviderFallbackPolicy() {
        val decideCalls = AtomicInteger()
        override fun decide(error: Throwable): ProviderFallbackDecision {
            decideCalls.incrementAndGet()
            return super.decide(error)
        }
    }

    private class CountingCircuitBreaker : ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 60_000)) {
        val failureCalls = AtomicInteger()
        override fun onFailure(providerId: String, error: Throwable): Boolean {
            failureCalls.incrementAndGet()
            return super.onFailure(providerId, error)
        }
    }

    @Test
    fun `provider cancellation reaches no policy and completes no observation`() {
        val observation = RecordingObservation()
        val retryPolicy = CountingRetryPolicy()
        val circuitBreaker = CountingCircuitBreaker()
        var calls = 0

        assertThatThrownBy {
            runBlocking {
                executor(
                    observation = observation,
                    retryPolicy = retryPolicy,
                    circuitBreaker = circuitBreaker,
                ).execute(request(FakeProvider { calls++; throw CancellationException("stop") }, retries = 1))
            }
        }.isInstanceOf(CancellationException::class.java)

        assertThat(calls).isEqualTo(1)
        assertThat(retryPolicy.decideCalls.get()).isZero()
        assertThat(circuitBreaker.failureCalls.get()).isZero()
        assertThat(observation.cancelled).isEqualTo(1)
        assertThat(observation.completed).isZero()
        assertThat(observation.failures).isZero()
    }

    @Test
    fun `cancellation during retry delay prevents second invocation and fallback`() {
        runBlocking {
        val fallbackPolicy = CountingFallbackPolicy()
        var calls = 0
        // Primary always fails retryably; coordinator plan routes "model" to it.
        val coordinator = coordinator(
            primary = FakeProvider { calls++; throw ProviderException("transient", retryable = true) },
            fallbackPolicy = fallbackPolicy,
        )
        val job = launch {
            coordinator.execute(
                ProviderExecutionRequest(
                    operation = componentOperation(1),
                    messages = emptyList(),
                    attemptCounter = AttemptCounter(),
                    correlationId = "cid",
                    securityContext = ExecutionSecurityContext(),
                    beforeRoute = ProviderRouteGate {},
                ),
            )
        }
        // Let the first attempt fail and enter the retry delay (50ms backoff + 0 jitter).
        withTimeout(2_000) { while (calls < 1) kotlinx.coroutines.yield() }
        job.cancelAndJoin()
        assertThat(calls).isEqualTo(1)
        assertThat(fallbackPolicy.decideCalls.get()).isZero()
        }
    }

    @Test
    fun `authorization cancellation cancels observation and invokes no provider or policies`() {
        var calls = 0
        val observation = RecordingObservation()
        val retryPolicy = CountingRetryPolicy()
        val fallbackPolicy = CountingFallbackPolicy()
        val cancellation = CancellationException("auth")
        val attemptExecutor = executor(
            observation = observation,
            retryPolicy = retryPolicy,
            authorization = authorization { throw cancellation },
        )

        assertThatThrownBy {
            runBlocking {
                coordinator(
                    primary = FakeProvider { calls++; ModelResponse("never") },
                    attemptExecutor = attemptExecutor,
                    fallbackPolicy = fallbackPolicy,
                ).execute(
                    ProviderExecutionRequest(
                        operation = componentOperation(0),
                        messages = emptyList(),
                        attemptCounter = AttemptCounter(),
                        correlationId = "cid",
                        securityContext = ExecutionSecurityContext(),
                        beforeRoute = ProviderRouteGate {},
                    ),
                )
            }
        }.isSameAs(cancellation)

        assertThat(calls).isZero()
        assertThat(retryPolicy.decideCalls.get()).isZero()
        assertThat(fallbackPolicy.decideCalls.get()).isZero()
        assertThat(observation.cancelled).isEqualTo(1)
        assertThat(observation.completed).isZero()
        assertThat(observation.failures).isZero()
    }

    private fun coordinator(
        primary: FakeProvider,
        attemptExecutor: ProviderAttemptExecutor = executor(),
        fallbackPolicy: ProviderFallbackPolicy = ProviderFallbackPolicy(),
    ) = ProviderExecutionCoordinator(
        routingPlan = ProviderRoutingPlan.builder()
            .provider("primary", primary)
            .model("model", "primary")
            .build(),
        circuitBreaker = ProviderCircuitBreaker(CircuitBreakerSettings()),
        attemptExecutor = attemptExecutor,
        fallbackPolicy = fallbackPolicy,
        beforeResolution = ProviderResolutionGate { _, _, _ -> },
        fallbackGate = ProviderFallbackGate { _, _, _, _, _, _ -> },
    )
}
