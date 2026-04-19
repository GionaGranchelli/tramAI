package dev.tramai.core.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelRequest
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.time.Duration

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
): ProviderException = ProviderException(
    message = "$providerName returned HTTP $statusCode: $body",
    statusCode = statusCode,
    retryable = isRetryableStatus(statusCode),
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
