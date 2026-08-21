@file:OptIn(ExperimentalTramaiInternalApi::class)
package dev.tramai.engine

import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEventFailurePolicy
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import kotlinx.coroutines.CancellationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Epic 5.3 — [FailureIsolatingEngineEventObserver] typed-policy enforcement.
 *
 * Engine lifecycle events are non-authoritative telemetry: a throwing
 * observer must never block engine behaviour. The typed overload honours the
 * event's declared failure policy: FAIL_OPEN (catalogue default) is contained
 * with a safe diagnostic, FAIL_CLOSED propagates unchanged. The legacy
 * [EngineEventObserver.onEngineEvent] overload is always contained, and
 * cancellation always escapes unchanged.
 */
class FailureIsolatingEngineEventObserverTest {

    private val failOpenEvent = RuntimeEvent.of(RuntimeEvents.DLP_TOOL_RESULT_REJECTED) {
        set(RuntimeAttributes.REASON_CODE, "dlp-rejected")
    }

    private val failClosedEvent = RuntimeEvent.of(
        RuntimeEvents.DLP_TOOL_RESULT_REJECTED.copy(
            name = "tramai.engine.fail-closed.probe",
            failurePolicy = RuntimeEventFailurePolicy.FAIL_CLOSED,
        ),
    ) {
        set(RuntimeAttributes.REASON_CODE, "dlp-rejected")
    }

    @Test
    fun `fail-open typed event failure is contained and never reaches the legacy path`() {
        var legacyCalls = 0
        val delegate = object : EngineEventObserver {
            override fun onEngineEvent(event: RuntimeEvent) = throw IllegalStateException("typed-down")
            override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
                legacyCalls++
            }
        }

        val diagnostics = withCapturedSecondaryDiagnostics {
            FailureIsolatingEngineEventObserver(delegate).onEngineEvent(failOpenEvent)
        }

        // no exception escaped, and the typed path was used — the delegate's
        // legacy method was never invoked
        assertThat(legacyCalls).isZero()
        assertThat(diagnostics.any {
            it.contains("extensionPoint=engine_event_observer") &&
                it.contains("failurePolicy=FAIL_OPEN") &&
                it.contains("authority=NON_AUTHORITATIVE")
        }).isTrue
    }

    @Test
    fun `fail-closed typed event failure propagates unchanged`() {
        val thrown = IllegalStateException("fail-closed-down")
        val delegate = object : EngineEventObserver {
            override fun onEngineEvent(event: RuntimeEvent) = throw thrown
            override fun onEngineEvent(name: String, attributes: Map<String, Any?>) = Unit
        }

        assertThatThrownBy { FailureIsolatingEngineEventObserver(delegate).onEngineEvent(failClosedEvent) }
            .isSameAs(thrown)
    }

    @Test
    fun `legacy event failure is contained with a diagnostic`() {
        val delegate = object : EngineEventObserver {
            override fun onEngineEvent(name: String, attributes: Map<String, Any?>) =
                throw IllegalStateException("legacy-down")
        }

        val diagnostics = withCapturedSecondaryDiagnostics {
            FailureIsolatingEngineEventObserver(delegate).onEngineEvent("tramai.engine.legacy.probe", emptyMap())
        }

        assertThat(diagnostics.any {
            it.contains("extensionPoint=engine_event_observer") &&
                it.contains("callback=onEngineEvent") &&
                it.contains("failurePolicy=FAIL_OPEN") &&
                it.contains("authority=NON_AUTHORITATIVE")
        }).isTrue
    }

    @Test
    fun `cancellation from a fail-open typed delegate escapes unchanged`() {
        val cancellation = CancellationException("observer-cancelled")
        val delegate = object : EngineEventObserver {
            override fun onEngineEvent(event: RuntimeEvent) = throw cancellation
            override fun onEngineEvent(name: String, attributes: Map<String, Any?>) = Unit
        }

        assertThatThrownBy { FailureIsolatingEngineEventObserver(delegate).onEngineEvent(failOpenEvent) }
            .isSameAs(cancellation)
    }

    @Test
    fun `cancellation from a fail-closed typed delegate escapes unchanged`() {
        val cancellation = CancellationException("observer-cancelled")
        val delegate = object : EngineEventObserver {
            override fun onEngineEvent(event: RuntimeEvent) = throw cancellation
            override fun onEngineEvent(name: String, attributes: Map<String, Any?>) = Unit
        }

        assertThatThrownBy { FailureIsolatingEngineEventObserver(delegate).onEngineEvent(failClosedEvent) }
            .isSameAs(cancellation)
    }
}
