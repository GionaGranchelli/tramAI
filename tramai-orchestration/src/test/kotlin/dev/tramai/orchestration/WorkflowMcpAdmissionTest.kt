package dev.tramai.orchestration

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.typeOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E3-E4: an MCP step takes three separate durable grants -- the step (E11), the server start (E4), the tool invocation
 * (E3) -- and no grant authorizes another.
 *
 * **Attribution without invention.** `McpTransportProvider.connect(toolCall)` never receives a step name, and the
 * default subprocess provider's own failure message says `<transport>`. Neither is usable as attribution, so the E4
 * grant is taken at the dispatch site -- inside the step, which knows its own name -- immediately before `connect()`.
 * No coroutine-context plumbing and no change to the public transport contract were needed.
 *
 * **Determinism.** Ordering is forced by a store decorator that commits a cancellation immediately after the n-th
 * granted admission: #1 is the step fence, #2 the server start, #3 the tool invocation. The transport is a counting
 * wrapper over an in-process MCP server on pipes, and the server's tool handler counts its own executions, so "the
 * tool never ran" is a zero on a counter rather than an absent log line. No subprocess is spawned.
 */
class WorkflowMcpAdmissionTest {
    private val servers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        servers.forEach { closeable -> closeable.close() }
        servers.clear()
    }

    @Test
    fun `an mcp step takes three separate grants`() {
        val server = echoServer()
        val provider = CountingProvider(server.provider)
        val store = McpAdmissionStore(InMemoryWorkflowCheckpointStore())

        val result =
            runBlocking {
                mcpWorkflow(provider, config(reconnect = false))
                    .run(McpState(), governed(), NoOpWorkflowObserver, persistence(store))
            }

        assertEquals("echo: hi", result.result?.content, "the tool really ran")
        assertEquals(1, provider.connectCount, "one server start")
        assertEquals(1, server.toolInvocations.get(), "one tool invocation")
        assertEquals(3, store.admitCount, "step, server start and tool call: three decisions, not one")
        assertEquals(
            3,
            store.grantedSequences.distinct().size,
            "every grant carries its own sequence: ${store.grantedSequences}",
        )
    }

    @Test
    fun `an ordinary run spawns and calls without asking any authority`() {
        val server = echoServer()
        val provider = CountingProvider(server.provider)
        val store = McpAdmissionStore(InMemoryWorkflowCheckpointStore())

        val result =
            runBlocking {
                mcpWorkflow(
                    provider,
                    config(reconnect = false),
                ).run(McpState(), WorkflowContext(workflowId = "run-1"), NoOpWorkflowObserver, persistence(store))
            }

        assertEquals("echo: hi", result.result?.content)
        assertEquals(1, provider.connectCount)
        assertEquals(0, store.admitCount, "an ungoverned run must ask no authority at all")
    }

    @Test
    fun `cancellation before the server start prevents the spawn`() {
        val server = echoServer()
        val provider = CountingProvider(server.provider)
        // Admission #1 is the step fence; the cancellation commits straight after it, so the server start is refused.
        val store = McpAdmissionStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 1)

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking {
                    mcpWorkflow(provider, config(reconnect = false))
                        .run(McpState(), governed(), NoOpWorkflowObserver, persistence(store))
                }
            }

        assertEquals(0, provider.connectCount, "a refused server start must not reach the provider")
        assertEquals(0, server.toolInvocations.get(), "and no tool can run without a server")
        assertTrue(
            error.message!!.contains("cannot dispatch $MCP_SERVER_START_EFFECT_KIND for step 'call'"),
            "the refusal names the effect and the step: ${error.message}",
        )
    }

    @Test
    fun `cancellation between the server start and the tool call prevents the invocation`() {
        val server = echoServer()
        val provider = CountingProvider(server.provider)
        // Admission #2 is the server start; cancellation commits after it, before the tool invocation is admitted.
        val store = McpAdmissionStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 2)

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking {
                    mcpWorkflow(provider, config(reconnect = false))
                        .run(McpState(), governed(), NoOpWorkflowObserver, persistence(store))
                }
            }

        assertEquals(1, provider.connectCount, "the server start was already admitted, so it happened")
        assertEquals(0, server.toolInvocations.get(), "the invocation was not")
        assertTrue(
            error.message!!.contains("cannot dispatch $MCP_TOOL_EFFECT_KIND for step 'call'"),
            "a server-start grant does not authorize the tool call: ${error.message}",
        )
    }

    @Test
    fun `a reconnect takes the server start and the tool call again`() {
        val server = echoServer()
        val provider = CountingProvider(server.provider, failFirstConnect = true)
        val store = McpAdmissionStore(InMemoryWorkflowCheckpointStore())

        val result =
            runBlocking {
                mcpWorkflow(provider, config(reconnect = true))
                    .run(McpState(), governed(), NoOpWorkflowObserver, persistence(store))
            }

        assertEquals("echo: hi", result.result?.content, "the second attempt is what makes the run succeed")
        assertEquals(2, provider.connectCount, "a reconnect is a new server start")
        assertEquals(1, server.toolInvocations.get(), "the first attempt never reached the tool")
        assertEquals(
            4,
            store.admitCount,
            "step + two server starts + one tool call: the failed attempt consumed its start grant and no tool grant",
        )
    }

    @Test
    fun `cancellation between reconnect attempts prevents the second spawn`() {
        val server = echoServer()
        val provider = CountingProvider(server.provider, failFirstConnect = true)
        // Admission #2 is the first attempt's server start; cancellation commits before the reconnect asks again.
        val store = McpAdmissionStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 2)

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking {
                    mcpWorkflow(provider, config(reconnect = true))
                        .run(McpState(), governed(), NoOpWorkflowObserver, persistence(store))
                }
            }

        assertEquals(1, provider.connectCount, "the reconnect never spawns a second server")
        assertEquals(0, server.toolInvocations.get())
        assertTrue(
            error.message!!.contains("cannot dispatch $MCP_SERVER_START_EFFECT_KIND"),
            "the second start is refused by the authority, not classified as a reconnectable failure: ${error.message}",
        )
    }

    @Test
    fun `a refusal surfacing from a transport is not classified and not retried`() {
        val store = McpAdmissionStore(InMemoryWorkflowCheckpointStore())
        // Reconnect is enabled and a transient failure would normally be retried: an authoritative refusal must not be
        // mistaken for one, and must arrive unchanged rather than wrapped as a WorkflowMcpException.
        val refusingProvider =
            object : McpTransportProvider {
                var connects = 0

                override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                    connects += 1
                    throw WorkflowAdmissionRefusedException("run 'run-1' terminal: refused in a transport")
                }
            }

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking {
                    mcpWorkflow(
                        refusingProvider,
                        config(reconnect = true),
                    ).run(McpState(), governed(), NoOpWorkflowObserver, persistence(store))
                }
            }

        assertTrue(
            error.message!!.contains("refused in a transport"),
            "the refusal arrives unchanged: ${error.message}",
        )
        assertEquals(1, refusingProvider.connects, "an authoritative refusal is never a reconnect reason")
    }

    @Test
    fun `a refusal racing a terminating transport is still a refusal, not a timeout`() {
        val server = echoServer()
        // The connection reports that the transport has begun terminating -- the state in which the step classifies a
        // concurrent transport error as the timeout. An authoritative refusal must not be swallowed by that
        // classification, so it has to arrive as itself.
        val terminatingProvider =
            object : McpTransportProvider {
                var connects = 0

                override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                    connects += 1
                    return server.provider.connect(toolCall).apply { terminationRequested = { true } }
                }
            }
        // Admission #2 is the server start; cancellation commits after it, so the tool call is refused.
        val store = McpAdmissionStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 2)

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking {
                    mcpWorkflow(terminatingProvider, config(reconnect = false))
                        .run(McpState(), governed(), NoOpWorkflowObserver, persistence(store))
                }
            }

        assertTrue(
            error.message!!.contains("cannot dispatch $MCP_TOOL_EFFECT_KIND"),
            "the refusal keeps its own type instead of becoming a timeout failure: ${error.message}",
        )
        assertEquals(1, terminatingProvider.connects, "the start was admitted before the cancellation committed")
        assertEquals(0, server.toolInvocations.get(), "no tool ran")
    }

    @Test
    fun `a store that cannot admit refuses the spawn`() {
        val server = echoServer()
        val provider = CountingProvider(server.provider)
        val store = McpAdmissionStore(InMemoryWorkflowCheckpointStore(), refuseAdmissionsFromAdmit = 2)

        assertFailsWith<WorkflowCancellationUnsupportedException> {
            runBlocking {
                mcpWorkflow(provider, config(reconnect = false))
                    .run(McpState(), governed(), NoOpWorkflowObserver, persistence(store))
            }
        }

        assertEquals(0, provider.connectCount, "no durable authority means no server start")
    }

    @Test
    fun `a governed run with no durable session never spawns`() {
        val server = echoServer()
        val provider = CountingProvider(server.provider)

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                // No persistence at all: the run still claims the durable guarantees, so it has nothing to ask.
                runBlocking {
                    mcpWorkflow(provider, config(reconnect = false))
                        .run(McpState(), governed(), NoOpWorkflowObserver, persistence = null)
                }
            }

        assertEquals(0, provider.connectCount, "fail-closed, not fail-open")
        assertTrue(
            error.message!!.contains("no durable checkpoint authority"),
            "fail-closed reason: ${error.message}",
        )
    }

    @Test
    fun `the two mcp effects are distinct kinds and distinct permit types`() {
        val start = WorkflowMcpServerStartPermit(stepName = "call", admissionSequence = 1L)
        val tool = WorkflowMcpToolPermit(stepName = "call", admissionSequence = 2L)

        assertEquals(MCP_SERVER_START_EFFECT_KIND, start.effectKind)
        assertEquals(MCP_TOOL_EFFECT_KIND, tool.effectKind)
        assertTrue(
            start::class != tool::class,
            "a server-start permit must not be presentable at the tool-call site",
        )
        assertTrue(start.tryConsume(), "the first consumption authorizes one dispatch")
        assertTrue(tool.tryConsume(), "each permit is independent")
        val reuse = assertFailsWith<IllegalStateException> { start.tryConsume() }
        assertTrue(
            reuse.message!!.contains("already consumed"),
            "reuse must name the programming error: ${reuse.message}",
        )
    }

    @Test
    fun `each mcp dispatch site asks only its own capability, once`() {
        val sources = mainSourceFiles()
        val startSites = sources.filter { (_, text) -> text.contains(".admitServerStart(") }
        val toolSites = sources.filter { (_, text) -> text.contains(".admitToolCall(") }

        assertEquals(
            listOf("McpStep.kt"),
            startSites.map { (file, _) -> file.fileName.toString() },
            "the server start is admitted in the step, at the connect() dispatch site",
        )
        assertEquals(
            listOf("McpStep.kt"),
            toolSites.map { (file, _) -> file.fileName.toString() },
            "the tool call is admitted in the step, at the callTool() dispatch site",
        )
        assertEquals(
            1,
            startSites
                .single()
                .second
                .windowed(".admitServerStart(".length)
                .count { it == ".admitServerStart(" },
            "one start grant per attempt",
        )
        assertEquals(
            1,
            toolSites
                .single()
                .second
                .windowed(".admitToolCall(".length)
                .count { it == ".admitToolCall(" },
            "one tool grant per attempt",
        )
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private fun config(reconnect: Boolean): McpStepConfig =
        McpStepConfig(timeoutSeconds = 30, reconnect = reconnect, allowedCommands = setOf("unused"))

    private fun mcpWorkflow(
        provider: McpTransportProvider,
        config: McpStepConfig,
    ): Workflow<McpState, McpState> {
        val step: InternalWorkflowStep<McpState> =
            McpWorkflowStep(
                name = "call",
                definition =
                    McpToolCallDefinition(
                        serverCommand = listOf("unused"),
                        toolName = "echo",
                        argumentKeys = setOf("message"),
                    ),
                config = config,
                toolCallBuilder = { _, _ ->
                    McpToolCall(
                        serverCommand = listOf("unused"),
                        toolName = "echo",
                        arguments = mapOf("message" to "hi"),
                    )
                },
                merge = { state, result, _ -> state.copy(result = result) },
                transportProvider = provider,
            )
        return Workflow(
            name = "checkout",
            definitionVersion = "1",
            stateType = typeOf<McpState>(),
            resultType = typeOf<McpState>(),
            schedule = null,
            steps = listOf(step),
            resultSelector = { it },
            stopPolicy = StopPolicy(),
            clock = Clock.systemUTC(),
            externalStepExecutorResolver = NoOpExternalStepExecutorResolver,
        )
    }

    private fun persistence(store: WorkflowCheckpointStore): WorkflowPersistence<McpState> =
        WorkflowPersistence(checkpointStore = store, stateCodec = McpStateCodec)

    private fun governed(): GovernedRun = GovernedRun(WorkflowContext(workflowId = "run-1"), MCP_GOVERNED)

    private fun echoServer(): EchoServer {
        val serverToClient = PipedOutputStream()
        val clientInput = PipedInputStream(serverToClient)
        val clientToServer = PipedOutputStream()
        val serverInput = PipedInputStream(clientToServer)
        val pipeCloseable =
            AutoCloseable {
                runCatching { clientInput.close() }
                runCatching { clientToServer.close() }
                runCatching { serverToClient.close() }
                runCatching { serverInput.close() }
            }
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val invocations = AtomicInteger(0)
        val server = echoServerDefinition(invocations)
        val session = AtomicReference<ServerSession?>(null)
        val sessionJob =
            scope.launch {
                session.set(
                    server.createSession(
                        StdioServerTransport(serverInput.asSource().buffered(), serverToClient.asSink().buffered()),
                    ),
                )
            }
        val closeable =
            AutoCloseable {
                runCatching { runBlocking { session.get()?.close() } }
                scope.coroutineContext[Job]?.cancel()
                sessionJob.cancel()
                pipeCloseable.close()
            }
        servers += closeable
        val provider =
            object : McpTransportProvider {
                override suspend fun connect(toolCall: McpToolCall): McpTransportConnection =
                    McpTransportConnection(
                        input = clientInput.asSource().buffered(),
                        output = clientToServer.asSink().buffered(),
                    )
            }
        return EchoServer(provider, invocations)
    }

    /** The server side of the fixture: one echo tool that counts its own executions. */
    private fun echoServerDefinition(invocations: AtomicInteger): Server =
        Server(
            serverInfo = Implementation(name = "test-echo", version = "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(false))),
        ) {
            addTool(
                Tool(
                    name = "echo",
                    description = "Echoes the message back",
                    inputSchema =
                        io.modelcontextprotocol.kotlin.sdk.types.ToolSchema(
                            properties =
                                buildJsonObject {
                                    put("message", buildJsonObject { put("type", JsonPrimitive("string")) })
                                },
                            required = listOf("message"),
                        ),
                ),
            ) { request ->
                invocations.incrementAndGet()
                val message =
                    request.params.arguments
                        ?.get("message")
                        ?.jsonPrimitive
                        ?.content ?: ""
                CallToolResult(
                    content = listOf(TextContent("echo: $message")),
                    structuredContent = buildJsonObject { put("echoed", JsonPrimitive(message)) },
                )
            }
        }

    private class EchoServer(
        val provider: McpTransportProvider,
        val toolInvocations: AtomicInteger,
    )

    /**
     * Counts how often a connection was requested. The count is the evidence for every "no server was spawned" claim:
     * the provider is the only path to a server start, and the default one runs `ProcessBuilder.start()` inside it.
     */
    private class CountingProvider(
        private val delegate: McpTransportProvider,
        private val failFirstConnect: Boolean = false,
    ) : McpTransportProvider {
        private var connects = 0

        val connectCount: Int
            get() = connects

        override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
            connects += 1
            if (failFirstConnect && connects == 1) {
                // A plain transport failure: classified TRANSPORT_FAILED, which is what makes it reconnectable.
                throw IOException("test transport failure before the server starts")
            }
            return delegate.connect(toolCall)
        }
    }
}

private data class McpState(
    val result: McpToolResult? = null,
)

private object McpStateCodec : WorkflowStateCodec<McpState> {
    override fun encode(state: McpState): String = state.result?.content.orEmpty()

    override fun decode(payload: String): McpState {
        val result = McpToolResult(content = payload, structuredContent = null)
        return McpState(result)
    }
}

private suspend fun mcpCancelRun(store: WorkflowCheckpointStore) {
    val current = store.load("checkout", "run-1") ?: return
    store.cancel(
        WorkflowCancellationRequest(
            workflowName = "checkout",
            workflowId = current.workflowId,
            expectedRevision = current.revision,
            expectedGeneration = current.checkpointGeneration,
            actor = "operator",
            reason = "stopped",
            cancelledAtEpochMillis = 0L,
        ),
    )
}

/**
 * Decorates the real store to force an admission ordering and to count what the runtime asked for.
 *
 * [cancelAfterAdmit] commits a cancellation immediately after that granted admission, which is how "the step was
 * admitted but the server was not" and "the server started but the tool call was not admitted" become deterministic.
 */
private class McpAdmissionStore(
    private val delegate: WorkflowCheckpointStore,
    private val cancelAfterAdmit: Int? = null,
    private val refuseAdmissionsFromAdmit: Int? = null,
) : WorkflowCheckpointStore by delegate {
    private val mutex = Mutex()
    private var attempts = 0
    private val granted = mutableListOf<Long>()

    val admitCount: Int
        get() = attempts

    val grantedSequences: List<Long>
        get() = granted

    override suspend fun admit(request: WorkflowAdmissionRequest): WorkflowAdmission =
        mutex.withLock {
            attempts += 1
            if (refuseAdmissionsFromAdmit != null && attempts >= refuseAdmissionsFromAdmit) {
                throw WorkflowCancellationUnsupportedException(
                    "${delegate::class.simpleName} cannot admit in this test",
                )
            }
            val admission = delegate.admit(request)
            if (admission.admitted) {
                granted += admission.sequence
                if (cancelAfterAdmit == attempts) {
                    mcpCancelRun(delegate)
                }
            }
            admission
        }
}

private val MCP_GOVERNED =
    GovernedRunIdentity(
        deployment =
            WorkloadDeploymentIdentity(
                workloadId = WorkloadId("checkout"),
                configuration = WorkloadConfigurationIdentity(ConfigurationId("conf"), ConfigurationVersion("1")),
                environmentId = EnvironmentId("test"),
                deploymentId = DeploymentId("dep"),
            ),
        runId = RunId("run-1"),
    )

/** Every orchestration main source file, keyed by path, for the dispatch-site assertions. */
private fun mainSourceFiles(): List<Pair<Path, String>> {
    val candidates =
        listOf(
            Path.of("src/main/kotlin/dev/tramai/orchestration"),
            Path.of("tramai-orchestration/src/main/kotlin/dev/tramai/orchestration"),
        )
    val root =
        candidates.firstOrNull { Files.isDirectory(it) }
            ?: throw AssertionError("none of the orchestration source roots exist: $candidates")
    return Files.list(root).use { stream ->
        stream
            .filter { it.fileName.toString().endsWith(".kt") }
            .map { it to Files.readString(it) }
            .toList()
    }
}
