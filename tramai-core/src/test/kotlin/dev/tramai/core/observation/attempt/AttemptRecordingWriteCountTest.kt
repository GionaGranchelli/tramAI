package dev.tramai.core.observation.attempt

import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.AttemptIdentity
import dev.tramai.core.observation.OperationCallContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gate 5 of Epic 0.7.5g — a mutation-surviving gap found by the campaign and closed here.
 *
 * `InMemoryAttemptObservationStore` is idempotent on `(invocationId, attempt)`, so a test that only
 * counts rows cannot tell whether the observer wrote once or three times: the store hides the second
 * write. The engine emits several terminal callbacks for one attempt (failure, then completion, or
 * cancellation), so the single-write guard is load-bearing — this test counts the calls themselves.
 */
class AttemptRecordingWriteCountTest {
    private class CountingStore : AttemptObservationStore {
        val appends = AtomicInteger()

        override fun append(observation: ModelAttemptObservation): AttemptAppendOutcome {
            appends.incrementAndGet()
            return AttemptAppendOutcome.RECORDED
        }

        override fun findByWorkflowRun(workflowRunId: String): AttemptHistory = AttemptHistory.Uninstrumented

        override fun findByInvocation(invocationId: String): AttemptHistory = AttemptHistory.Uninstrumented
    }

    private fun observe(
        observer: AttemptRecordingObserver,
        attempt: Int = 0,
    ) = observer.recording(
        OperationCallContext(
            serviceInterface = "com.example.Service",
            methodName = "call",
            providerId = "primary",
            requestedModel = "logical-model",
            attempt = attempt,
        ),
        observer.onCallStarted(
            OperationCallContext(
                serviceInterface = "com.example.Service",
                methodName = "call",
                providerId = "primary",
                requestedModel = "logical-model",
                attempt = attempt,
            ),
        ),
        AttemptIdentity(
            invocationId = "inv-1",
            workflowRunId = "run-1",
            correlationId = "corr-1",
            routeIndex = 0,
        ),
    )

    @Test
    fun `one attempt is written exactly once whatever terminal callbacks arrive`() {
        val store = CountingStore()
        val observation = observe(AttemptRecordingObserver(store))

        // The sequence the engine really produces for a failed then completed attempt.
        observation.onProviderFailure(IllegalStateException("boom"))
        observation.onCallCompleted(parseSuccess = null)
        observation.onCallCompleted(parseSuccess = null)
        observation.onCallCancelled()

        assertThat(store.appends.get()).isEqualTo(1)
    }

    @Test
    fun `cancellation after a response still writes once`() {
        val store = CountingStore()
        val observation = observe(AttemptRecordingObserver(store))

        observation.onProviderResponse(ModelResponse(content = "answer", modelUsed = "logical-model"))
        observation.onCallCompleted(parseSuccess = null)
        observation.onCallCancelled()

        assertThat(store.appends.get()).isEqualTo(1)
    }
}
