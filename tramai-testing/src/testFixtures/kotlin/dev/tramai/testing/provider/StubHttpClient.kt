package dev.tramai.testing.provider

import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.net.http.HttpResponse.BodySubscriber
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Deterministic, offline [HttpClient] stub used by the provider TCK.
 *
 * Each [send] call consumes one entry from the canned response queue. The
 * response body is handed back as the canned [TrackingInputStream] itself, so
 * the TCK can observe both what the adapter read and whether it closed the
 * body. A canned [IOException] simulates a transport failure.
 *
 * For cancellation tests, call [armCancellation] before invoking the adapter:
 * [send] then blocks on [cancellationRelease] and throws
 * [CancellationException] once the test releases it, proving that adapter
 * cancellation escapes as cancellation rather than being wrapped in a
 * [dev.tramai.core.exception.ProviderException].
 */
class StubHttpClient : HttpClient() {

    private val responses = ArrayDeque<CannedResponse>()
    private val requestLog = mutableListOf<RecordedRequest>()
    private val requestCounter = AtomicInteger(0)

    /** Release to let an armed [send] proceed to throw CancellationException. */
    val cancellationRelease = CompletableDeferred<Unit>()

    @Volatile
    private var cancellationArmed = false

    @Volatile
    var lastRequestBody: String? = null
        private set

    @Volatile
    var lastUri: URI? = null
        private set

    @Volatile
    var lastTimeout: Duration? = null
        private set

    @Volatile
    var lastRequestHeaders: Map<String, List<String>> = emptyMap()
        private set

    fun enqueue(
        statusCode: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        responses.addLast(CannedResponse(statusCode, TrackingInputStream.of(body), headers, null))
    }

    fun enqueue(
        statusCode: Int,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ) {
        responses.addLast(CannedResponse(statusCode, TrackingInputStream.of(body), headers, null))
    }

    fun enqueueTransportFailure(error: IOException) {
        responses.addLast(CannedResponse(0, TrackingInputStream.of(""), emptyMap(), error))
    }

    /** Canned body whose reads throw [error], simulating a mid-stream transport failure. */
    fun enqueueBodyThatFailsOnRead(error: IOException) {
        responses.addLast(
            CannedResponse(
                statusCode = 200,
                body = TrackingInputStream.of(FailingInputStream(error)),
                headers = emptyMap(),
                failure = null,
            ),
        )
    }

    /** Makes the next [send] block until [cancellationRelease], then throw CancellationException. */
    fun armCancellation() {
        cancellationArmed = true
    }

    @Volatile
    private var lastReturnedBody: TrackingInputStream? = null

    fun requestCount(): Int = requestCounter.get()

    fun lastRequest(): RecordedRequest? = requestLog.lastOrNull()

    /** Whether the body of the most recently returned response was closed by the adapter. */
    fun lastBodyClosed(): Boolean = lastReturnedBody?.closed == true

    fun lastBodyCloseCount(): Int = lastReturnedBody?.closeCount() ?: 0

    override fun <T : Any?> send(request: HttpRequest, responseBodyHandler: BodyHandler<T>): HttpResponse<T> {
        record(request)
        val canned = responses.removeFirstOrNull()
            ?: throw IllegalStateException("StubHttpClient: no canned response queued")

        if (canned.failure != null) {
            requestCounter.incrementAndGet()
            throw canned.failure
        }

        if (cancellationArmed) {
            runBlocking { cancellationRelease.await() }
            requestCounter.incrementAndGet()
            throw CancellationException("test cancellation")
        }

        requestCounter.incrementAndGet()
        lastReturnedBody = canned.body
        @Suppress("UNCHECKED_CAST")
        return StubHttpResponse(
            request = request,
            responseStatus = canned.statusCode,
            headers = canned.headers,
            responseBody = canned.body as T,
        )
    }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(
        UnsupportedOperationException("StubHttpClient is synchronous-only"),
    )

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(
        UnsupportedOperationException("StubHttpClient is synchronous-only"),
    )

    override fun connectTimeout(): Optional<Duration> = Optional.empty()

    override fun followRedirects(): HttpClient.Redirect = HttpClient.Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_2

    override fun executor(): Optional<java.util.concurrent.Executor> = Optional.empty()

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun newWebSocketBuilder(): WebSocket.Builder =
        throw UnsupportedOperationException("StubHttpClient does not support websockets")

    private fun record(request: HttpRequest) {
        lastRequestBody = request.bodyPublisher().map { publisher -> readBodyPublisher(publisher) }.orElse(null)
        lastUri = request.uri()
        lastTimeout = request.timeout().orElse(null)
        lastRequestHeaders = request.headers().map()
        requestLog.add(
            RecordedRequest(
                method = request.method(),
                uri = request.uri(),
                headers = request.headers().map(),
                body = lastRequestBody,
            ),
        )
    }

    data class RecordedRequest(
        val method: String,
        val uri: URI,
        val headers: Map<String, List<String>>,
        val body: String?,
    )

    private data class CannedResponse(
        val statusCode: Int,
        val body: TrackingInputStream,
        val headers: Map<String, String>,
        val failure: IOException?,
    )

    private class StubHttpResponse<T>(
        private val request: HttpRequest,
        private val responseStatus: Int,
        headers: Map<String, String>,
        private val responseBody: T,
    ) : HttpResponse<T> {
        private val responseHeaders: HttpHeaders = HttpHeaders.of(
            headers.mapValues { listOf(it.value) },
        ) { _, _ -> true }

        override fun statusCode(): Int = responseStatus
        override fun body(): T = responseBody
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()
        override fun headers(): HttpHeaders = responseHeaders
        override fun sslSession(): Optional<javax.net.ssl.SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_2
    }
}

/** Reads and closes [publisher]'s content as UTF-8 text. */
private fun readBodyPublisher(publisher: HttpRequest.BodyPublisher): String {
    val bytes = java.io.ByteArrayOutputStream()
    // A BodyPublisher may deliver asynchronously — wait for the terminal
    // signal instead of racing on the buffer (a racy recorder could capture
    // "" or a partial body for a legitimate async publisher).
    val completed = java.util.concurrent.CompletableFuture<Unit>()
    val subscriber = object : Flow.Subscriber<ByteBuffer> {
        override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
        override fun onNext(item: ByteBuffer) {
            val copy = ByteArray(item.remaining())
            item.duplicate().get(copy)
            bytes.write(copy)
        }

        override fun onError(throwable: Throwable) {
            completed.completeExceptionally(throwable)
        }

        override fun onComplete() {
            completed.complete(Unit)
        }
    }
    publisher.subscribe(subscriber)
    completed.join()
    return String(bytes.toByteArray(), StandardCharsets.UTF_8)
}

/** Input stream that fails on first read with the given [error]. */
internal class FailingInputStream(private val error: IOException) : java.io.InputStream() {
    override fun read(): Int = throw error
    override fun read(b: ByteArray, off: Int, len: Int): Int = throw error
    override fun close() { /* no-op: nothing to release */ }
}
