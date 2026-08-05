package dev.tramai.core.observation

import dev.tramai.core.exception.ProviderFailureCode

/**
 * Event delivered to an explicitly configured internal diagnostics sink for
 * provider HTTP and transport failures.
 *
 * The observer receives the original [ProviderFailureDiagnosticEvent.failure]
 * throwable and a bounded preview of a rejected HTTP response body so
 * operators can diagnose the root cause. Observer data is never automatically
 * forwarded to model messages, public exceptions, engine events, audit, logs,
 * or telemetry — it is diagnostic-only by construction.
 *
 * Failure semantics are fail-open: an observer exception is swallowed on
 * ordinary failure paths and must never replace the provider failure. An
 * observer that throws [kotlinx.coroutines.CancellationException] while the
 * enclosing coroutine is still active is treated the same as any other
 * observer failure and swallowed; only genuine coroutine cancellation
 * propagates (at the next suspension point).
 */
fun interface ProviderFailureDiagnosticObserver {
    fun record(event: ProviderFailureDiagnosticEvent)
}

/** Default no-op observer. */
object NoOpProviderFailureDiagnosticObserver : ProviderFailureDiagnosticObserver {
    override fun record(event: ProviderFailureDiagnosticEvent) = Unit
}

/**
 * Diagnostic payload for a failed provider HTTP or transport attempt.
 *
 * @property providerId the provider identifier (e.g. `openai`, `anthropic`);
 * diagnostic-only, never interpolated into safe caller-visible messages
 * @property code the typed [ProviderFailureCode] classification
 * @property statusCode the HTTP status for [ProviderFailureCode.HTTP_REJECTED]
 * failures, `null` for transport failures
 * @property retryable whether the failure is classified retryable
 * @property retryAfterMillis the recommended retry delay, when the provider
 * exposed one
 * @property failure the original throwable for transport failures; `null` for
 * HTTP-status failures; never forwarded beyond this observer
 * @property httpBodyPreview a bounded preview of the rejected response body
 * for [ProviderFailureCode.HTTP_REJECTED] failures, `null` otherwise
 * @property httpBodyPreviewTruncated true when [httpBodyPreview] was cut off
 * at the size limit
 */
data class ProviderFailureDiagnosticEvent(
    val providerId: String,
    val code: ProviderFailureCode,
    val statusCode: Int?,
    val retryable: Boolean,
    val retryAfterMillis: Long?,
    val failure: Throwable?,
    val httpBodyPreview: String?,
    val httpBodyPreviewTruncated: Boolean,
)
