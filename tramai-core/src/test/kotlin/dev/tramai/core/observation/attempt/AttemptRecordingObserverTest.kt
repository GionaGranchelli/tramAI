package dev.tramai.core.observation.attempt

import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.AttemptIdentity
import dev.tramai.core.observation.NoOpOperationObservation
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * Epic 0.7.5b — attempt recording at the observer seam: outcome mapping, null retention,
 * single-write semantics, and the guarantee that persistence can never change an execution outcome.
 */
class AttemptRecordingObserverTest {
    private class FixedClock : Clock() {
        val reads = AtomicLong()

        override fun instant(): Instant = Instant.parse("2026-03-01T00:00:00Z").plusMillis(reads.addAndGet(100))

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    private class CountingDelegate : OperationObserver {
        var started = 0
        var responses = 0
        var failures = 0
        var completions = 0
        var cancellations = 0
        var lastObservation: OperationObservation? = null

        override fun onCallStarted(context: OperationCallContext): OperationObservation {
            started++
            val delegate =
                object : OperationObservation {
                    override fun onProviderResponse(response: ModelResponse) {
                        responses++
                    }

                    override fun onProviderFailure(error: Throwable) {
                        failures++
                    }

                    override fun onStructuredParseFailure(
                        rawResponse: String,
                        errorSummary: String,
                    ) = Unit

                    override fun onCallCompleted(parseSuccess: Boolean?) {
                        completions++
                    }

                    override fun onCallCancelled() {
                        cancellations++
                    }
                }
            lastObservation = delegate
            return delegate
        }
    }

    private object ExplodingStore : AttemptObservationStore {
        override fun append(observation: ModelAttemptObservation): AttemptAppendOutcome = error("store unavailable")

        override fun findByWorkflowRun(workflowRunId: String): AttemptHistory = AttemptHistory.Uninstrumented

        override fun findByInvocation(invocationId: String): AttemptHistory = AttemptHistory.Uninstrumented
    }

    private fun context(attempt: Int = 0) =
        OperationCallContext(
            serviceInterface = "com.example.Service",
            methodName = "call",
            providerId = "primary",
            requestedModel = "logical-model",
            attempt = attempt,
        )

    private fun identity(invocationId: String? = "inv-1") =
        AttemptIdentity(
            invocationId = invocationId,
            workflowRunId = "run-1",
            correlationId = "corr-1",
            routeIndex = 0,
        )

    private fun observe(
        observer: AttemptRecordingObserver,
        invocationId: String? = "inv-1",
        attempt: Int = 0,
    ): OperationObservation {
        val callContext = context(attempt)
        return observer.recording(callContext, observer.onCallStarted(callContext), identity(invocationId))
    }

    private fun recorded(store: InMemoryAttemptObservationStore): List<ModelAttemptObservation> =
        when (val history = store.findByInvocation("inv-1")) {
            is AttemptHistory.Available -> history.attempts
            AttemptHistory.Uninstrumented -> error("expected observed history")
        }

    @Test
    fun `a successful attempt is recorded with identity, usage exactly as reported and provenance`() {
        val store = InMemoryAttemptObservationStore()
        val observation =
            observe(AttemptRecordingObserver(store, clock = FixedClock()))
        observation.onProviderResponse(
            ModelResponse(
                content = "answer",
                inputTokens = 10,
                outputTokens = 5,
                thinkingTokens = 3,
                modelUsed = "actual-model",
            ),
        )
        observation.onCallCompleted(parseSuccess = true)

        val attempt = recorded(store).single()
        assertThat(attempt.invocationId).isEqualTo("inv-1")
        assertThat(attempt.workflowRunId).isEqualTo("run-1")
        assertThat(attempt.correlationId).isEqualTo("corr-1")
        assertThat(attempt.routeIndex).isZero()
        assertThat(attempt.outcome).isEqualTo(AttemptOutcome.SUCCEEDED)
        assertThat(attempt.reportedModel).isEqualTo("actual-model")
        assertThat(attempt.provenance).isEqualTo(AttemptProvenance.OBSERVED_IN_PROCESS)
        // Reported separately and never summed: a total would double-count reasoning folded into output.
        assertThat(attempt.inputTokens).isEqualTo(10)
        assertThat(attempt.outputTokens).isEqualTo(5)
        assertThat(attempt.thinkingTokens).isEqualTo(3)
        assertThat(attempt.durationMillis).isEqualTo(100L)
    }

    @Test
    fun `missing usage stays null and a STOP default is never reported as a provider claim`() {
        val store = InMemoryAttemptObservationStore()
        val observation = observe(AttemptRecordingObserver(store, clock = FixedClock()))
        observation.onProviderResponse(ModelResponse(content = "answer"))
        observation.onCallCompleted(parseSuccess = null)

        val attempt = recorded(store).single()
        assertThat(attempt.inputTokens).isNull()
        assertThat(attempt.outputTokens).isNull()
        assertThat(attempt.thinkingTokens).isNull()
        assertThat(attempt.reportedModel).isNull()
        assertThat(attempt.finishReason).isEqualTo(FinishReason.STOP.name)
        assertThat(attempt.finishReasonReported).isFalse()
    }

    @Test
    fun `a failure followed by completion is one FAILED attempt, not two and not a success`() {
        val store = InMemoryAttemptObservationStore()
        val observation = observe(AttemptRecordingObserver(store, clock = FixedClock()))
        observation.onProviderFailure(IllegalStateException("boom"))
        observation.onCallCompleted(parseSuccess = null)

        val attempts = recorded(store)
        assertThat(attempts).hasSize(1)
        assertThat(attempts.single().outcome).isEqualTo(AttemptOutcome.FAILED)
        assertThat(attempts.single().failureCategory).isEqualTo("IllegalStateException")
        assertThat(attempts.single().outcome).isNotEqualTo(AttemptOutcome.SUCCEEDED)
    }

    @Test
    fun `a cancelled attempt is recorded as CANCELLED`() {
        val store = InMemoryAttemptObservationStore()
        val observation = observe(AttemptRecordingObserver(store, clock = FixedClock()))
        observation.onCallCancelled()

        assertThat(recorded(store).single().outcome).isEqualTo(AttemptOutcome.CANCELLED)
    }

    @Test
    fun `a completion with neither response nor failure is UNKNOWN, never success`() {
        val store = InMemoryAttemptObservationStore()
        val observation = observe(AttemptRecordingObserver(store, clock = FixedClock()))
        observation.onCallCompleted(parseSuccess = null)

        val attempt = recorded(store).single()
        assertThat(attempt.outcome).isEqualTo(AttemptOutcome.UNKNOWN)
        assertThat(attempt.reportedModel).isNull()
    }

    @Test
    fun `repeated terminal callbacks do not duplicate the attempt`() {
        val store = InMemoryAttemptObservationStore()
        val observation = observe(AttemptRecordingObserver(store, clock = FixedClock()))
        observation.onProviderResponse(ModelResponse(content = "answer"))
        observation.onCallCompleted(parseSuccess = true)
        observation.onCallCompleted(parseSuccess = true)
        observation.onCallCancelled()

        assertThat(recorded(store)).hasSize(1)
    }

    @Test
    fun `an attempt without invocation identity is not recorded under a fabricated identity`() {
        val delegate = CountingDelegate()
        val store = InMemoryAttemptObservationStore()
        observe(AttemptRecordingObserver(store, delegate = delegate, clock = FixedClock()), invocationId = null)
            .onCallCompleted(parseSuccess = true)

        assertThat(delegate.started).isEqualTo(1)
        assertThat(store.all()).isEmpty()
        assertThat(store.findByInvocation("inv-1")).isEqualTo(AttemptHistory.Available(emptyList()))
        assertThat(store.findByWorkflowRun("run-1")).isEqualTo(AttemptHistory.Available(emptyList()))
    }

    @Test
    fun `a store failure neither escapes nor stops the delegate from observing`() {
        val delegate = CountingDelegate()
        val observation =
            observe(AttemptRecordingObserver(ExplodingStore, delegate = delegate, clock = FixedClock()))

        observation.onProviderResponse(ModelResponse(content = "answer"))
        observation.onCallCompleted(parseSuccess = true)

        assertThat(delegate.responses).isEqualTo(1)
        assertThat(delegate.completions).isEqualTo(1)
    }

    @Test
    fun `a terminal outcome is forwarded to the delegate the observer was given`() {
        // Isolation of a throwing observer is the composition's failure-isolating wrapper, which the
        // secondary-failure architecture guard requires at every invocation site; this class forwards.
        val seen = mutableListOf<String>()
        val delegate =
            OperationObserver { _ ->
                object : OperationObservation {
                    override fun onProviderResponse(response: ModelResponse) {
                        seen += "response"
                    }

                    override fun onProviderFailure(error: Throwable) {
                        seen += "failure"
                    }

                    override fun onStructuredParseFailure(
                        rawResponse: String,
                        errorSummary: String,
                    ) = Unit

                    override fun onCallCompleted(parseSuccess: Boolean?) {
                        seen += "completed"
                    }
                }
            }
        val store = InMemoryAttemptObservationStore()
        val observation =
            observe(AttemptRecordingObserver(store, delegate = delegate, clock = FixedClock()))
        observation.onProviderResponse(ModelResponse(content = "answer"))
        observation.onCallCompleted(parseSuccess = true)

        assertThat(seen).containsExactly("response", "completed")
        assertThat(recorded(store).single().outcome).isEqualTo(AttemptOutcome.SUCCEEDED)
    }

    @Test
    fun `the in-memory store is idempotent per invocation and attempt`() {
        val store = InMemoryAttemptObservationStore()
        val attempt =
            ModelAttemptObservation(
                invocationId = "inv-1",
                attempt = 0,
                workflowRunId = "run-1",
                correlationId = "corr-1",
                routeIndex = 0,
                providerId = "primary",
                requestedModel = "logical-model",
                reportedModel = null,
                startedAt = Instant.parse("2026-03-01T00:00:00Z"),
                completedAt = Instant.parse("2026-03-01T00:00:01Z"),
                durationMillis = 1000,
                outcome = AttemptOutcome.SUCCEEDED,
                failureCategory = null,
                finishReason = "STOP",
                finishReasonReported = false,
                inputTokens = null,
                outputTokens = null,
                thinkingTokens = null,
                provenance = AttemptProvenance.OBSERVED_IN_PROCESS,
            )

        assertThat(store.append(attempt)).isEqualTo(AttemptAppendOutcome.RECORDED)
        assertThat(store.append(attempt)).isEqualTo(AttemptAppendOutcome.ALREADY_RECORDED)
        assertThat(recorded(store)).hasSize(1)
    }

    @Test
    fun `the uninstrumented store claims nothing`() {
        assertThat(UninstrumentedAttemptObservationStore.findByInvocation("inv-1"))
            .isEqualTo(AttemptHistory.Uninstrumented)
        assertThat(UninstrumentedAttemptObservationStore.findByWorkflowRun("run-1"))
            .isEqualTo(AttemptHistory.Uninstrumented)
    }

    @Test
    fun `recording starts only when the engine asks for it, not at call start`() {
        val store = InMemoryAttemptObservationStore()
        val delegate = CountingDelegate()
        val observer = AttemptRecordingObserver(store, delegate = delegate, clock = FixedClock())

        // The engine starts observation before authorization; nothing may be written until it asks
        // for recording after the pre-call checks, or a refusal would look like a provider attempt.
        observer.onCallStarted(context()).onCallCompleted(parseSuccess = true)

        assertThat(delegate.completions).isEqualTo(1)
        assertThat(store.all()).isEmpty()
    }

    @Test
    fun `a throwing delegate cannot drop the attempt record`() {
        val store = InMemoryAttemptObservationStore()
        val throwing =
            OperationObserver { _ ->
                object : OperationObservation {
                    override fun onProviderResponse(response: ModelResponse) = error("telemetry down")

                    override fun onProviderFailure(error: Throwable) = error("telemetry down")

                    override fun onStructuredParseFailure(
                        rawResponse: String,
                        errorSummary: String,
                    ) = error("telemetry down")

                    override fun onCallCompleted(parseSuccess: Boolean?) = error("telemetry down")
                }
            }
        val observer = AttemptRecordingObserver(store, delegate = throwing, clock = FixedClock())
        val callContext = context()
        // The failure-isolating boundary turns a throwing start into a no-op observation.
        val started = runCatching { observer.onCallStarted(callContext) }
        val delegateObservation = started.getOrDefault(NoOpOperationObservation)
        val observation = observer.recording(callContext, delegateObservation, identity())

        observation.onProviderResponse(ModelResponse(content = "answer"))
        observation.onCallCompleted(parseSuccess = true)

        assertThat(recorded(store).single().outcome).isEqualTo(AttemptOutcome.SUCCEEDED)
    }
}
