package dev.tramai.core.observation

import dev.tramai.core.model.ToolFailureCode

/**
 * Event delivered to an explicitly configured internal diagnostics sink.
 *
 * The observer receives the original [ToolFailureDiagnosticEvent.failure]
 * throwable so operators can diagnose the root cause. Observer data is never
 * automatically forwarded to model messages, public exceptions, engine
 * events, audit, logs, or telemetry — it is diagnostic-only by construction.
 *
 * Failure semantics are fail-open: an observer exception is swallowed on
 * ordinary failure paths and must never replace cancellation, the original
 * tool failure, or a successful tool result. An observer that throws
 * [kotlinx.coroutines.CancellationException] while the enclosing coroutine is
 * still active is treated the same as any other observer failure and
 * swallowed; only genuine coroutine cancellation propagates.
 */
fun interface ToolFailureDiagnosticObserver {
    fun record(event: ToolFailureDiagnosticEvent)
}

/** Default no-op observer. */
object NoOpToolFailureDiagnosticObserver : ToolFailureDiagnosticObserver {
    override fun record(event: ToolFailureDiagnosticEvent) = Unit
}

/**
 * Diagnostic payload for a failed tool attempt.
 *
 * @property toolName the tool that failed
 * @property code the [ToolFailureCode] the engine classified the failure as
 * @property attempt the zero-based attempt index within the retry loop
 * @property retryClassified true when the failure is classified as retryable
 * (an idempotent tool); false for invalid-input and terminal exhaustion events
 * @property failure the original throwable; never forwarded beyond this observer
 */
data class ToolFailureDiagnosticEvent(
    val toolName: String,
    val code: ToolFailureCode,
    val attempt: Int,
    val retryClassified: Boolean,
    val failure: Throwable,
)
