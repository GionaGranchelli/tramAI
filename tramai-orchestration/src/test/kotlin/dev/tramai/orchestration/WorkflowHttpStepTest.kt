package dev.tramai.orchestration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpResponse as JdkHttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSession
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test

class WorkflowHttpStepTest {
    @Test
    fun `http step makes a GET request and merges status into state`() {
        httpServer { exchange ->
            assertThat(exchange.requestMethod).isEqualTo("GET")
            exchange.respond(200, "ok")
        }.use { server ->
            val workflow = workflow<HttpState>("http-get") {
                outboundNetworkPolicy = localPolicy()
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
                outboundNetworkPolicy = localPolicy()
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
                outboundNetworkPolicy = localPolicy()
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
                outboundNetworkPolicy = localPolicy()
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
            Thread.sleep(5_000)
            exchange.respond(200, "slow")
        }.use { server ->
            val workflow = workflow<HttpState>("http-timeout") {
                outboundNetworkPolicy = localPolicy()
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
            .hasMessage("Workflow http step timed out")
            .hasNoCause()
        }
    }

    @Test
    fun `http step redacts authorization headers and bodies in workflow events`() {
        val observer = RecordingHttpWorkflowObserver()
        httpServer { exchange ->
            exchange.respond(200, "server-secret")
        }.use { server ->
            val workflow = workflow<HttpState>("http-redaction") {
                outboundNetworkPolicy = localPolicy()
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
            // URL path/fragment never reach workflow events.
            assertThat(renderedAttributes).doesNotContain("/secret")
        }
    }

    @Test
    fun `http step rejects non-http schemes without a hostname as validation failure`() {
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
            .hasMessage("Workflow http step validation failed")
            .hasNoCause()

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

            val thrown = org.assertj.core.api.Assertions.catchThrowable {
                runBlocking {
                    workflow.run(
                        initialState = HttpState(),
                        observer = observer,
                    )
                }
            }!!
            assertThat(thrown).isInstanceOf(WorkflowHttpException::class.java)
                .hasMessage("Workflow http step was rejected by policy")
                .hasNoCause()

            assertThat(workflowFailureCode(thrown)).isEqualTo(WorkflowStepFailureCode.POLICY_REJECTED)
            assertThat(requests.get()).isZero()
            assertThat(observer.eventNames).contains("tramai.workflow.http.request.policy.rejected")
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
                    outboundNetworkPolicy = localPolicy()
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
            .hasMessage("Workflow http step validation failed")
            .hasNoCause()

        assertThat(observer.eventNames).contains("tramai.workflow.http.request.validation.failed")
    }

    @Test
    fun `http step rejects policy-owned unsupported schemes`() {
        assertPolicyRejected("ftp://example.com/")
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
            .hasMessage("Workflow http step validation failed")
            .hasNoCause()

        assertThat(observer.eventNames).contains("tramai.workflow.http.request.validation.failed")
    }

    @Test
    fun `http step returns non-2xx responses when they are not retried`() {
        val observer = RecordingHttpWorkflowObserver()
        httpServer { exchange ->
            exchange.respond(404, "missing")
        }.use { server ->
            val workflow = workflow<HttpState>("http-not-found") {
                outboundNetworkPolicy = localPolicy()
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
                outboundNetworkPolicy = localPolicy()
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
            .hasMessage("Workflow http step validation failed")
            .hasNoCause()

        val validationEvent = observer.events.single { (eventName, _) ->
            eventName == "tramai.workflow.http.request.validation.failed"
        }
        // URL, host, path, query, and fragment never reach workflow events.
        assertThat(validationEvent.second.containsKey("url")).isFalse()
        assertThat(validationEvent.second.toString()).doesNotContain("token=secret")
        assertThat(validationEvent.second["failure_code"]).isEqualTo("workflow.step.validation_failed")
    }

    @Test
    fun `http step config rejects response limits above int max`() {
        assertThatThrownBy {
            HttpStepConfig(maxResponseBytes = Int.MAX_VALUE.toLong() + 1)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maxResponseBytes")
    }

    @Test
    fun `http step rejects loopback IPv4 127 0 0 1`() { assertPolicyRejected("http://127.0.0.1/") }
    @Test
    fun `http step rejects loopback short form 127 1`() { assertPolicyRejected("http://127.1/") }
    @Test
    fun `http step rejects loopback IPv6`() { assertPolicyRejected("http://[::1]/") }
    @Test
    fun `http step rejects RFC1918 10 8`() { assertPolicyRejected("http://10.0.0.1/") }
    @Test fun `http step rejects RFC1918 172 16 12`() {
        assertPolicyRejected("http://172.16.0.1/")
        assertPolicyRejected("http://172.31.255.255/")
    }
    @Test
    fun `http step rejects RFC1918 192 168 16`() { assertPolicyRejected("http://192.168.1.1/") }
    @Test
    fun `http step rejects cloud metadata endpoint`() { assertPolicyRejected("http://169.254.169.254/") }
    @Test
    fun `http step rejects link local IPv6 fe80 10`() { assertPolicyRejected("http://[fe80::1]/") }
    @Test
    fun `http step rejects CGNAT 100 64 10`() {
        assertPolicyRejected("http://100.64.0.1/")
        assertPolicyRejected("http://100.127.255.255/")
    }
    @Test
    fun `http step rejects IPv6 ULA fc00 7`() {
        assertPolicyRejected("http://[fc00::1]/")
        assertPolicyRejected("http://[fd00::1]/")
    }
    @Test
    fun `http step rejects alternative IPv4 decimal integer encoding`() { assertPolicyRejected("http://2130706433/") }
    @Test
    fun `http step rejects alternative IPv4 octal encoding`() { assertPolicyRejected("http://0177.0.0.1/") }
    @Test
    fun `http step rejects alternative IPv4 hex encoding`() { assertPolicyRejected("http://0x7f.0.0.1/") }
    @Test
    fun `http step rejects alternative IPv4 shortened notation`() { assertPolicyRejected("http://127.0.1/") }

    @Test
    fun `http step rejects user info authority during canonicalization`() {
        assertValidationRejected("http://user@example.com/")
        assertValidationRejected("http://trusted.example@evil.example/")
    }

    @Test
    fun `http step allowlist does not bypass private destination restrictions`() {
        assertPolicyRejected("http://127.0.0.1/", HttpStepConfig(allowedHosts = setOf("127.0.0.1")))
        httpServer { it.respond(200, "ok") }.use { server ->
            val workflow = workflow<HttpState>("http-private-allowed") {
                outboundNetworkPolicy = localPolicy()
                httpStep("fetch", HttpStepConfig(allowedHosts = setOf("127.0.0.1")),
                    { _, _ -> HttpRequest("GET", server.url("/")) },
                    { state, response, _ -> state.copy(status = response.status) })
            }.build { it }
            assertThat(runBlocking { workflow.run(HttpState()) }.status).isEqualTo(200)
        }
    }

    @Test
    fun `strict governed policy rejects non listed public host`() {
        assertPolicyRejected("http://other.example.com/", policy = OutboundNetworkPolicies.governed(setOf("api.example.com")))
        assertThatThrownBy { OutboundNetworkPolicies.governed(emptySet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `governed policy requires its allowlist even when step config has one`() {
        assertPolicyRejected("http://example.org/", HttpStepConfig(allowedHosts = setOf("example.org")),
            OutboundNetworkPolicies.governed(setOf("api.example.com")))
    }

    @Test
    fun `custom redirect following client cannot override deny by default`() {
        val privateRequests = AtomicInteger()
        httpServer { privateRequests.incrementAndGet(); it.respond(200, "private") }.use { internal ->
            httpServer { it.respond(302, ByteArray(0), mapOf("Location" to internal.url("/private"))) }.use { external ->
                val workflow = workflow<HttpState>("hostile-redirect") {
                    outboundNetworkPolicy = localPolicy()
                    httpTransport = JdkHttpTransport(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build())
                    httpStep("fetch", localHttpConfig(), { _, _ -> HttpRequest("GET", external.url("/redirect")) },
                        { state, response, _ -> state.copy(status = response.status) })
                }.build { it }
                assertPolicyRejectedRun(workflow)
                assertThat(privateRequests.get()).isZero()
            }
        }
    }

    @Test
    fun `connected address validation rejects restricted peer after safe pre resolution`() {
        var merged = false
        val workflow = workflow<HttpState>("connected-address") {
            httpTransport = FakeConnectedAddressTransport(InetAddress.getByName("127.0.0.1"))
            httpStep("fetch", request = { _, _ -> HttpRequest("GET", "http://example.com/") },
                merge = { state, _, _ -> merged = true; state })
        }.build { it }
        assertPolicyRejectedRun(workflow)
        assertThat(merged).isFalse()
    }

    @Test
    fun `connected address validation fails closed when transport stays silent`() {
        val workflow = workflow<HttpState>("silent-connected-address") {
            httpTransport = SilentConnectedAddressTransport
            httpStep("fetch", request = { _, _ -> HttpRequest("GET", "http://example.com/") },
                merge = { state, _, _ -> state })
        }.build { it }

        assertPolicyRejectedRun(workflow)
    }

    @Test
    fun `connected address validation failure closes the returned response body`() {
        val tracking = TrackingCloseInputStream(ByteArrayInputStream(ByteArray(0)))
        val silentTransport = object : HttpTransport {
            override val capability = HttpTransportCapability.CONNECTED_ADDRESS_VALIDATION
            override suspend fun send(
                httpRequest: java.net.http.HttpRequest,
                blockingDispatcher: CoroutineContext,
                onConnected: (InetAddress) -> Unit,
            ): ControlledSendResult = ControlledSendResult(fakeResponse(httpRequest, tracking))
        }
        val workflow = workflow<HttpState>("silent-connected-close") {
            httpTransport = silentTransport
            httpStep("fetch", request = { _, _ -> HttpRequest("GET", "http://example.com/") },
                merge = { state, _, _ -> state })
        }.build { it }

        assertPolicyRejectedRun(workflow)
        assertThat(tracking.closed).isTrue()
    }

    @Test
    fun `every retry attempt crosses resolved address admission`() {
        val requests = AtomicInteger()
        val countingPolicy = object : OutboundNetworkPolicy {
            var addressValidations = 0
            override fun validateTarget(target: OutboundNetworkTarget) {
                if (target.addresses.isNotEmpty()) {
                    addressValidations++
                    if (addressValidations >= 2) throw IllegalArgumentException("second admission rejected")
                }
            }
        }
        httpServer { exchange ->
            requests.incrementAndGet()
            exchange.respond(503, "busy")
        }.use { server ->
            val workflow = workflow<HttpState>("per-attempt-admission") {
                outboundNetworkPolicy = countingPolicy
                httpStep(
                    name = "fetch",
                    config = HttpStepConfig(retryOnStatus = setOf(503), maxRetries = 1),
                    request = { _, _ -> HttpRequest(method = "GET", url = server.url("/")) },
                    merge = { state, _, _ -> state },
                )
            }.build { it }

            val thrown = org.assertj.core.api.Assertions.catchThrowable { runBlocking { workflow.run(HttpState()) } }!!
            assertThat(thrown).isInstanceOf(WorkflowHttpException::class.java)
                .hasMessage("Workflow http step was rejected by policy")
                .hasNoCause()
            assertThat(workflowFailureCode(thrown)).isEqualTo(WorkflowStepFailureCode.POLICY_REJECTED)
            assertThat(requests.get()).isEqualTo(1)
        }
    }

    @Test
    fun `policy configured after http step declaration still applies`() {
        val workflow = workflow<HttpState>("frozen-policy") {
            httpStep("fetch", request = { _, _ -> HttpRequest("GET", "http://other.example.com/") },
                merge = { state, _, _ -> state })
            outboundNetworkPolicy = OutboundNetworkPolicies.governed(setOf("api.example.com"))
        }.build { it }

        assertPolicyRejectedRun(workflow)
    }

    @Test
    fun `scheme rejection happens before any DNS resolution`() {
        assertPolicyRejected("ftp://example.com/", policy = OutboundNetworkPolicies.governed(setOf("api.example.com")))
    }

    @Test
    fun `unresolvable host fails closed under default policy`() {
        assertPolicyRejected("http://this-host-does-not-exist.invalid/")
    }

    @Test
    fun `cancellation during policy rejection preserves cancellation`() {
        val cancellingPolicy = object : OutboundNetworkPolicy {
            override fun validateTarget(target: OutboundNetworkTarget) {
                throw kotlinx.coroutines.CancellationException("cancel")
            }
        }
        val workflow = workflow<HttpState>("policy-cancellation") {
            outboundNetworkPolicy = cancellingPolicy
            httpStep("fetch", request = { _, _ -> HttpRequest("GET", "http://example.com/") }, merge = { state, _, _ -> state })
        }.build { it }
        assertThatThrownBy { runBlocking { workflow.run(HttpState()) } }
            .isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
            .isNotInstanceOf(WorkflowHttpException::class.java)
    }

    @Test
    fun `policy rejection keeps rejected target details out of public error and events`() {
        val observer = RecordingHttpWorkflowObserver()
        val thrown = org.assertj.core.api.Assertions.catchThrowable {
            runBlocking { policyWorkflow("http://127.0.0.1:8080/secret?token=TOP-SECRET").run(HttpState(), observer = observer) }
        }!!
        assertThat(thrown.message).doesNotContain("TOP-SECRET", "127.0.0.1", "/secret")
        val event = observer.events.single { it.first == "tramai.workflow.http.request.policy.rejected" }
        assertThat(event.second.toString()).doesNotContain("TOP-SECRET", "127.0.0.1", "/secret")
        assertThat(event.second["failure_code"]).isEqualTo("workflow.step.policy_rejected")
    }

    @Test
    fun `branch http steps inherit the workflow outbound policy`() {
        // P1 regression: a governed workflow-level policy must reach httpStep inside branchStep,
        // otherwise governed fail-closed can be silently bypassed by routing HTTP through a branch.
        val observer = RecordingHttpWorkflowObserver()
        val workflow = workflow<HttpState>("branch-policy") {
            outboundNetworkPolicy = OutboundNetworkPolicies.governed(setOf("api.example.com"))
            branchStep(name = "route", select = { "a" }) {
                branch("a") {
                    httpStep(
                        name = "fetch",
                        request = { _, _ -> HttpRequest(method = "GET", url = "http://other.example.com/") },
                        merge = { state, _, _ -> state },
                    )
                }
            }
        }.build { it }
        val thrown = org.assertj.core.api.Assertions.catchThrowable {
            runBlocking { workflow.run(HttpState(), observer = observer) }
        }!!
        assertThat(thrown).isInstanceOf(WorkflowHttpException::class.java)
            .hasMessage("Workflow http step was rejected by policy")
            .hasNoCause()
        assertThat(workflowFailureCode(thrown)).isEqualTo(WorkflowStepFailureCode.POLICY_REJECTED)
        assertThat(observer.eventNames).contains("tramai.workflow.http.request.policy.rejected")
    }

    @Test
    fun `http step rejects IPv4 mapped IPv6 loopback`() {
        assertPolicyRejected("http://[::ffff:127.0.0.1]/")
        assertPolicyRejected("http://[::ffff:10.0.0.1]/")
    }
}

private fun localPolicy(): OutboundNetworkPolicy = OutboundNetworkPolicies.defenceInDepth(allowPrivateDestinations = true)

private fun policyWorkflow(url: String, config: HttpStepConfig = HttpStepConfig(), policy: OutboundNetworkPolicy = OutboundNetworkPolicies.defenceInDepth()) =
    workflow<HttpState>("policy-rejected") {
        outboundNetworkPolicy = policy
        httpStep("fetch", config, { _, _ -> HttpRequest("GET", url) }, { state, response, _ -> state.copy(status = response.status) })
    }.build { it }

private fun assertPolicyRejected(url: String, config: HttpStepConfig = HttpStepConfig(), policy: OutboundNetworkPolicy = OutboundNetworkPolicies.defenceInDepth()) {
    val observer = RecordingHttpWorkflowObserver()
    val thrown = org.assertj.core.api.Assertions.catchThrowable { runBlocking { policyWorkflow(url, config, policy).run(HttpState(), observer = observer) } }!!
    assertThat(thrown).isInstanceOf(WorkflowHttpException::class.java).hasMessage("Workflow http step was rejected by policy").hasNoCause()
    assertThat(workflowFailureCode(thrown)).isEqualTo(WorkflowStepFailureCode.POLICY_REJECTED)
    assertThat(observer.eventNames).contains("tramai.workflow.http.request.policy.rejected")
}

private fun assertValidationRejected(url: String) {
    val thrown = org.assertj.core.api.Assertions.catchThrowable { runBlocking { policyWorkflow(url).run(HttpState()) } }!!
    assertThat(thrown).isInstanceOf(WorkflowHttpException::class.java).hasMessage("Workflow http step validation failed").hasNoCause()
    assertThat(workflowFailureCode(thrown)).isEqualTo(WorkflowStepFailureCode.VALIDATION_FAILED)
}

private fun assertPolicyRejectedRun(workflow: Workflow<HttpState, HttpState>) {
    val thrown = org.assertj.core.api.Assertions.catchThrowable { runBlocking { workflow.run(HttpState()) } }!!
    assertThat(thrown).isInstanceOf(WorkflowHttpException::class.java).hasMessage("Workflow http step was rejected by policy").hasNoCause()
    assertThat(workflowFailureCode(thrown)).isEqualTo(WorkflowStepFailureCode.POLICY_REJECTED)
}

private class FakeConnectedAddressTransport(private val connected: InetAddress) : HttpTransport {
    override val capability = HttpTransportCapability.CONNECTED_ADDRESS_VALIDATION
    override suspend fun send(
        httpRequest: java.net.http.HttpRequest,
        blockingDispatcher: CoroutineContext,
        onConnected: (InetAddress) -> Unit,
    ): ControlledSendResult {
        onConnected(connected)
        return ControlledSendResult(fakeResponse(httpRequest))
    }
}

private object SilentConnectedAddressTransport : HttpTransport {
    override val capability = HttpTransportCapability.CONNECTED_ADDRESS_VALIDATION
    override suspend fun send(
        httpRequest: java.net.http.HttpRequest,
        blockingDispatcher: CoroutineContext,
        onConnected: (InetAddress) -> Unit,
    ): ControlledSendResult = ControlledSendResult(fakeResponse(httpRequest))
}

private fun fakeResponse(
    request: java.net.http.HttpRequest,
    body: InputStream = ByteArrayInputStream(ByteArray(0)),
): JdkHttpResponse<InputStream> = object : JdkHttpResponse<InputStream> {
    override fun statusCode() = 200
    override fun request() = request
    override fun previousResponse(): Optional<JdkHttpResponse<InputStream>> = Optional.empty()
    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
    override fun body(): InputStream = body
    override fun sslSession(): Optional<SSLSession> = Optional.empty()
    override fun uri(): URI = request.uri()
    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}

private class TrackingCloseInputStream(delegate: InputStream) : java.io.FilterInputStream(delegate) {
    var closed = false
        private set

    override fun close() {
        closed = true
        super.close()
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
