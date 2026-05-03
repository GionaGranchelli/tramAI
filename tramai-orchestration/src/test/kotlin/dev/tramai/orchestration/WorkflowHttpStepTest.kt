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
                    config = HttpStepConfig(
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
        val largeBody = "a".repeat(128)
        httpServer { exchange ->
            exchange.respond(200, largeBody)
        }.use { server ->
            val workflow = workflow<HttpState>("http-large-response") {
                httpStep(
                    name = "fetch",
                    config = HttpStepConfig(maxResponseBytes = 32),
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
            assertThat(result.body).hasSize(32)
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
                    config = HttpStepConfig(timeoutSeconds = 1),
                    request = { _, _ -> HttpRequest(method = "GET", url = server.url("/slow")) },
                    merge = { state, response, _ -> state.copy(status = response.status, body = response.body) },
                )
            }.build { it }

            assertThatThrownBy {
                runBlocking { workflow.run(HttpState()) }
            }.isInstanceOf(HttpTimeoutException::class.java)
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
            exchange.use(handler)
        })
        this.executor = executor
        start()
    }

    fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

private fun httpServer(handler: (HttpExchange) -> Unit): TestHttpServer = TestHttpServer(handler)

private fun HttpExchange.respond(status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { output -> output.write(bytes) }
}
