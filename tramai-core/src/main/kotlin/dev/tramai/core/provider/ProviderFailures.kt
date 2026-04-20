package dev.tramai.core.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelRequest
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Applies the normalized Tramai timeout, when present, to a provider HTTP request.
 */
fun HttpRequest.Builder.applyTramaiTimeout(request: ModelRequest): HttpRequest.Builder = apply {
    request.timeoutMillis?.let { timeout(Duration.ofMillis(it)) }
}

/**
 * Creates a normalized provider failure for an HTTP status response.
 */
fun providerHttpFailure(
    providerName: String,
    statusCode: Int,
    body: String,
    retryAfterHeader: String? = null,
): ProviderException = ProviderException(
    message = "$providerName returned HTTP $statusCode: $body",
    statusCode = statusCode,
    retryable = isRetryableStatus(statusCode),
    retryAfterMillis = parseRetryAfterMillis(retryAfterHeader),
)

/**
 * Creates a normalized provider failure for transport-layer errors.
 */
fun providerTransportFailure(
    providerName: String,
    error: Throwable,
): ProviderException = when (error) {
    is ProviderException -> error
    is HttpTimeoutException -> ProviderException(
        message = "$providerName request timed out: ${error.message ?: error::class.simpleName}",
        cause = error,
        retryable = true,
    )
    is ConnectException -> ProviderException(
        message = "$providerName connection failed: ${error.message ?: error::class.simpleName}",
        cause = error,
        retryable = true,
    )
    is IOException -> ProviderException(
        message = "$providerName transport failed: ${error.message ?: error::class.simpleName}",
        cause = error,
        retryable = true,
    )
    else -> ProviderException(
        message = "$providerName failed: ${error.message ?: error::class.simpleName}",
        cause = error,
    )
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
