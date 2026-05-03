package dev.tramai.orchestration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.net.InetSocketAddress
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

class WorkflowHttpStepTest {
    @Test
    fun `http step makes a GET request and merges status into state`() {
        httpServer { exchange ->
            assertThat(exchange.requestMethod).isEqualTo("GET")
            exchange.respond(200, "ok")
        }.use { server ->
            val workflow = workflow<HttpState>("http-get") {
                httpStep(
                    name = "fetch",
                    config = localHttpConfig(),
                    request = { _, _ -> HttpRequest(method = "GET", url = server.url("/status")) },
                    merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
                )
            }.build { it }

            val result = runBlocking { workflow.run(HttpState()) }

            assertThat(result.status).isEqualTo(200)
            assertThat(result.body).isEqualTo("ok")
        }
    }

    @Test
    fun `http step sends POST body and merges response`() {
        httpServer { exchange ->
            val requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val contentType = exchange.requestHeaders.getFirst("Content-Type")
            assertThat(exchange.requestMethod).isEqualTo("POST")
            exchange.respond(201, "$contentType|$requestBody")
        }.use { server ->
            val workflow = workflow<HttpState>("http-post") {
                httpStep(
                    name = "create",
                    config = localHttpConfig(),
                    request = { _, _ ->
                        HttpRequest(
                            method = "POST",
                            url = server.url("/submit"),
                            headers = mapOf("Content-Type" to "application/json"),
                            body = """{"message":"hello"}""",
                        )
                    },
                    merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
                )
            }.build { it }

            val result = runBlocking { workflow.run(HttpState()) }

            assertThat(result.status).isEqualTo(201)
            assertThat(result.body).isEqualTo("""application/json|{"message":"hello"}""")
        }
    }

    @Test
    fun `http step retries on configured status and succeeds`() {
        val attempts = AtomicInteger()
        httpServer { exchange ->
            val attempt = attempts.incrementAndGet()
            if (attempt < 3) {
                exchange.respond(503, "retry-$attempt")
            } else {
                exchange.respond(200, "done")
            }
        }.use { server ->
            val workflow = workflow<HttpState>("http-retry") {
                httpStep(
                    name = "fetch",
                    config = localHttpConfig(
                        retryOnStatus = setOf(503),
                        maxRetries = 2,
                    ),
                    request = { _, _ -> HttpRequest(method = "GET", url = server.url("/retry")) },
                    merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
                )
            }.build { it }

            val result = runBlocking { workflow.run(HttpState()) }

            assertThat(attempts.get()).isEqualTo(3)
            assertThat(result.status).isEqualTo(200)
            assertThat(result.body).isEqualTo("done")
        }
    }

    @Test
    fun `http step truncates oversized responses and continues`() {
        val observer = RecordingHttpWorkflowObserver()
        val largeBody = ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() }
        httpServer { exchange ->
            exchange.respond(200, largeBody)
        }.use { server ->
            val workflow = workflow<HttpState>("http-large-response") {
                httpStep(
                    name = "fetch",
                    config = localHttpConfig(maxResponseBytes = 1_024),
                    request = { _, _ -> HttpRequest(method = "GET", url = server.url("/large")) },
                    merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
                )
            }.build { it }

            val result = runBlocking {
                workflow.run(
                    initialState = HttpState(),
                    observer = observer,
                )
            }

            assertThat(result.status).isEqualTo(200)
            assertThat(result.body).hasSize(1_024)
            assertThat(observer.eventNames).contains("tramai.workflow.http.response.truncated")
        }
    }

    @Test
    fun `http step throws on timeout`() {
        httpServer { exchange ->
            Thread.sleep(1_500)
            exchange.respond(200, "slow")
        }.use { server ->
            val workflow = workflow<HttpState>("http-timeout") {
                httpStep(
                    name = "fetch",
                    config = localHttpConfig(timeoutSeconds = 1),
                    request = { _, _ -> HttpRequest(method = "GET", url = server.url("/slow")) },
                    merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
                )
            }.build { it }

        assertThatThrownBy {
            runBlocking { workflow.run(HttpState()) }
        }.isInstanceOf(WorkflowHttpException::class.java)
            .hasCauseInstanceOf(HttpTimeoutException::class.java)
        }
    }

    @Test
    fun `http step redacts authorization headers and bodies in workflow events`() {
        val observer = RecordingHttpWorkflowObserver()
        httpServer { exchange ->
            exchange.respond(200, "server-secret")
        }.use { server ->
            val workflow = workflow<HttpState>("http-redaction") {
                httpStep(
                    name = "fetch",
                    config = localHttpConfig(),
                    request = { _, _ ->
                        HttpRequest(
                            method = "POST",
                            url = server.url("/secret?token=query-secret"),
                            headers = mapOf("Authorization" to "Bearer top-secret"),
                            body = "request-secret",
                        )
                    },
                    merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
                )
            }.build { it }

            runBlocking {
                workflow.run(
                    initialState = HttpState(),
                    observer = observer,
                )
            }

            val renderedAttributes = observer.events.joinToString(separator = "\n") { (_, attributes) ->
                attributes.entries.joinToString(separator = "\n") { "${it.key}=${it.value}" }
            }
            assertThat(renderedAttributes).doesNotContain("top-secret")
            assertThat(renderedAttributes).doesNotContain("request-secret")
            assertThat(renderedAttributes).doesNotContain("server-secret")
            assertThat(renderedAttributes).doesNotContain("query-secret")
            assertThat(renderedAttributes).contains("/secret")
        }
    }

    @Test
    fun `http step rejects non-http schemes and records validation failure`() {
        val observer = RecordingHttpWorkflowObserver()
        val workflow = workflow<HttpState>("http-invalid-scheme") {
            httpStep(
                name = "fetch",
                request = { _, _ -> HttpRequest(method = "GET", url = "file:///tmp/secret.txt") },
                merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
            )
        }.build { it }

        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = HttpState(),
                    observer = observer,
                )
            }
        }.isInstanceOf(WorkflowHttpException::class.java)
            .hasMessageContaining("step 'fetch'")
            .hasMessageContaining("/tmp/secret.txt")
            .cause()
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThat(observer.eventNames).contains("tramai.workflow.http.request.validation.failed")
    }

    @Test
    fun `http step rejects private hosts by default before sending the request`() {
        val observer = RecordingHttpWorkflowObserver()
        val requests = AtomicInteger()
        httpServer { exchange ->
            requests.incrementAndGet()
            exchange.respond(200, "ok")
        }.use { server ->
            val workflow = workflow<HttpState>("http-private-host") {
                httpStep(
                    name = "fetch",
                    request = { _, _ -> HttpRequest(method = "GET", url = server.url("/internal")) },
                    merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
                )
            }.build { it }

            assertThatThrownBy {
                runBlocking {
                    workflow.run(
                        initialState = HttpState(),
                        observer = observer,
                    )
                }
            }.isInstanceOf(WorkflowHttpException::class.java)
                .hasMessageContaining("step 'fetch'")
                .hasMessageContaining("127.0.0.1")
                .cause()
                .isInstanceOf(IllegalArgumentException::class.java)

            assertThat(requests.get()).isZero()
            assertThat(observer.eventNames).contains("tramai.workflow.http.request.validation.failed")
        }
    }

    @Test
    fun `http step does not follow redirects to a different host`() {
        val internalRequests = AtomicInteger()
        httpServer { exchange ->
            internalRequests.incrementAndGet()
            exchange.respond(200, "internal")
        }.use { internalServer ->
            httpServer { exchange ->
                exchange.respond(
                    status = 302,
                    body = ByteArray(0),
                    headers = mapOf("Location" to "http://localhost:${internalServer.port}/internal"),
                )
            }.use { externalServer ->
                val workflow = workflow<HttpState>("http-redirect") {
                    httpStep(
                        name = "fetch",
                        config = localHttpConfig(),
                        request = { _, _ -> HttpRequest(method = "GET", url = externalServer.url("/redirect")) },
                        merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
                    )
                }.build { it }

                val result = runBlocking { workflow.run(HttpState()) }

                assertThat(result.status).isEqualTo(302)
                assertThat(result.body).isNull()
                assertThat(internalRequests.get()).isZero()
            }
        }
    }

    @Test
    fun `http step rejects unsupported HTTP methods`() {
        val observer = RecordingHttpWorkflowObserver()
        val workflow = workflow<HttpState>("http-unsupported-method") {
            httpStep(
                name = "fetch",
                request = { _, _ -> HttpRequest(method = "TRACE", url = "https://example.com/resource") },
                merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
            )
        }.build { it }

        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = HttpState(),
                    observer = observer,
                )
            }
        }.isInstanceOf(WorkflowHttpException::class.java)
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("TRACE")

        assertThat(observer.eventNames).contains("tramai.workflow.http.request.validation.failed")
    }

    @Test
    fun `http step rejects malformed URLs with empty host`() {
        val observer = RecordingHttpWorkflowObserver()
        val workflow = workflow<HttpState>("http-malformed-url") {
            httpStep(
                name = "fetch",
                request = { _, _ -> HttpRequest(method = "GET", url = "https:///missing-host") },
                merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
            )
        }.build { it }

        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = HttpState(),
                    observer = observer,
                )
            }
        }.isInstanceOf(WorkflowHttpException::class.java)
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("missing-host")

        assertThat(observer.eventNames).contains("tramai.workflow.http.request.validation.failed")
    }

    @Test
    fun `http step returns non-2xx responses when they are not retried`() {
        val observer = RecordingHttpWorkflowObserver()
        httpServer { exchange ->
            exchange.respond(404, "missing")
        }.use { server ->
            val workflow = workflow<HttpState>("http-not-found") {
                httpStep(
                    name = "fetch",
                    config = localHttpConfig(),
                    request = { _, _ -> HttpRequest(method = "GET", url = server.url("/missing")) },
                    merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
                )
            }.build { it }

            val result = runBlocking {
                workflow.run(
                    initialState = HttpState(),
                    observer = observer,
                )
            }

            assertThat(result.status).isEqualTo(404)
            assertThat(result.body).isEqualTo("missing")
            assertThat(observer.eventNames).doesNotContain("tramai.workflow.http.request.retrying")
        }
    }

    @Test
    fun `http step decodes non-utf8 responses using replacement characters`() {
        val invalidUtf8 = byteArrayOf(0x48, 0x69, 0x20, 0xC3.toByte(), 0x28)
        httpServer { exchange ->
            exchange.respond(200, invalidUtf8)
        }.use { server ->
            val workflow = workflow<HttpState>("http-non-utf8") {
                httpStep(
                    name = "fetch",
                    config = localHttpConfig(),
                    request = { _, _ -> HttpRequest(method = "GET", url = server.url("/bytes")) },
                    merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
                )
            }.build { it }

            val result = runBlocking { workflow.run(HttpState()) }

            assertThat(result.status).isEqualTo(200)
            assertThat(result.body).isEqualTo("Hi \uFFFD(")
        }
    }

    @Test
    fun `http step validation uses a safe redacted URL when query stripping fails`() {
        val observer = RecordingHttpWorkflowObserver()
        val workflow = workflow<HttpState>("http-strip-query-edge") {
            httpStep(
                name = "fetch",
                request = { _, _ -> HttpRequest(method = "GET", url = "http://exa mple.com/path?token=secret") },
                merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
            )
        }.build { it }

        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = HttpState(),
                    observer = observer,
                )
            }
        }.isInstanceOf(WorkflowHttpException::class.java)
            .hasCauseInstanceOf(IllegalArgumentException::class.java)

        val validationEvent = observer.events.single { (eventName, _) ->
            eventName == "tramai.workflow.http.request.validation.failed"
        }
        assertThat(validationEvent.second["url"]).isEqualTo("http://exa mple.com/path")
        assertThat(validationEvent.second["url"].toString()).doesNotContain("token=secret")
    }

    @Test
    fun `http step config rejects response limits above int max`() {
        assertThatThrownBy {
            HttpStepConfig(maxResponseBytes = Int.MAX_VALUE.toLong() + 1)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maxResponseBytes")
    }
}

private data class HttpState(
    val status: Int? = null,
    val body: String? = null,
)

private class RecordingHttpWorkflowObserver : WorkflowObserver {
    val eventNames = mutableListOf<String>()
    val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    override fun onWorkflowEvent(
        workflowName: String,
        name: String,
        attributes: Map<String, Any?>,
        context: WorkflowContext,
    ) {
        eventNames += name
        events += name to attributes
    }
}

private class TestHttpServer(
    handler: (HttpExchange) -> Unit,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(0), 0).apply {
        createContext("/", HttpHandler { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        })
        this.executor = executor
        start()
    }

    val port: Int
        get() = server.address.port

    fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

private fun httpServer(handler: (HttpExchange) -> Unit): TestHttpServer = TestHttpServer(handler)

private fun HttpExchange.respond(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
    respond(status, body.toByteArray(StandardCharsets.UTF_8), headers)
}

private fun HttpExchange.respond(status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
    headers.forEach { (headerName, headerValue) ->
        responseHeaders.add(headerName, headerValue)
    }
    sendResponseHeaders(status, body.size.toLong())
    responseBody.use { output -> output.write(body) }
}

private fun localHttpConfig(
    timeoutSeconds: Long = 30,
    maxResponseBytes: Long = 1_048_576,
    retryOnStatus: Set<Int> = emptySet(),
    maxRetries: Int = 0,
    allowedHosts: Set<String>? = setOf("127.0.0.1", "localhost"),
): HttpStepConfig = HttpStepConfig(
    timeoutSeconds = timeoutSeconds,
    maxResponseBytes = maxResponseBytes,
    retryOnStatus = retryOnStatus,
    maxRetries = maxRetries,
    allowedHosts = allowedHosts,
)
