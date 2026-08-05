package dev.tramai.core.provider

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.ProviderFailureCode
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.observation.NoOpProviderFailureDiagnosticObserver
import dev.tramai.core.observation.ProviderFailureDiagnosticEvent
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.stream.Stream
import kotlinx.coroutines.CancellationException

/**
 * Maximum size of a provider error-body preview retained for diagnostics.
 *
 * The preview is capped by string length; typical JSON error payloads are
 * ASCII, for which characters and bytes coincide. The cap bounds both the
 * retained memory and the diagnostic surface regardless of encoding.
 */
const val PROVIDER_ERROR_BODY_LIMIT_BYTES = 8 * 1024

/**
 * Applies the normalized Tramai timeout, when present, to a provider HTTP request.
 */
fun HttpRequest.Builder.applyTramaiTimeout(request: ModelRequest): HttpRequest.Builder = apply {
    request.timeoutMillis?.let { timeout(Duration.ofMillis(it)) }
}

/**
 * Creates a normalized, safe provider failure for an HTTP status response.
 *
 * The public [ProviderException.message] is fixed text plus the numeric HTTP
 * status. The response body is never placed in the exception, standard logs,
 * or telemetry; a bounded preview is delivered only to [observer].
 *
 * Retry behaviour is preserved structurally through the returned
 * exception's [ProviderException.statusCode], [ProviderException.retryable],
 * and [ProviderException.retryAfterMillis] fields.
 *
 * @param bodyTruncated true when [body] is already a bounded preview that was
 * cut off at the size limit by a streaming caller; the flag survives the cap
 * applied here so the diagnostic reports truncation correctly.
 */
fun providerHttpFailure(
    providerId: String,
    statusCode: Int,
    body: String,
    bodyTruncated: Boolean = false,
    retryAfterHeader: String? = null,
    observer: ProviderFailureDiagnosticObserver = NoOpProviderFailureDiagnosticObserver,
): ProviderException {
    val retryable = isRetryableStatus(statusCode)
    val retryAfterMillis = parseRetryAfterMillis(retryAfterHeader)
    // Callers that read the body with a bounded stream pass bodyTruncated=true;
    // their truncation signal survives the cap here. Otherwise the helper
    // caps an already-materialized body itself.
    val bounded = boundedBodyPreview(body).let { if (bodyTruncated) it.copy(truncated = true) else it }
    deliver(
        observer,
        ProviderFailureDiagnosticEvent(
            providerId = providerId,
            code = ProviderFailureCode.HTTP_REJECTED,
            statusCode = statusCode,
            retryable = retryable,
            retryAfterMillis = retryAfterMillis,
            failure = null,
            httpBodyPreview = bounded.text,
            httpBodyPreviewTruncated = bounded.truncated,
        ),
    )
    return ProviderException(
        message = "Provider request failed with HTTP $statusCode",
        statusCode = statusCode,
        retryable = retryable,
        retryAfterMillis = retryAfterMillis,
        failureCode = ProviderFailureCode.HTTP_REJECTED,
    )
}

/**
 * Logs a metadata-only debug record for a non-2xx provider response.
 *
 * The record contains only trusted metadata — never the response body,
 * headers, credentials, or any text derived from the response.
 */
fun logProviderHttpFailureDebug(
    logger: System.Logger?,
    providerId: String,
    statusCode: Int,
) {
    if (logger?.isLoggable(System.Logger.Level.DEBUG) != true) {
        return
    }
    logger.log(
        System.Logger.Level.DEBUG,
        "Provider request failed: provider=$providerId code=HTTP_REJECTED status=$statusCode retryable=${isRetryableStatus(statusCode)}",
    )
}

/**
 * Creates a normalized, safe provider failure for transport-layer errors.
 *
 * The public message is fixed text per failure category; `error.message` is
 * never interpolated into it. Built-in instances do not retain [error] as
 * their cause — the original throwable is delivered only to [observer], so
 * stack traces and telemetry cannot expose its message. A [ProviderException]
 * passed in is returned unchanged: it is already a trusted typed failure.
 *
 * Retry behaviour is preserved structurally through the returned exception's
 * [ProviderException.retryable] field. Cancellation is never converted into a
 * provider failure: [error] is rethrown before any classification or
 * diagnostic delivery when it is a [kotlinx.coroutines.CancellationException].
 */
fun providerTransportFailure(
    providerId: String,
    error: Throwable,
    observer: ProviderFailureDiagnosticObserver = NoOpProviderFailureDiagnosticObserver,
): ProviderException {
    error.rethrowIfCancellation()
    if (error is ProviderException) {
        return error
    }
    return when (error) {
        is HttpTimeoutException -> transportFailure(
            providerId, error, ProviderFailureCode.TIMEOUT, "Provider request timed out", retryable = true, observer,
        )
        is ConnectException -> transportFailure(
            providerId, error, ProviderFailureCode.CONNECTION_FAILED, "Provider connection failed", retryable = true, observer,
        )
        is IOException -> transportFailure(
            providerId, error, ProviderFailureCode.TRANSPORT_FAILED, "Provider transport failed", retryable = true, observer,
        )
        else -> transportFailure(
            providerId, error, ProviderFailureCode.UNEXPECTED_FAILURE, "Provider request failed", retryable = false, observer,
        )
    }
}

private fun transportFailure(
    providerId: String,
    error: Throwable,
    code: ProviderFailureCode,
    message: String,
    retryable: Boolean,
    observer: ProviderFailureDiagnosticObserver,
): ProviderException {
    deliver(
        observer,
        ProviderFailureDiagnosticEvent(
            providerId = providerId,
            code = code,
            statusCode = null,
            retryable = retryable,
            retryAfterMillis = null,
            failure = error,
            httpBodyPreview = null,
            httpBodyPreviewTruncated = false,
        ),
    )
    return ProviderException(
        message = message,
        retryable = retryable,
        failureCode = code,
    )
}

/**
 * A bounded preview of a provider error body.
 *
 * @property text the retained text, at most the configured size limit
 * @property truncated true when the full body was longer than the retained text
 */
data class BoundedProviderErrorBody(
    val text: String,
    val truncated: Boolean,
)

/**
 * Caps an already-materialized error body at [limitBytes] characters.
 *
 * Used for non-streaming responses, where the JDK handler has already
 * materialized the body; the retained copy — and therefore the diagnostic
 * surface — is still bounded.
 */
fun boundedBodyPreview(body: String, limitBytes: Int = PROVIDER_ERROR_BODY_LIMIT_BYTES): BoundedProviderErrorBody =
    if (body.length <= limitBytes) {
        BoundedProviderErrorBody(body, truncated = false)
    } else {
        BoundedProviderErrorBody(body.take(limitBytes), truncated = true)
    }

/**
 * Reads at most [limitBytes] characters from a streaming line body and closes
 * it, reporting whether the body continued beyond the retained preview.
 *
 * Replaces unbounded materialization (`toArray().joinToString(...)`) on
 * streaming error paths: the stream is closed once the cap is reached, so the
 * full error body is never materialized.
 */
fun boundedLinesPreview(lines: Stream<String>, limitBytes: Int = PROVIDER_ERROR_BODY_LIMIT_BYTES): BoundedProviderErrorBody =
    lines.use { stream ->
        val sb = StringBuilder()
        val iterator = stream.iterator()
        while (iterator.hasNext()) {
            val line = iterator.next()
            val separatorLength = if (sb.isEmpty()) 0 else 1
            if (sb.length + separatorLength + line.length > limitBytes) {
                return@use BoundedProviderErrorBody(sb.toString(), truncated = true)
            }
            if (sb.isNotEmpty()) {
                sb.append('\n')
            }
            sb.append(line)
        }
        BoundedProviderErrorBody(sb.toString(), truncated = false)
    }

/**
 * Delivers a diagnostic event fail-open.
 *
 * The delivery call site is synchronous — no suspension happens between the
 * original failure and delivery — so genuine coroutine cancellation cannot be
 * in flight here: it either was already rethrown by
 * [rethrowIfCancellation] on the original error, or surfaces at the next
 * suspension point (`emit`, `withContext`). An observer-thrown
 * [CancellationException] is therefore always the observer's own bug and is
 * swallowed like any other observer failure; it must never replace the
 * provider failure.
 */
private fun deliver(observer: ProviderFailureDiagnosticObserver, event: ProviderFailureDiagnosticEvent) {
    try {
        observer.record(event)
    } catch (e: CancellationException) {
        // Observer-thrown cancellation never replaces the provider failure.
    } catch (e: Exception) {
        // Fail-open: observer bugs never replace the provider failure.
    }
}

private fun isRetryableStatus(statusCode: Int): Boolean = statusCode in setOf(408, 425, 429, 500, 502, 503, 504)

private fun parseRetryAfterMillis(value: String?): Long? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    trimmed.toLongOrNull()?.let { seconds ->
        return if (seconds >= 0) seconds * 1_000 else null
    }

    val retryAt = runCatching {
        Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).parse(trimmed))
    }.getOrNull() ?: return null

    return (retryAt.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L)
}
