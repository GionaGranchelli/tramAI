package dev.tramai.core.provider

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.ProviderFailureCode
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.observation.ProviderFailureDiagnosticEvent
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Maximum number of provider error-body bytes retained for diagnostics. */
const val PROVIDER_ERROR_BODY_LIMIT_BYTES = 8 * 1024

/** Applies the normalized Tramai timeout, when present, to a provider HTTP request. */
fun HttpRequest.Builder.applyTramaiTimeout(request: ModelRequest): HttpRequest.Builder = apply {
    request.timeoutMillis?.let { timeout(Duration.ofMillis(it)) }
}

/**
 * Creates a safe provider failure whose [message] is emitted verbatim.
 *
 * Only pass text controlled by the caller. Provider responses, throwable
 * messages, request data, credentials, and other untrusted values must not be
 * interpolated into [message].
 */
fun safeProviderFailure(
    message: String,
    code: ProviderFailureCode,
    statusCode: Int? = null,
    retryable: Boolean = false,
    retryAfterMillis: Long? = null,
): ProviderException = ProviderException(
    message = message,
    statusCode = statusCode,
    retryable = retryable,
    retryAfterMillis = retryAfterMillis,
).apply {
    failureCode = code
    safeFactoryTrusted = true
}

/**
 * Legacy ABI-compatible HTTP failure factory.
 *
 * The response [body] is bounded before it is discarded. It is never placed
 * in the returned exception, logs, telemetry, or an observer event.
 */
@Suppress("UNUSED_PARAMETER")
fun providerHttpFailure(
    providerName: String,
    statusCode: Int,
    body: String,
    retryAfterHeader: String? = null,
): ProviderException {
    boundedBodyPreview(body)
    return newHttpFailure(statusCode, retryAfterHeader)
}

/**
 * Observer-aware HTTP failure factory. The diagnostic body preview is bounded
 * by UTF-8 bytes even when the supplied [body] was already materialized.
 */
suspend fun providerHttpFailureObserved(
    providerId: String,
    statusCode: Int,
    body: String,
    bodyTruncated: Boolean = false,
    retryAfterHeader: String? = null,
    observer: ProviderFailureDiagnosticObserver,
): ProviderException = providerHttpFailureObserved(
    providerId = providerId,
    providerAlias = null,
    statusCode = statusCode,
    body = body,
    bodyTruncated = bodyTruncated,
    retryAfterHeader = retryAfterHeader,
    observer = observer,
)

/** Observer-aware HTTP factory with a diagnostic-only caller alias. */
suspend fun providerHttpFailureObserved(
    providerId: String,
    providerAlias: String?,
    statusCode: Int,
    body: String,
    bodyTruncated: Boolean = false,
    retryAfterHeader: String? = null,
    observer: ProviderFailureDiagnosticObserver,
): ProviderException {
    val retryable = isRetryableStatus(statusCode)
    val retryAfterMillis = parseRetryAfterMillis(retryAfterHeader)
    val bounded = boundedBodyPreview(body).let {
        if (bodyTruncated) it.copy(truncated = true) else it
    }
    deliver(
        observer,
        ProviderFailureDiagnosticEvent(
            providerId = providerId,
            providerAlias = providerAlias,
            code = ProviderFailureCode.HTTP_REJECTED,
            statusCode = statusCode,
            retryable = retryable,
            retryAfterMillis = retryAfterMillis,
            failure = null,
            httpBodyPreview = bounded.text,
            httpBodyPreviewTruncated = bounded.truncated,
        ),
    )
    return newHttpFailure(statusCode, retryAfterHeader)
}

/**
 * Logs trusted metadata for a rejected provider response.
 *
 * [providerName] and [body] are retained for binary compatibility and are
 * deliberately excluded from the log record.
 */
@Suppress("UNUSED_PARAMETER")
fun logProviderHttpFailureDebug(
    logger: System.Logger?,
    providerName: String,
    statusCode: Int,
    body: String,
) {
    if (logger?.isLoggable(System.Logger.Level.DEBUG) != true) return
    logger.log(
        System.Logger.Level.DEBUG,
        "Provider request failed: code=HTTP_REJECTED status=$statusCode retryable=${isRetryableStatus(statusCode)}",
    )
}

/** Legacy ABI-compatible transport failure factory without observer delivery. */
@Suppress("UNUSED_PARAMETER")
fun providerTransportFailure(providerName: String, error: Throwable): ProviderException {
    error.rethrowIfCancellation()
    if (error is ProviderException && error.safeFactoryTrusted) return error
    return sanitizeTransportFailure(error)
}

/** Observer-aware transport failure factory. */
suspend fun providerTransportFailureObserved(
    providerId: String,
    error: Throwable,
    observer: ProviderFailureDiagnosticObserver,
): ProviderException = providerTransportFailureObserved(
    providerId = providerId,
    providerAlias = null,
    error = error,
    observer = observer,
)

/** Observer-aware transport factory with a diagnostic-only caller alias. */
suspend fun providerTransportFailureObserved(
    providerId: String,
    providerAlias: String?,
    error: Throwable,
    observer: ProviderFailureDiagnosticObserver,
): ProviderException {
    error.rethrowIfCancellation()
    if (error is ProviderException && error.safeFactoryTrusted) return error

    val sanitized = sanitizeTransportFailure(error)
    deliver(
        observer,
        ProviderFailureDiagnosticEvent(
            providerId = providerId,
            providerAlias = providerAlias,
            code = requireNotNull(sanitized.failureCode),
            statusCode = sanitized.statusCode,
            retryable = sanitized.retryable,
            retryAfterMillis = sanitized.retryAfterMillis,
            failure = error,
            httpBodyPreview = null,
            httpBodyPreviewTruncated = false,
        ),
    )
    return sanitized
}

private fun newHttpFailure(statusCode: Int, retryAfterHeader: String?): ProviderException =
    safeProviderFailure(
        message = "Provider request failed with HTTP $statusCode",
        code = ProviderFailureCode.HTTP_REJECTED,
        statusCode = statusCode,
        retryable = isRetryableStatus(statusCode),
        retryAfterMillis = parseRetryAfterMillis(retryAfterHeader),
    )

private fun sanitizeTransportFailure(error: Throwable): ProviderException {
    if (error is ProviderException) {
        val code = if (error.statusCode != null) {
            ProviderFailureCode.HTTP_REJECTED
        } else {
            ProviderFailureCode.UNEXPECTED_FAILURE
        }
        val message = error.statusCode?.let { "Provider request failed with HTTP $it" } ?: "Provider request failed"
        return safeProviderFailure(
            message = message,
            code = code,
            statusCode = error.statusCode,
            retryable = error.retryable,
            retryAfterMillis = error.retryAfterMillis,
        )
    }

    return when (error) {
        is HttpTimeoutException -> safeProviderFailure(
            "Provider request timed out", ProviderFailureCode.TIMEOUT, retryable = true,
        )
        is ConnectException -> safeProviderFailure(
            "Provider connection failed", ProviderFailureCode.CONNECTION_FAILED, retryable = true,
        )
        is IOException -> safeProviderFailure(
            "Provider transport failed", ProviderFailureCode.TRANSPORT_FAILED, retryable = true,
        )
        else -> safeProviderFailure(
            "Provider request failed", ProviderFailureCode.UNEXPECTED_FAILURE,
        )
    }
}

/** A bounded preview of a provider error body. */
data class BoundedProviderErrorBody(
    val text: String,
    val truncated: Boolean,
)

/**
 * Reads and closes [input], consuming at most [limitBytes] plus one sentinel
 * byte. Only the first [limitBytes] bytes are decoded as UTF-8. If the cap
 * splits a multibyte character, the diagnostic preview may end in U+FFFD.
 */
fun readErrorBodyPreview(
    input: InputStream,
    limitBytes: Int = PROVIDER_ERROR_BODY_LIMIT_BYTES,
): BoundedProviderErrorBody {
    require(limitBytes >= 0) { "limitBytes must not be negative" }
    return input.use { stream ->
        val bytes = ByteArray(limitBytes + 1)
        var read = 0
        while (read < bytes.size) {
            val count = stream.read(bytes, read, bytes.size - read)
            if (count < 0) break
            if (count == 0) {
                val single = stream.read()
                if (single < 0) break
                bytes[read++] = single.toByte()
            } else {
                read += count
            }
        }
        val retained = minOf(read, limitBytes)
        var text = String(bytes, 0, retained, Charsets.UTF_8)
        while (text.toByteArray(Charsets.UTF_8).size > limitBytes) {
            text = text.dropLast(1)
        }
        BoundedProviderErrorBody(
            text = text,
            truncated = read == limitBytes + 1,
        )
    }
}

private fun boundedBodyPreview(
    body: String,
    limitBytes: Int = PROVIDER_ERROR_BODY_LIMIT_BYTES,
): BoundedProviderErrorBody = readErrorBodyPreview(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)), limitBytes)

private suspend fun deliver(
    observer: ProviderFailureDiagnosticObserver,
    event: ProviderFailureDiagnosticEvent,
) {
    try {
        observer.record(event)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
    } catch (e: Exception) {
        e.rethrowIfCancellation()
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
