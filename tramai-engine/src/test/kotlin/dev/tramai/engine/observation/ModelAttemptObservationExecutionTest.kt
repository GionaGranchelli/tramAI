package dev.tramai.engine.observation

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.observation.attempt.AttemptHistory
import dev.tramai.core.observation.attempt.AttemptOutcome
import dev.tramai.core.observation.attempt.AttemptProvenance
import dev.tramai.core.observation.attempt.AttemptRecordingObserver
import dev.tramai.core.observation.attempt.InMemoryAttemptObservationStore
import dev.tramai.core.observation.attempt.ModelAttemptObservation
import dev.tramai.core.observation.attempt.UninstrumentedAttemptObservationStore
import dev.tramai.engine.characterization.ExecutionTraceFixture
import dev.tramai.engine.characterization.TraceService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * Epic 0.7.5b — a real non-streaming provider execution must produce durable, correctly attributed
 * attempt observations.
 *
 * These tests drive the actual engine through the characterization fixture: the provider really is
 * invoked, really fails and is really retried. Nothing here calls the observer directly.
 */
class ModelAttemptObservationExecutionTest {
    /** Advances on every read so duration is derived from the clock, not hardcoded. */
    private class TickingClock : Clock() {
        private val tick = AtomicLong()

        override fun instant(): Instant = Instant.parse("2026-02-01T00:00:00Z").plusMillis(tick.addAndGet(250))

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    /** Records the identity the engine bridged into the observer, so assertions never guess a UUID. */
    private class IdentityCapturingObserver : OperationObserver {
        val contexts = mutableListOf<OperationCallContext>()

        override fun onCallStarted(context: OperationCallContext): OperationObservation {
            contexts += context
            return object : OperationObservation {
                override fun onProviderResponse(response: ModelResponse) = Unit

                override fun onProviderFailure(error: Throwable) = Unit

                override fun onStructuredParseFailure(
                    rawResponse: String,
                    errorSummary: String,
                ) = Unit

                override fun onCallCompleted(parseSuccess: Boolean?) = Unit
            }
        }
    }

    private fun fixtureWith(
        store: InMemoryAttemptObservationStore,
        captor: IdentityCapturingObserver,
        configure: ExecutionTraceFixture.() -> Unit = {},
    ): ExecutionTraceFixture {
        val fixture = ExecutionTraceFixture()
        fixture.observerOverride =
            AttemptRecordingObserver(store, delegate = captor, clock = TickingClock())
        fixture.configure()
        return fixture
    }

    private fun attempts(history: AttemptHistory): List<ModelAttemptObservation> =
        when (history) {
            is AttemptHistory.Available -> history.attempts
            AttemptHistory.Uninstrumented -> error("expected observed history, got Uninstrumented")
        }

    private fun invocationIds(captor: IdentityCapturingObserver): List<String> {
        val ids = captor.contexts.mapNotNull { it.identity.invocationId }
        return ids
    }

    @Test
    fun `a successful provider call persists exactly one attempt with identity, outcome and model`() {
        val store = InMemoryAttemptObservationStore()
        val captor = IdentityCapturingObserver()
        val fixture = fixtureWith(store, captor)

        assertThat(runBlocking { fixture.engine().create(TraceService::class).answer("hello") })
            .isEqualTo("answer")

        val invocationIds = invocationIds(captor)
        assertThat(invocationIds).hasSize(1)

        val attempt = attempts(store.findByInvocation(invocationIds.single())).single()
        assertThat(attempt.attempt).isZero()
        assertThat(attempt.outcome).isEqualTo(AttemptOutcome.SUCCEEDED)
        assertThat(attempt.provenance).isEqualTo(AttemptProvenance.OBSERVED_IN_PROCESS)
        assertThat(attempt.providerId).isEqualTo("primary")
        assertThat(attempt.requestedModel).isEqualTo("logical-model")
        assertThat(attempt.reportedModel).isNotNull()
        assertThat(attempt.routeIndex).isZero()
        assertThat(attempt.correlationId).isNotNull()
        assertThat(attempt.durationMillis).isEqualTo(250L)
    }

    @Test
    fun `retry followed by success persists two distinct attempts of one invocation`() {
        val store = InMemoryAttemptObservationStore()
        val captor = IdentityCapturingObserver()
        val fixture = fixtureWith(store, captor) { providerFailures = 1 }

        assertThat(runBlocking { fixture.engine().create(TraceService::class).retryOnce("input") })
            .isEqualTo("answer")

        val invocationIds = invocationIds(captor)
        assertThat(invocationIds).hasSize(2)
        assertThat(invocationIds.toSet()).hasSize(1)

        val recorded = attempts(store.findByInvocation(invocationIds.first()))
        assertThat(recorded.map { it.attempt }).containsExactly(0, 1)
        assertThat(recorded.map { it.outcome })
            .containsExactly(AttemptOutcome.FAILED, AttemptOutcome.SUCCEEDED)
        assertThat(recorded.first().failureCategory).isEqualTo("ProviderException")
        assertThat(recorded.last().failureCategory).isNull()
    }

    @Test
    fun `repeated invocations never share an attempt identity`() {
        val store = InMemoryAttemptObservationStore()
        val captor = IdentityCapturingObserver()
        val fixture = fixtureWith(store, captor)
        val service = fixture.engine().create(TraceService::class)

        runBlocking {
            service.answer("first")
            service.answer("second")
        }

        val invocationIds = invocationIds(captor)
        assertThat(invocationIds).hasSize(2)
        assertThat(invocationIds.toSet()).hasSize(2)
        assertThat(attempts(store.findByInvocation(invocationIds[0])).map { it.attempt }).containsExactly(0)
        assertThat(attempts(store.findByInvocation(invocationIds[1])).map { it.attempt }).containsExactly(0)
    }

    @Test
    fun `a failed attempt is never recorded as a successful completion`() {
        val store = InMemoryAttemptObservationStore()
        val captor = IdentityCapturingObserver()
        val fixture = fixtureWith(store, captor) { providerFailures = 1 }

        assertThatThrownBy { runBlocking { fixture.engine().create(TraceService::class).answer("input") } }
            .isInstanceOf(ProviderException::class.java)

        val recorded = attempts(store.findByInvocation(invocationIds(captor).single()))
        assertThat(recorded).hasSize(1)
        assertThat(recorded.single().outcome).isEqualTo(AttemptOutcome.FAILED)
        assertThat(recorded.single().reportedModel).isNull()
        assertThat(recorded.single().inputTokens).isNull()
    }

    @Test
    fun `missing usage stays unknown and a default finish reason is not a provider claim`() {
        val store = InMemoryAttemptObservationStore()
        val captor = IdentityCapturingObserver()
        val fixture = fixtureWith(store, captor)
        runBlocking { fixture.engine().create(TraceService::class).answer("input") }

        val recorded = attempts(store.findByInvocation(invocationIds(captor).single())).single()
        assertThat(recorded.inputTokens).isNull()
        assertThat(recorded.outputTokens).isNull()
        assertThat(recorded.thinkingTokens).isNull()
        assertThat(recorded.finishReason).isEqualTo("STOP")
        assertThat(recorded.finishReasonReported).isFalse()
    }

    @Test
    fun `queries expose no prompt text and no secrets`() {
        val store = InMemoryAttemptObservationStore()
        val captor = IdentityCapturingObserver()
        val fixture = fixtureWith(store, captor)
        val canary = "CANARY-SECRET-7f3a91"
        runBlocking { fixture.engine().create(TraceService::class).answer(canary) }

        val recorded = attempts(store.findByInvocation(invocationIds(captor).single())).single()
        assertThat(recorded.toString()).doesNotContain(canary)
        assertThat(listOfNotNull(recorded.providerId, recorded.requestedModel, recorded.reportedModel))
            .noneMatch { it.contains(canary) }
    }

    @Test
    fun `with no observation persistence configured nothing is fabricated`() {
        val store = InMemoryAttemptObservationStore()
        val fixture = ExecutionTraceFixture()
        runBlocking { fixture.engine().create(TraceService::class).answer("input") }

        assertThat(attempts(store.findByWorkflowRun("any-run"))).isEmpty()
        assertThat(store.findByInvocation("any-invocation")).isEqualTo(AttemptHistory.Available(emptyList()))
        assertThat(UninstrumentedAttemptObservationStore.findByInvocation("any-invocation"))
            .isEqualTo(AttemptHistory.Uninstrumented)
    }
}
