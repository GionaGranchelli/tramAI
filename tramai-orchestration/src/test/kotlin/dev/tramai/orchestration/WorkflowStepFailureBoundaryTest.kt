package dev.tramai.orchestration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.typeOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import kotlin.test.Test

class WorkflowStepFailureBoundaryTest {
    @Test
    fun `every external step family exposes only its fixed failure contract`() {
        WorkflowStepKind.entries.forEach { kind ->
            WorkflowStepFailureCode.entries.forEach { code ->
                val failure = safeWorkflowStepFailure(
                    kind, code, fixedWorkflowStepMessage(kind, code), "step", 1,
                )

                assertThat(failure.message).isEqualTo(fixedWorkflowStepMessage(kind, code))
                assertThat(failure.cause).isNull()
                assertThat(workflowFailureCode(failure)).isEqualTo(code)
                assertThat(assertsNoFixture(failure.message)).isTrue()
            }
        }
    }

    @Test
    fun `diagnostic preview obeys byte boundary without invalid utf8`() {
        val exact = boundedWorkflowDetailPreview("a".repeat(8_192))
        val overflow = boundedWorkflowDetailPreview("a".repeat(8_193))
        val split = boundedWorkflowDetailPreview("a".repeat(8_191) + "€")

        assertThat(exact.truncated).isFalse()
        assertThat(exact.text.encodeToByteArray()).hasSize(8_192)
        assertThat(overflow.truncated).isTrue()
        assertThat(overflow.text.encodeToByteArray()).hasSize(8_192)
        assertThat(split.truncated).isTrue()
        assertThat(split.text).isEqualTo("a".repeat(8_191))
    }

    @Test
    fun `observer failures are fail open while its cancellation is not active`() {
        val event = WorkflowStepFailureDiagnosticEvent(
            workflowName = "w", stepName = "s", kind = WorkflowStepKind.HTTP,
            code = WorkflowStepFailureCode.TRANSPORT_FAILED, attempt = 1, willRetryOrReconnect = false,
            failure = IllegalStateException(SECRET_FIXTURE), detailPreview = SECRET_FIXTURE, detailTruncated = false,
        )
        runBlocking {
            deliverWorkflowStepFailure(WorkflowStepFailureDiagnosticObserver { throw IllegalStateException("observer") }, event)
            deliverWorkflowStepFailure(WorkflowStepFailureDiagnosticObserver { throw CancellationException("synthetic") }, event)
        }
    }

    // ─── contract items 3–14 ────────────────────────────────────────────

    @Test
    fun `caller constructed workflow http exception is re-sanitized at the boundary`() {
        val diagnostics = BoundaryDiagnosticObserver()
        val original = WorkflowHttpException(
            stepName = "fetch",
            redactedUrl = "https://$SECRET_FIXTURE",
            attempt = 1,
            cause = IllegalArgumentException(SECRET_FIXTURE),
        )
        val workflow = workflow<BoundaryState>("http-re-sanitize") {
            failureDiagnosticObserver = diagnostics
            httpStep(
                name = "fetch",
                config = HttpStepConfig(),
                request = { _, _ -> throw original },
                merge = { state, response, _ -> state.copy(status = response.status) },
            )
        }.build { it }

        val thrown = catchThrowable { runBlocking { workflow.run(BoundaryState()) } }

        assertThat(thrown).isInstanceOf(WorkflowHttpException::class.java)
            .isNotSameAs(original)
            .hasMessage("Workflow http step input preparation failed")
            .hasNoCause()
        assertThat(workflowFailureCode(thrown!!)).isEqualTo(WorkflowStepFailureCode.PREPARATION_FAILED)
        assertThat(assertsNoFixture(thrown.message)).isTrue()

        val event = diagnostics.single()
        assertThat(event.code).isEqualTo(WorkflowStepFailureCode.PREPARATION_FAILED)
        assertThat(event.failure).isSameAs(original)
        assertThat(event.detailPreview).contains(SECRET_FIXTURE)
    }

    @Test
    fun `diagnostic is delivered exactly once per failed attempt across a reconnect`() {
        val diagnostics = BoundaryDiagnosticObserver()
        val workflow = boundaryMcpWorkflow(
            McpWorkflowStep(
                name = "echo",
                definition = McpToolCallDefinition(serverCommand = listOf("server"), toolName = "echo"),
                config = McpStepConfig(reconnect = true, allowedCommands = setOf("server")),
                toolCallBuilder = { _, _ -> McpToolCall(serverCommand = listOf("server"), toolName = "echo") },
                merge = { state, _, _ -> state },
                transportProvider = failingTransport { IOException("mcp transport $SECRET_FIXTURE") },
            ),
            diagnostics,
        )

        val thrown = catchThrowable { runBlocking { workflow.run(BoundaryState()) } }

        assertThat(thrown).isInstanceOf(WorkflowMcpException::class.java)
            .hasMessage("Workflow mcp step transport failed")
            .hasNoCause()
        assertThat(workflowFailureCode(thrown!!)).isEqualTo(WorkflowStepFailureCode.TRANSPORT_FAILED)

        assertThat(diagnostics.events).hasSize(2)
        assertThat(diagnostics.events.map { it.attempt }).containsExactly(1, 2)
        assertThat(diagnostics.events.map { it.willRetryOrReconnect }).containsExactly(true, false)
        diagnostics.events.forEach { event ->
            assertThat(event.code).isEqualTo(WorkflowStepFailureCode.TRANSPORT_FAILED)
        }
        assertThat(diagnostics.events.first().detailPreview).contains(SECRET_FIXTURE)
    }

    @Test
    fun `observer fake cancellation while the job is active is fail open`() {
        val workflow = workflow<BoundaryState>("http-fake-cancellation") {
            failureDiagnosticObserver = WorkflowStepFailureDiagnosticObserver {
                throw CancellationException("synthetic observer cancellation")
            }
            httpStep(
                name = "fetch",
                config = HttpStepConfig(),
                request = { _, _ -> throw IOException("boom $SECRET_FIXTURE") },
                merge = { state, response, _ -> state.copy(status = response.status) },
            )
        }.build { it }

        val thrown = catchThrowable { runBlocking { workflow.run(BoundaryState()) } }

        assertThat(thrown).isInstanceOf(WorkflowHttpException::class.java)
            .hasMessage("Workflow http step input preparation failed")
            .hasNoCause()
        assertThat(workflowFailureCode(thrown!!)).isEqualTo(WorkflowStepFailureCode.PREPARATION_FAILED)
    }

    @Test
    fun `real parent cancellation during diagnostic delivery stays the primary outcome`() {
        val workflow = workflow<BoundaryState>("http-real-cancellation") {
            failureDiagnosticObserver = WorkflowStepFailureDiagnosticObserver {
                currentCoroutineContext().job.parent?.cancel()
            }
            httpStep(
                name = "fetch",
                config = HttpStepConfig(),
                request = { _, _ -> throw IOException("boom") },
                merge = { state, response, _ -> state.copy(status = response.status) },
            )
        }.build { it }

        runBlocking {
            val outer = Job()
            val job = launch(outer) { workflow.run(BoundaryState()) }
            job.join()
            assertThat(job.isCancelled).isTrue()
        }
    }

    @Test
    fun `http transport failures emit a single event and never retry`() {
        val diagnostics = BoundaryDiagnosticObserver()
        val workflow = workflow<BoundaryState>("http-transport-failure") {
            failureDiagnosticObserver = diagnostics
            httpStep(
                name = "fetch",
                config = HttpStepConfig(allowedHosts = setOf("127.0.0.1"), maxRetries = 3),
                request = { _, _ -> HttpRequest(method = "GET", url = "http://127.0.0.1:1/unreachable") },
                merge = { state, response, _ -> state.copy(status = response.status) },
            )
        }.build { it }

        val thrown = catchThrowable { runBlocking { workflow.run(BoundaryState()) } }

        assertThat(thrown).isInstanceOf(WorkflowHttpException::class.java)
            .hasMessage("Workflow http step transport failed")
            .hasNoCause()
        val event = diagnostics.single()
        assertThat(event.attempt).isEqualTo(1)
        assertThat(event.willRetryOrReconnect).isFalse()
    }

    @Test
    fun `http status retries are unchanged and merge failure reports the final attempt`() {
        val diagnostics = BoundaryDiagnosticObserver()
        val attempts = AtomicInteger()
        httpServer { exchange ->
            attempts.incrementAndGet()
            exchange.respond(503, "busy")
        }.use { server ->
            val workflow = workflow<BoundaryState>("http-status-retry") {
                failureDiagnosticObserver = diagnostics
                httpStep(
                    name = "fetch",
                    config = HttpStepConfig(
                        retryOnStatus = setOf(503),
                        maxRetries = 1,
                        allowedHosts = setOf("127.0.0.1"),
                    ),
                    request = { _, _ -> HttpRequest(method = "GET", url = server.url("/retry")) },
                    merge = { _, _, _ -> error("merge boom $SECRET_FIXTURE") },
                )
            }.build { it }

            val thrown = catchThrowable { runBlocking { workflow.run(BoundaryState()) } }

            assertThat(thrown).isInstanceOf(WorkflowHttpException::class.java)
                .hasMessage("Workflow http step result handling failed")
                .hasNoCause()
            assertThat(workflowFailureCode(thrown!!)).isEqualTo(WorkflowStepFailureCode.RESULT_HANDLING_FAILED)
            assertThat(attempts.get()).isEqualTo(2)
            val event = diagnostics.single()
            assertThat(event.attempt).isEqualTo(2)
            assertThat(event.willRetryOrReconnect).isFalse()
            assertThat(event.detailPreview).contains(SECRET_FIXTURE)
        }
    }

    @Test
    fun `http non retryable status codes are not retried`() {
        val attempts = AtomicInteger()
        httpServer { exchange ->
            attempts.incrementAndGet()
            exchange.respond(503, "busy")
        }.use { server ->
            val workflow = workflow<BoundaryState>("http-no-retry") {
                httpStep(
                    name = "fetch",
                    config = HttpStepConfig(maxRetries = 3, allowedHosts = setOf("127.0.0.1")),
                    request = { _, _ -> HttpRequest(method = "GET", url = server.url("/busy")) },
                    merge = { state, response, _ -> state.copy(status = response.status) },
                )
            }.build { it }

            val result = runBlocking { workflow.run(BoundaryState()) }

            assertThat(attempts.get()).isEqualTo(1)
            assertThat(result.status).isEqualTo(503)
        }
    }

    @Test
    fun `mcp typed classification decides reconnects by failure type`() {
        // TIMEOUT never reconnects.
        assertThat(mcpIsTransientForReconnect(CancellationException("timed out"))).isFalse()
        // TRANSPORT_FAILED on attempt 1 reconnects.
        assertThat(mcpIsTransientForReconnect(IOException("transport"))).isTrue()
        // CLEANUP_FAILED (post-completion cleanup) never reconnects.
        assertThat(mcpIsTransientForReconnect(mcpPostCallCleanupException())).isFalse()
        // Cancellation never reconnects.
        assertThat(mcpIsTransientForReconnect(CancellationException("cancelled"))).isFalse()
        // A possibly-executed tool is never called again (post-call cleanup wrap).
        assertThat(mcpIsTransientForReconnect(mcpPostCallCleanupException(IOException("close failed")))).isFalse()
        // Framework-wrapped transport errors remain reconnectable.
        assertThat(mcpIsTransientForReconnect(WorkflowMcpException("echo", "failed to start MCP server"))).isTrue()
        // Untyped legacy/provider exceptions keep their historical reconnect:
        // a subprocess-start failure (untyped WorkflowMcpException from the
        // transport provider) is transient and reconnects.
        assertThat(mcpIsTransientForReconnect(WorkflowMcpException("echo", "failed to start MCP server", IOException("start")))).isTrue()
        // Typed non-transport codes never reconnect.
        val typedTimeout = WorkflowMcpException("echo", "fixed")
        typedTimeout.failureCode = WorkflowStepFailureCode.TIMEOUT
        assertThat(mcpIsTransientForReconnect(typedTimeout)).isFalse()
        val typedCleanup = WorkflowMcpException("echo", "fixed")
        typedCleanup.failureCode = WorkflowStepFailureCode.CLEANUP_FAILED
        assertThat(mcpIsTransientForReconnect(typedCleanup)).isFalse()
    }

    @Test
    fun `mcp timeout never reconnects and cancellation emits no diagnostic`() {
        val timeoutDiagnostics = BoundaryDiagnosticObserver()
        val timeoutWorkflow = boundaryMcpWorkflow(
            McpWorkflowStep(
                name = "echo",
                definition = McpToolCallDefinition(serverCommand = listOf("server"), toolName = "echo"),
                config = McpStepConfig(reconnect = true, timeoutSeconds = 1, allowedCommands = setOf("server")),
                toolCallBuilder = { _, _ -> McpToolCall(serverCommand = listOf("server"), toolName = "echo") },
                merge = { state, _, _ -> state },
                transportProvider = object : McpTransportProvider {
                    override suspend fun connect(toolCall: McpToolCall): McpTransportConnection =
                        awaitCancellation()
                },
            ),
            timeoutDiagnostics,
        )
        val timeoutThrown = catchThrowable { runBlocking { timeoutWorkflow.run(BoundaryState()) } }
        assertThat(timeoutThrown).isInstanceOf(WorkflowMcpException::class.java)
            .hasMessage("Workflow mcp step timed out")
            .hasNoCause()
        assertThat(workflowFailureCode(timeoutThrown!!)).isEqualTo(WorkflowStepFailureCode.TIMEOUT)
        val timeoutEvent = timeoutDiagnostics.single()
        assertThat(timeoutEvent.attempt).isEqualTo(1)
        assertThat(timeoutEvent.willRetryOrReconnect).isFalse()

        val cancelDiagnostics = BoundaryDiagnosticObserver()
        val cancelWorkflow = boundaryMcpWorkflow(
            McpWorkflowStep(
                name = "echo",
                definition = McpToolCallDefinition(serverCommand = listOf("server"), toolName = "echo"),
                config = McpStepConfig(reconnect = true, allowedCommands = setOf("server")),
                toolCallBuilder = { _, _ -> McpToolCall(serverCommand = listOf("server"), toolName = "echo") },
                merge = { state, _, _ -> state },
                transportProvider = failingTransport { CancellationException("parent cancelled") },
            ),
            cancelDiagnostics,
        )
        val cancelThrown = catchThrowable { runBlocking { cancelWorkflow.run(BoundaryState()) } }
        assertThat(cancelThrown).isInstanceOf(CancellationException::class.java)
        assertThat(cancelDiagnostics.events).isEmpty()
    }

    @Test
    fun `builder failure diagnostic observer is snapshotted at build time`() {
        val observerA = BoundaryDiagnosticObserver()
        val observerB = BoundaryDiagnosticObserver()
        val builder = workflow<BoundaryState>("http-snapshot") {
            failureDiagnosticObserver = observerA
            httpStep(
                name = "fetch",
                config = HttpStepConfig(),
                request = { _, _ -> throw IOException("boom") },
                merge = { state, response, _ -> state.copy(status = response.status) },
            )
        }
        val workflow = builder.build { it }
        builder.failureDiagnosticObserver = observerB

        val thrown = catchThrowable { runBlocking { workflow.run(BoundaryState()) } }

        assertThat(thrown).isInstanceOf(WorkflowHttpException::class.java)
            .hasMessage("Workflow http step input preparation failed")
        assertThat(observerA.events).hasSize(1)
        assertThat(observerB.events).isEmpty()
    }

    @Test
    fun `each family maps at least one failure phase to its fixed code and message`() {
        val httpDiagnostics = BoundaryDiagnosticObserver()
        assertSurfacedFailure(
            workflow<BoundaryState>("http") {
                failureDiagnosticObserver = httpDiagnostics
                httpStep(
                    name = "fetch",
                    config = HttpStepConfig(),
                    request = { _, _ -> throw IOException("boom $SECRET_FIXTURE") },
                    merge = { state, response, _ -> state.copy(status = response.status) },
                )
            }.build { it },
            httpDiagnostics,
            WorkflowHttpException::class.java,
            WorkflowStepFailureCode.PREPARATION_FAILED,
            "Workflow http step input preparation failed",
        )

        val shellDiagnostics = BoundaryDiagnosticObserver()
        assertSurfacedFailure(
            workflow<BoundaryState>("shell") {
                failureDiagnosticObserver = shellDiagnostics
                shellStep(
                    name = "echo",
                    config = ShellStepConfig(allowedCommands = setOf("pwd")),
                    definition = ShellCommandDefinition(executable = "echo"),
                    command = { _, _ -> throw IOException("boom $SECRET_FIXTURE") },
                    merge = { state, result, _ -> state.copy(result = result) },
                )
            }.build { it },
            shellDiagnostics,
            WorkflowShellException::class.java,
            WorkflowStepFailureCode.PREPARATION_FAILED,
            "Workflow shell step input preparation failed",
        )

        val mcpDiagnostics = BoundaryDiagnosticObserver()
        assertSurfacedFailure(
            boundaryMcpWorkflow(
                McpWorkflowStep(
                    name = "echo",
                    definition = McpToolCallDefinition(serverCommand = listOf("server"), toolName = "echo"),
                    config = McpStepConfig(reconnect = true),
                    toolCallBuilder = { _, _ -> throw IOException("boom $SECRET_FIXTURE") },
                    merge = { state, _, _ -> state },
                    transportProvider = failingTransport { IOException("unused") },
                ),
                mcpDiagnostics,
            ),
            mcpDiagnostics,
            WorkflowMcpException::class.java,
            WorkflowStepFailureCode.PREPARATION_FAILED,
            "Workflow mcp step input preparation failed",
        )

        val codexDiagnostics = BoundaryDiagnosticObserver()
        assertSurfacedFailure(
            workflow<BoundaryState>("codex") {
                failureDiagnosticObserver = codexDiagnostics
                codexStep(
                    name = "review-ui",
                    config = CodexStepConfig(cliPath = "/bin/true"),
                    prompt = { _, _ -> throw IOException("boom $SECRET_FIXTURE") },
                    merge = { state, response, _ -> state.copy(output = response) },
                )
            }.build { it },
            codexDiagnostics,
            WorkflowCodexException::class.java,
            WorkflowStepFailureCode.PREPARATION_FAILED,
            "Workflow codex step input preparation failed",
        )

        val hermesDiagnostics = BoundaryDiagnosticObserver()
        assertSurfacedFailure(
            workflow<BoundaryState>("hermes") {
                failureDiagnosticObserver = hermesDiagnostics
                hermesStep(
                    name = "review-ui",
                    config = HermesStepConfig(cliPath = "/bin/true"),
                    prompt = { _, _ -> throw IOException("boom $SECRET_FIXTURE") },
                    merge = { state, response, _ -> state.copy(output = response) },
                )
            }.build { it },
            hermesDiagnostics,
            WorkflowHermesException::class.java,
            WorkflowStepFailureCode.PREPARATION_FAILED,
            "Workflow hermes step input preparation failed",
        )
    }

    @Test
    fun `v0_5_0 binary compatibility fixture exists and its test class is present`() {
        assertThat(javaClass.classLoader.getResource("binary-compat/fixture-v0.5.0.jar")).isNotNull()
        assertThat(Class.forName("dev.tramai.orchestration.BinaryCompatibilityFixtureTest")).isNotNull()
    }

    @Test
    fun `process families map process start failures to start failed`() {
        val shellDiagnostics = BoundaryDiagnosticObserver()
        assertSurfacedFailure(
            workflow<BoundaryState>("shell-start") {
                failureDiagnosticObserver = shellDiagnostics
                shellStep(
                    name = "missing",
                    config = ShellStepConfig(allowedCommands = setOf("definitely-not-present-binary")),
                    definition = ShellCommandDefinition(executable = "definitely-not-present-binary"),
                    command = { _, _ -> ShellCommand(listOf("definitely-not-present-binary")) },
                    merge = { state, result, _ -> state.copy(result = result) },
                )
            }.build { it },
            shellDiagnostics,
            WorkflowShellException::class.java,
            WorkflowStepFailureCode.START_FAILED,
            "Workflow shell step process could not be started",
        )

        val codexDiagnostics = BoundaryDiagnosticObserver()
        assertSurfacedFailure(
            workflow<BoundaryState>("codex-start") {
                failureDiagnosticObserver = codexDiagnostics
                codexStep(
                    name = "review-ui",
                    config = CodexStepConfig(cliPath = "/definitely-not-present-binary"),
                    prompt = { _, _ -> "p" },
                    merge = { state, response, _ -> state.copy(output = response) },
                )
            }.build { it },
            codexDiagnostics,
            WorkflowCodexException::class.java,
            WorkflowStepFailureCode.START_FAILED,
            "Workflow codex step process could not be started",
        )

        val hermesDiagnostics = BoundaryDiagnosticObserver()
        assertSurfacedFailure(
            workflow<BoundaryState>("hermes-start") {
                failureDiagnosticObserver = hermesDiagnostics
                hermesStep(
                    name = "review-ui",
                    config = HermesStepConfig(cliPath = "/definitely-not-present-binary"),
                    prompt = { _, _ -> "p" },
                    merge = { state, response, _ -> state.copy(output = response) },
                )
            }.build { it },
            hermesDiagnostics,
            WorkflowHermesException::class.java,
            WorkflowStepFailureCode.START_FAILED,
            "Workflow hermes step process could not be started",
        )

        val mcpDiagnostics = BoundaryDiagnosticObserver()
        assertSurfacedFailure(
            boundaryMcpWorkflow(
                McpWorkflowStep(
                    name = "echo",
                    definition = McpToolCallDefinition(serverCommand = listOf("server"), toolName = "echo"),
                    config = McpStepConfig(reconnect = true),
                    toolCallBuilder = { _, _ ->
                        McpToolCall(serverCommand = listOf("server"), toolName = "other-tool")
                    },
                    merge = { state, _, _ -> state },
                    transportProvider = failingTransport { IOException("unused") },
                ),
                mcpDiagnostics,
            ),
            mcpDiagnostics,
            WorkflowMcpException::class.java,
            WorkflowStepFailureCode.VALIDATION_FAILED,
            "Workflow mcp step validation failed",
        )
    }

}

private data class BoundaryState(
    val status: Int? = null,
    val result: ShellResult? = null,
    val mcpResult: McpToolResult? = null,
    val output: String? = null,
)

private class BoundaryDiagnosticObserver : WorkflowStepFailureDiagnosticObserver {
    val events = mutableListOf<WorkflowStepFailureDiagnosticEvent>()

    override suspend fun onFailure(event: WorkflowStepFailureDiagnosticEvent) {
        events += event
    }

    fun single(): WorkflowStepFailureDiagnosticEvent = events.single()
}

private fun boundaryMcpWorkflow(
    step: InternalWorkflowStep<BoundaryState>,
    diagnosticObserver: WorkflowStepFailureDiagnosticObserver = NoOpWorkflowStepFailureDiagnosticObserver,
): Workflow<BoundaryState, BoundaryState> = Workflow(
    name = "boundary-mcp-workflow",
    definitionVersion = "1",
    stateType = typeOf<BoundaryState>(),
    resultType = typeOf<BoundaryState>(),
    schedule = null,
    steps = listOf(step),
    resultSelector = { it },
    stopPolicy = StopPolicy(),
    clock = Clock.systemUTC(),
    externalStepExecutorResolver = NoOpExternalStepExecutorResolver,
    failureDiagnosticObserver = diagnosticObserver,
)

private fun failingTransport(connectError: () -> Throwable): McpTransportProvider = object : McpTransportProvider {
    override suspend fun connect(toolCall: McpToolCall): McpTransportConnection = throw connectError()
}

private fun assertSurfacedFailure(
    workflow: Workflow<BoundaryState, BoundaryState>,
    diagnostics: BoundaryDiagnosticObserver,
    expectedType: Class<out Throwable>,
    expectedCode: WorkflowStepFailureCode,
    expectedMessage: String,
) {
    val thrown = catchThrowable { runBlocking { workflow.run(BoundaryState()) } }
    assertThat(thrown).isInstanceOf(expectedType)
        .hasMessage(expectedMessage)
        .hasNoCause()
    assertThat(thrown!!.suppressed).isEmpty()
    assertThat(workflowFailureCode(thrown)).isEqualTo(expectedCode)
    assertThat(assertsNoFixture(thrown.message)).isTrue()
    assertThat(diagnostics.single().code).isEqualTo(expectedCode)
}

// The typed reconnect classifier in McpStep.kt is file-private; the contract
// tests drive it directly via reflection on the compiled facade.
private fun mcpIsTransientForReconnect(error: Throwable): Boolean {
    val method = Class.forName("dev.tramai.orchestration.McpStepKt").declaredMethods.single {
        it.name == "isTransientForReconnect" && it.parameterCount == 1
    }
    check(method.trySetAccessible()) { "cannot access McpStepKt.isTransientForReconnect" }
    return method.invoke(null, error) as Boolean
}

private fun mcpPostCallCleanupException(cause: Throwable = IOException("cleanup failed")): Throwable =
    Class.forName("dev.tramai.orchestration.McpPostCallCleanupException")
        .getDeclaredConstructor(Throwable::class.java)
        .newInstance(cause) as Throwable

private class BoundaryTestHttpServer(
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

    fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

private fun httpServer(handler: (HttpExchange) -> Unit): BoundaryTestHttpServer = BoundaryTestHttpServer(handler)

private fun HttpExchange.respond(status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { output -> output.write(bytes) }
}
