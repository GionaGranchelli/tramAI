package dev.tramai.orchestration

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Clock
import kotlin.reflect.typeOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class WorkflowMcpStepTest {
    private val servers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        servers.clear()
    }

    @Test
    fun `mcp step calls a tool and returns result`() {
        val (transportProvider, _) = createEchoServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "echo",
            toolCallBuilder = { _, _ ->
                McpToolCall(
                    serverCommand = listOf("unused"),
                    toolName = "echo",
                    arguments = mapOf("message" to "hello-world"),
                )
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))
        val result = runBlocking { workflow.run(McpState()) }

        assertThat(result.result?.content).contains("hello-world")
        assertThat(result.result?.isError).isFalse()
    }

    @Test
    fun `mcp step returns error result when tool not found`() {
        val (transportProvider, _) = createEchoServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "missing",
            config = McpStepConfig(reconnect = false),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("unused"), toolName = "nonexistent_tool")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))
        val result = runBlocking { workflow.run(McpState()) }

        assertThat(result.result?.isError).isTrue()
    }

    @Test
    fun `mcp step times out if tool exceeds timeout`() {
        val (transportProvider, _) = createSlowServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "slow",
            config = McpStepConfig(timeoutSeconds = 1, reconnect = false),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("unused"), toolName = "delay")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))

        assertThatThrownBy {
            runBlocking { workflow.run(McpState()) }
        }.isInstanceOf(WorkflowMcpException::class.java)
            .hasMessageContaining("timed out")
    }

    @Test
    fun `mcp step emits observer events`() {
        val (transportProvider, _) = createEchoServer()
        val observer = RecordingMcpObserver()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "echo",
            toolCallBuilder = { _, _ ->
                McpToolCall(
                    serverCommand = listOf("unused"),
                    toolName = "echo",
                    arguments = mapOf("message" to "emit"),
                )
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))
        runBlocking { workflow.run(McpState(), observer = observer) }

        assertThat(observer.eventNames).contains(
            "tramai.workflow.mcp.started",
            "tramai.workflow.mcp.completed",
        )
        val startedEvent = observer.events.first { it.first == "tramai.workflow.mcp.started" }
        assertThat(startedEvent.second["step_name"]).isEqualTo("echo")
        assertThat(startedEvent.second["tool_name"]).isEqualTo("echo")
    }

    @Test
    fun `mcp step validates tool allowlist`() {
        val (transportProvider, _) = createEchoServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "echo",
            config = McpStepConfig(toolAllowlist = setOf("approved_tool"), reconnect = false),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("unused"), toolName = "echo")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))

        assertThatThrownBy {
            runBlocking { workflow.run(McpState()) }
        }.isInstanceOf(WorkflowMcpException::class.java)
            .hasMessageContaining("not in the allowlist")
    }

    @Test
    fun `mcp step handles structured content`() {
        val (transportProvider, _) = createJsonServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "json",
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("unused"), toolName = "get_data")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))
        val result = runBlocking { workflow.run(McpState()) }

        assertThat(result.result?.structuredContent).isNotNull()
        assertThat(result.result?.structuredContent).contains("key")
        assertThat(result.result?.structuredContent).contains("value")
    }

    // ─── test infrastructure ────────────────────────────────────────────

    private data class McpState(
        val result: McpToolResult? = null,
    )

    private fun buildWorkflow(
        steps: List<InternalWorkflowStep<McpState>>,
    ): Workflow<McpState, McpState> = Workflow(
        name = "test-mcp-workflow",
        definitionVersion = "1",
        stateType = typeOf<McpState>(),
        resultType = typeOf<McpState>(),
        schedule = null,
        steps = steps,
        resultSelector = { it },
        stopPolicy = StopPolicy(),
        clock = Clock.systemUTC(),
    )

    private class RecordingMcpObserver : WorkflowObserver {
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

    private fun createEchoServer(): Pair<McpTransportProvider, AutoCloseable> {
        val (clientInput, serverToClient, clientToServer, serverInput, pipeCloseable) = createPipes()
        val scope = CoroutineScope(Job() + Dispatchers.IO)

        val server = Server(
            serverInfo = Implementation(name = "test-echo", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        ) {
            addTool(
                Tool(
                    name = "echo",
                    description = "Echoes the message back",
                    inputSchema = io.modelcontextprotocol.kotlin.sdk.types.ToolSchema(
                        properties = buildJsonObject {
                            put("message", buildJsonObject {
                                put("type", JsonPrimitive("string"))
                            })
                        },
                        required = listOf("message"),
                    ),
                ),
            ) { request ->
                val message = request.params.arguments?.get("message")?.jsonPrimitive?.content ?: ""
                val payload = buildJsonObject { put("echoed", JsonPrimitive(message)) }
                CallToolResult(
                    content = listOf(TextContent("echo: $message")),
                    structuredContent = payload,
                )
            }
        }

        scope.launch {
            server.createSession(
                StdioServerTransport(
                    serverInput.asSource().buffered(),
                    serverToClient.asSink().buffered(),
                ),
            )
        }

        val closeable = AutoCloseable {
            runCatching { pipeCloseable.close() }
            scope.cancel()
            runBlocking { server.close() }
        }
        servers += closeable

        val transportProvider = object : McpTransportProvider {
            override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                return McpTransportConnection(
                    input = clientInput.asSource().buffered(),
                    output = clientToServer.asSink().buffered(),
                )
            }
        }

        return transportProvider to closeable
    }

    private fun createSlowServer(): Pair<McpTransportProvider, AutoCloseable> {
        val (clientInput, serverToClient, clientToServer, serverInput, pipeCloseable) = createPipes()
        val scope = CoroutineScope(Job() + Dispatchers.IO)

        val server = Server(
            serverInfo = Implementation(name = "test-slow", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        ) {
            addTool(
                Tool(
                    name = "delay",
                    description = "Delays for a long time",
                    inputSchema = io.modelcontextprotocol.kotlin.sdk.types.ToolSchema(
                        properties = buildJsonObject {},
                        required = emptyList(),
                    ),
                ),
            ) {
                delay(30.seconds)
                CallToolResult(content = listOf(TextContent("done")))
            }
        }

        scope.launch {
            server.createSession(
                StdioServerTransport(
                    serverInput.asSource().buffered(),
                    serverToClient.asSink().buffered(),
                ),
            )
        }

        val closeable = AutoCloseable {
            runCatching { pipeCloseable.close() }
            scope.cancel()
            runBlocking { server.close() }
        }
        servers += closeable

        val transportProvider = object : McpTransportProvider {
            override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                return McpTransportConnection(
                    input = clientInput.asSource().buffered(),
                    output = clientToServer.asSink().buffered(),
                )
            }
        }

        return transportProvider to closeable
    }

    private fun createJsonServer(): Pair<McpTransportProvider, AutoCloseable> {
        val (clientInput, serverToClient, clientToServer, serverInput, pipeCloseable) = createPipes()
        val scope = CoroutineScope(Job() + Dispatchers.IO)

        val server = Server(
            serverInfo = Implementation(name = "test-json", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        ) {
            addTool(
                Tool(
                    name = "get_data",
                    description = "Returns structured data",
                    inputSchema = io.modelcontextprotocol.kotlin.sdk.types.ToolSchema(
                        properties = buildJsonObject {},
                        required = emptyList(),
                    ),
                ),
            ) {
                val payload = buildJsonObject { put("key", JsonPrimitive("value")) }
                CallToolResult(
                    content = listOf(TextContent("""{"key":"value"}""")),
                    structuredContent = payload,
                )
            }
        }

        scope.launch {
            server.createSession(
                StdioServerTransport(
                    serverInput.asSource().buffered(),
                    serverToClient.asSink().buffered(),
                ),
            )
        }

        val closeable = AutoCloseable {
            runCatching { pipeCloseable.close() }
            scope.cancel()
            runBlocking { server.close() }
        }
        servers += closeable

        val transportProvider = object : McpTransportProvider {
            override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                return McpTransportConnection(
                    input = clientInput.asSource().buffered(),
                    output = clientToServer.asSink().buffered(),
                )
            }
        }

        return transportProvider to closeable
    }

    private fun createPipes(): PipeSet {
        val serverToClient = PipedOutputStream()
        val clientInput = PipedInputStream(serverToClient)
        val clientToServer = PipedOutputStream()
        val serverInput = PipedInputStream(clientToServer)
        val closeable = AutoCloseable {
            runCatching { clientInput.close() }
            runCatching { clientToServer.close() }
            runCatching { serverToClient.close() }
            runCatching { serverInput.close() }
        }
        return PipeSet(clientInput, serverToClient, clientToServer, serverInput, closeable)
    }

    private data class PipeSet(
        val clientInput: PipedInputStream,
        val serverToClient: PipedOutputStream,
        val clientToServer: PipedOutputStream,
        val serverInput: PipedInputStream,
        val closeable: AutoCloseable,
    )
}
