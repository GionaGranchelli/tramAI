package dev.tramai.core.structured

/**
 * Event delivered to an explicitly configured internal diagnostics sink for
 * structured-output failures.
 *
 * Event fields are diagnostic-only: never auto-forward them to model messages,
 * public exceptions, events, logs, audit, or telemetry. Never include the full
 * original prompt.
 */
fun interface StructuredOutputFailureDiagnosticObserver {
    suspend fun onFailure(event: StructuredOutputFailureDiagnosticEvent)
}

/**
 * Diagnostic payload for a structured-output failure.
 *
 * Attempt indexing is 1-based, consistent with tool/workflow observers. Every
 * field is diagnostic-only and must not be automatically forwarded to model
 * messages, public exceptions, events, logs, audit, or telemetry.
 */
data class StructuredOutputFailureDiagnosticEvent(
    val serviceName: String?,
    val methodName: String?,
    val code: StructuredOutputFailureCode,
    val attempt: Int,
    val willRetry: Boolean,
    val rawResponsePreview: String?,
    val rawResponseTruncated: Boolean,
    val detailPreview: String?,
    val detailTruncated: Boolean,
    val failure: Throwable?,
    val numericMetadata: Map<String, Long> = emptyMap(),
)

/** Default no-op structured-output diagnostic observer. */
object NoOpStructuredOutputFailureDiagnosticObserver : StructuredOutputFailureDiagnosticObserver {
    override suspend fun onFailure(event: StructuredOutputFailureDiagnosticEvent) = Unit
}
