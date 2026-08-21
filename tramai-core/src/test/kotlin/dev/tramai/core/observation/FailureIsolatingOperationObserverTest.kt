package dev.tramai.core.observation

import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEventFailurePolicy
import dev.tramai.core.observation.event.RuntimeEvents
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Epic 5.3 — operation observer lifecycle matrix.
 *
 * Every lifecycle callback of [OperationObservation] is exercised with a
 * throwing delegate. The invariant under test: a telemetry failure is
 * contained and never changes the business outcome; cancellation always
 * escapes; FAIL_CLOSED events propagate; FAIL_OPEN events are contained.
 */
class FailureIsolatingOperationObserverTest {

    private val modelResponse = ModelResponse(
        content = "ok",
    )

    private val context = OperationCallContext(
        serviceInterface = "dev.tramai.example.Service",
        methodName = "call",
        providerId = "test-provider",
        requestedModel = "test-model",
        attempt = 0,
    )

    /** Delegate whose every callback throws [IllegalStateException]. */
    private val throwingObservation = object : OperationObservation {
        override fun onProviderResponse(response: ModelResponse) = throw IllegalStateException("boom-response")
        override fun onProviderFailure(error: Throwable) = throw IllegalStateException("boom-failure")
        override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) = throw IllegalStateException("boom-parse")
        override fun onEngineEvent(name: String, attributes: Map<String, Any?>) = throw IllegalStateException("boom-event")
        override fun onCallCompleted(parseSuccess: Boolean?) = throw IllegalStateException("boom-completed")
    }

    private fun isolated(): OperationObservation =
        FailureIsolatingOperationObservation(throwingObservation)

    @Test
    fun `provider response failure is contained`() {
        isolated().onProviderResponse(modelResponse)
    }

    @Test
    fun `provider failure callback failure is contained`() {
        isolated().onProviderFailure(IllegalStateException("primary"))
    }

    @Test
    fun `structured parse failure callback failure is contained`() {
        isolated().onStructuredParseFailure("raw", "summary")
    }

    @Test
    fun `legacy engine event callback failure is contained`() {
        isolated().onEngineEvent("tramai.engine.event", emptyMap())
    }

    @Test
    fun `call completed failure is contained`() {
        isolated().onCallCompleted(parseSuccess = true)
        isolated().onCallCompleted(parseSuccess = null)
    }

    @Test
    fun `call cancelled propagates to the cancellation helper for suppression`() {
        // The cancellation path is deliberately NOT isolated: the engine's
        // completeCancellation helper catches the observer failure and
        // attaches it as suppressed onto the in-flight CancellationException
        // (frozen contract — CE stays primary, secondary failure preserved).
        assertFailsWith<IllegalStateException> {
            isolated().onCallCancelled()
        }
    }

    @Test
    fun `fail-open runtime event emission failure is contained`() {
        val event = RuntimeEvent.of(RuntimeEvents.ROUTE_SELECTED) {
            set(RuntimeAttributes.PROVIDER_ID, "p")
        }
        isolated().onEngineEvent(event)
    }

    @Test
    fun `fail-closed runtime event emission failure propagates`() {
        val event = RuntimeEvent.of(RuntimeEvents.ROUTE_SELECTED.copy(
            name = "tramai.engine.fail-closed.probe",
            failurePolicy = RuntimeEventFailurePolicy.FAIL_CLOSED,
        )) {
            set(RuntimeAttributes.PROVIDER_ID, "p")
        }
        assertFailsWith<IllegalStateException> {
            isolated().onEngineEvent(event)
        }
    }

    @Test
    fun `cancellation from an observer always escapes unchanged`() {
        val cancellation = CancellationException("observer-cancelled")
        val cancellingObservation = object : OperationObservation {
            override fun onProviderResponse(response: ModelResponse) = throw cancellation
            override fun onProviderFailure(error: Throwable) = throw cancellation
            override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) = throw cancellation
            override fun onEngineEvent(name: String, attributes: Map<String, Any?>) = throw cancellation
            override fun onCallCompleted(parseSuccess: Boolean?) = throw cancellation
        }
        val obs = FailureIsolatingOperationObservation(cancellingObservation)
        val caught = assertFailsWith<CancellationException> {
            obs.onProviderResponse(modelResponse)
        }
        assertSame(cancellation, caught)
    }

    @Test
    fun `onCallStarted failure returns no-op observation instead of throwing`() {
        val observer = FailureIsolatingOperationObserver(
            object : OperationObserver {
                override fun onCallStarted(context: OperationCallContext): OperationObservation =
                    throw IllegalStateException("boom-start")
            },
        )
        val observation = observer.onCallStarted(context)
        assertTrue(observation is NoOpOperationObservation)
        observation.onProviderResponse(modelResponse) // no-op must not throw
        observation.onCallCompleted(parseSuccess = true)
    }

    @Test
    fun `onCallStarted cancellation propagates unchanged`() {
        val cancellation = CancellationException("cancelled")
        val observer = FailureIsolatingOperationObserver(
            object : OperationObserver {
                override fun onCallStarted(context: OperationCallContext): OperationObservation = throw cancellation
            },
        )
        assertSame(cancellation, assertFailsWith<CancellationException> {
            observer.onCallStarted(context)
        })
    }

    @Test
    fun `successful callbacks are forwarded to the delegate`() {
        val received = mutableListOf<String>()
        val observation = FailureIsolatingOperationObservation(object : OperationObservation {
            override fun onProviderResponse(response: ModelResponse) {
                received += "response"
            }

            override fun onProviderFailure(error: Throwable) {
                received += "failure"
            }

            override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) {
                received += "parse"
            }

            override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
                received += "event"
            }

            override fun onCallCompleted(parseSuccess: Boolean?) {
                received += "completed"
            }
        })
        observation.onProviderResponse(modelResponse)
        observation.onProviderFailure(IllegalStateException("x"))
        observation.onStructuredParseFailure("raw", "s")
        observation.onEngineEvent("name", emptyMap())
        observation.onCallCompleted(true)
        assertEquals(listOf("response", "failure", "parse", "event", "completed"), received)
    }
}
