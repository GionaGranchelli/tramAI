package dev.tramai.core.provider.transport

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver
import dev.tramai.core.provider.applyTramaiTimeout
import dev.tramai.core.provider.logProviderHttpFailureDebug
import dev.tramai.core.provider.providerHttpFailureObserved
import dev.tramai.core.provider.providerTransportFailureObserved
import dev.tramai.core.provider.readErrorBodyPreview
import java.io.BufferedReader
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Low-level HTTP/stream transport machinery shared by provider adapters.
 *
 * This package centralises transport invariants only: request framing,
 * timeout application, rejected-response handling, bounded body reads, body
 * closure, and SSE framing. Endpoint construction, authentication, JSON wire
 * formats, tool semantics, usage extraction, and stream interpretation remain
 * inside each adapter so the wire contract stays visible in provider source.
 *
 * This is cross-module implementation machinery for the built-in adapters,
 * not stable application-facing API; it may change or move in any release.
 */

/**
 * Builds a JSON HTTP request for a provider endpoint with the normalized
 * Tramai timeout applied.
 *
 * Authentication and provider-protocol headers are added by the caller so a
 * reviewer can still see the wire contract from the provider source:
 *
 * ```
 * providerJsonRequest(uri, request, jsonBody)
 *     .header("Authorization", "Bearer ...")   // provider-specific auth
 *     .build()
 * ```
 */
@ExperimentalProviderTransportApi
fun providerJsonRequest(
    uri: URI,
    request: ModelRequest,
    body: String,
): HttpRequest.Builder = HttpRequest.newBuilder()
    .uri(uri)
    .header("Content-Type", "application/json")
    .applyTramaiTimeout(request)
    .POST(HttpRequest.BodyPublishers.ofString(body))

/**
 * Builds the safe failure for a rejected (non-2xx) provider HTTP response.
 *
 * Performs the shared rejected-response lifecycle:
 * 1. reads a bounded preview of the error body (8 KiB diagnostic bound);
 * 2. closes the response body on every exit;
 * 3. logs debug metadata only (never the body content);
 * 4. delivers the diagnostic observer event (fail-open);
 * 5. propagates `Retry-After` through the returned exception.
 *
 * A body-read failure is normalized to a transport failure. Cancellation is
 * preserved. The caller decides whether the returned [ProviderException] is
 * thrown (non-streaming) or emitted as a `StreamChunk.Error` (streaming);
 * this utility does not decide provider protocol behaviour.
 */
@ExperimentalProviderTransportApi
suspend fun rejectedProviderHttpResponse(
    providerId: String,
    providerAlias: String?,
    response: HttpResponse<InputStream>,
    observer: ProviderFailureDiagnosticObserver,
    logger: System.Logger? = null,
): ProviderException {
    val errorBody = try {
        readErrorBodyPreview(response.body())
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        return providerTransportFailureObserved(providerId, providerAlias, error, observer)
    }
    logProviderHttpFailureDebug(logger, providerId, response.statusCode(), errorBody.text)
    return providerHttpFailureObserved(
        providerId = providerId,
        providerAlias = providerAlias,
        statusCode = response.statusCode(),
        body = errorBody.text,
        bodyTruncated = errorBody.truncated,
        retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null),
        observer = observer,
    )
}

/**
 * Returns the payload of the next SSE `data: ` line in [reader], or `null` at
 * end of stream.
 *
 * SSE framing only: unrelated fields (`event:`, `id:`, comments, blank lines)
 * are skipped, the `data: ` prefix is stripped, and the payload is returned
 * verbatim (trimmed). Payload interpretation — `[DONE]`, delta parsing,
 * Anthropic event semantics — is the caller's responsibility. The reader is
 * intentionally left open; the caller owns closure (typically `use`).
 */
@ExperimentalProviderTransportApi
fun readSseDataPayload(reader: BufferedReader): String? {
    while (true) {
        val line = reader.readLine() ?: return null
        sseDataPayload(line)?.let { return it }
    }
}

/**
 * Returns the payload of an SSE `data: ` line, or `null` for any other line.
 * Callers that need event context (e.g. Anthropic) pair this with
 * [sseEventName] in their own loop.
 */
@ExperimentalProviderTransportApi
fun sseDataPayload(line: String): String? =
    if (line.startsWith("data: ")) line.substring(6).trim() else null

/**
 * Returns the event name of an SSE `event: ` line, or `null` for any other
 * line. Callers that need event context (e.g. Anthropic) pair this with
 * [sseDataPayload] in their own loop.
 */
@ExperimentalProviderTransportApi
fun sseEventName(line: String): String? =
    if (line.startsWith("event: ")) line.substring(7).trim() else null
