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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.util.concurrent.TimeUnit
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
            definition = McpToolCallDefinition(
                serverCommand = listOf("unused"),
                toolName = "echo",
                argumentKeys = setOf("message"),
            ),
            config = McpStepConfig.unrestricted(),
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
            definition = McpToolCallDefinition(
                serverCommand = listOf("unused"),
                toolName = "nonexistent_tool",
            ),
            config = McpStepConfig.unrestricted().copy(reconnect = false),
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
            definition = McpToolCallDefinition(
                serverCommand = listOf("unused"),
                toolName = "delay",
            ),
            config = McpStepConfig.unrestricted().copy(timeoutSeconds = 1, reconnect = false),
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
    fun `mcp step propagates CancellationException on mid-call coroutine cancellation`() {
        val (transportProvider, _) = createSlowServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "slow",
            definition = McpToolCallDefinition(
                serverCommand = listOf("unused"),
                toolName = "delay",
            ),
            config = McpStepConfig.unrestricted().copy(timeoutSeconds = 10, reconnect = false),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("unused"), toolName = "delay")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))

        runBlocking {
            supervisorScope {
            val deferred = async {
                workflow.run(McpState())
            }
            delay(500)
            deferred.cancel()
            val result = runCatching { deferred.await() }
            assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            }
        }
    }

    @Test
    fun `mcp subprocess cleanup terminates descendant processes on mid-call coroutine cancellation`() {
        val parentPidFile = Files.createTempFile("workflow-mcp-cancel-parent", ".pid")
        val childPidFile = Files.createTempFile("workflow-mcp-cancel-child", ".pid")
        try {
            withExecutableScript(
                name = "slow-mcp-descendants",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${parentPidFile.toAbsolutePath()}'
                    |sleep 30 &
                    |child=$!
                    |echo ${'$'}child > '${childPidFile.toAbsolutePath()}'
                    |wait ${'$'}child
                """.trimMargin(),
            ) { serverScript ->
                val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
                    name = "slow-subprocess",
                    definition = McpToolCallDefinition(
                        serverCommand = listOf(serverScript.toString()),
                        toolName = "delay",
                    ),
                    config = McpStepConfig.unrestricted().copy(timeoutSeconds = 30, reconnect = false),
                    toolCallBuilder = { _, _ ->
                        McpToolCall(serverCommand = listOf(serverScript.toString()), toolName = "delay")
                    },
                    merge = { state, result, _ -> state.copy(result = result) },
                    transportProvider = SubprocessMcpTransportProvider(),
                )

                val workflow = buildWorkflow(listOf(step))

                runBlocking {
                    supervisorScope {
                    val deferred = async {
                        workflow.run(McpState())
                    }
                    val parentProcess = awaitMcpProcessHandle(parentPidFile)
                    val childProcess = awaitMcpProcessHandle(childPidFile)
                    deferred.cancel()
                    runCatching { deferred.await() }

                    awaitMcpProcessExit(parentProcess)
                    awaitMcpProcessExit(childProcess)
                    assertThat(parentProcess?.isAlive ?: false).isFalse()
                    assertThat(childProcess?.isAlive ?: false).isFalse()
                    }
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
            Files.deleteIfExists(childPidFile)
        }
    }

    @Test
    fun `mcp subprocess cleanup terminates descendant processes on timeout`() {
        val parentPidFile = Files.createTempFile("workflow-mcp-timeout-parent", ".pid")
        val childPidFile = Files.createTempFile("workflow-mcp-timeout-child", ".pid")
        try {
            withExecutableScript(
                name = "slow-mcp-descendants",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${parentPidFile.toAbsolutePath()}'
                    |sleep 30 &
                    |child=$!
                    |echo ${'$'}child > '${childPidFile.toAbsolutePath()}'
                    |wait ${'$'}child
                """.trimMargin(),
            ) { serverScript ->
                val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
                    name = "slow-subprocess",
                    definition = McpToolCallDefinition(
                        serverCommand = listOf(serverScript.toString()),
                        toolName = "delay",
                    ),
                    config = McpStepConfig.unrestricted().copy(timeoutSeconds = 1, reconnect = false),
                    toolCallBuilder = { _, _ ->
                        McpToolCall(serverCommand = listOf(serverScript.toString()), toolName = "delay")
                    },
                    merge = { state, result, _ -> state.copy(result = result) },
                    transportProvider = SubprocessMcpTransportProvider(),
                )

                val workflow = buildWorkflow(listOf(step))

                runBlocking {
                    supervisorScope {
                        val execution = async { workflow.run(McpState()) }
                        val parentProcess = awaitMcpProcessHandle(parentPidFile)
                        val childProcess = awaitMcpProcessHandle(childPidFile)

                        val failure = runCatching { execution.await() }.exceptionOrNull()
                        assertThat(failure).isInstanceOf(WorkflowMcpException::class.java)
                            .hasMessageContaining("timed out")

                        awaitMcpProcessExit(parentProcess)
                        awaitMcpProcessExit(childProcess)
                        assertThat(parentProcess?.isAlive ?: false).isFalse()
                        assertThat(childProcess?.isAlive ?: false).isFalse()
                    }
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
            Files.deleteIfExists(childPidFile)
        }
    }

    @Test
    fun `mcp step emits observer events`() {
        val (transportProvider, _) = createEchoServer()
        val observer = RecordingMcpObserver()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "echo",
            definition = McpToolCallDefinition(
                serverCommand = listOf("unused"),
                toolName = "echo",
                argumentKeys = setOf("message"),
            ),
            config = McpStepConfig.unrestricted(),
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
            definition = McpToolCallDefinition(
                serverCommand = listOf("unused"),
                toolName = "echo",
            ),
            config = McpStepConfig.unrestricted().copy(
                toolAllowlist = setOf("approved_tool"),
                reconnect = false,
            ),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("unused"), toolName = "echo")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))

        val error = runCatching {
            runBlocking { workflow.run(McpState()) }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(WorkflowMcpException::class.java)
        assertThat(error).hasMessageContaining("not in the allowlist")
        assertThat(error?.cause).isNull()
    }

    @Test
    fun `mcp step handles structured content`() {
        val (transportProvider, _) = createJsonServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "json",
            definition = McpToolCallDefinition(
                serverCommand = listOf("unused"),
                toolName = "get_data",
            ),
            config = McpStepConfig.unrestricted(),
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

    @Test
    fun `mcp typed overload decodes the tool result before merge`() {
        val (transportProvider, _) = createEchoServer()

        val workflow = workflow<McpState>("typed-mcp-success") {
            mcpStep(
                name = "echo",
                config = McpStepConfig.unrestricted(),
                definition = McpToolCallDefinition(
                    serverCommand = listOf("unused"),
                    toolName = "echo",
                    argumentKeys = setOf("message"),
                ),
                toolCall = { _, _ ->
                    McpToolCall(
                        serverCommand = listOf("unused"),
                        toolName = "echo",
                        arguments = mapOf("message" to "typed"),
                    )
                },
                decode = { result -> result.content ?: error("missing content") },
                merge = { state, result, _ -> state.copy(decoded = result) },
            )
        }.build { it }.withFirstMcpTransport(transportProvider)

        val result = runBlocking { workflow.run(McpState(), observer = RecordingMcpObserver()) }

        assertThat(result.decoded).isEqualTo("echo: typed")
    }

    @Test
    fun `mcp typed overload wraps decode failures as workflow mcp exceptions`() {
        val (transportProvider, _) = createEchoServer()

        val workflow = workflow<McpState>("typed-mcp-failure") {
            mcpStep(
                name = "echo",
                config = McpStepConfig.unrestricted(),
                definition = McpToolCallDefinition(
                    serverCommand = listOf("unused"),
                    toolName = "echo",
                    argumentKeys = setOf("message"),
                ),
                toolCall = { _, _ ->
                    McpToolCall(
                        serverCommand = listOf("unused"),
                        toolName = "echo",
                        arguments = mapOf("message" to "typed"),
                    )
                },
                decode = { result -> error("decode failed for '${result.content}'") },
                merge = { state, result, _ -> state.copy(decoded = result) },
            )
        }.build { it }.withFirstMcpTransport(transportProvider)

        assertThatThrownBy {
            runBlocking { workflow.run(McpState()) }
        }.isInstanceOf(WorkflowMcpException::class.java)
            .hasMessageContaining("decode failed for 'echo: typed'")
    }

    @Test
    fun `mcp step rejects state-influenced command changes`() {
        val (transportProvider, _) = createEchoServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "echo",
            definition = McpToolCallDefinition(
                serverCommand = listOf("node", "server.js"),
                toolName = "echo",
            ),
            config = McpStepConfig.unrestricted().copy(reconnect = false),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("evil", "command"), toolName = "echo")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))

        val error = runCatching {
            runBlocking { workflow.run(McpState()) }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(WorkflowMcpException::class.java)
        assertThat(error).hasMessageContaining("does not match the canonical definition")
        assertThat(error?.cause).isNull()
    }

    @Test
    fun `mcp step enforces command denylist`() {
        val (transportProvider, _) = createEchoServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "echo",
            definition = McpToolCallDefinition(
                serverCommand = listOf("dangerous-cmd"),
                toolName = "echo",
            ),
            config = McpStepConfig(
                reconnect = false,
                allowedCommands = setOf("dangerous-cmd"),
                deniedCommands = setOf("dangerous-cmd"),
            ),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("dangerous-cmd"), toolName = "echo")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))

        val error = runCatching {
            runBlocking { workflow.run(McpState()) }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(WorkflowMcpException::class.java)
        assertThat(error).hasMessageContaining("blocked by the denylist")
        assertThat(error?.cause).isNull()
    }

    @Test
    fun `mcp step config allows only node commands`() {
        val (transportProvider, _) = createEchoServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "echo",
            definition = McpToolCallDefinition(
                serverCommand = listOf("node", "server.js"),
                toolName = "echo",
                argumentKeys = setOf("message"),
            ),
            config = McpStepConfig(allowedCommands = setOf("node")),
            toolCallBuilder = { _, _ ->
                McpToolCall(
                    serverCommand = listOf("node", "server.js"),
                    toolName = "echo",
                    arguments = mapOf("message" to "hello-node"),
                )
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))
        val result = runBlocking { workflow.run(McpState()) }

        assertThat(result.result?.content).contains("hello-node")
    }

    @Test
    fun `mcp step unrestricted factory preserves old behavior`() {
        val (transportProvider, _) = createEchoServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "echo",
            definition = McpToolCallDefinition(
                serverCommand = listOf("unused"),
                toolName = "echo",
            ),
            config = McpStepConfig.unrestricted(),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("unused"), toolName = "echo")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))
        val result = runBlocking { workflow.run(McpState()) }

        assertThat(result.result?.content).contains("echo:")
    }

    @Test
    fun `static mcp commands violating allowlist fail at workflow build time`() {
        assertThatThrownBy {
            workflow<McpState>("static-mcp-allowlist-failure") {
                mcpStep(
                    name = "echo",
                    config = McpStepConfig(allowedCommands = setOf("node")),
                    definition = McpToolCallDefinition(
                        serverCommand = listOf("python", "server.py"),
                        toolName = "echo",
                    ),
                    toolCall = { _, _ ->
                        McpToolCall(serverCommand = listOf("python", "server.py"), toolName = "echo")
                    },
                    merge = { state, result, _ -> state.copy(result = result) },
                )
            }.build { it }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not in allowlist")
    }

    @Test
    fun `dynamic mcp commands violating allowlist fail at execution time`() {
        val (transportProvider, _) = createEchoServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "echo",
            definition = McpToolCallDefinition(
                serverCommand = listOf("python", "server.py"),
                toolName = "echo",
            ),
            config = McpStepConfig(allowedCommands = setOf("node"), reconnect = false),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("python", "server.py"), toolName = "echo")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))

        assertThatThrownBy {
            runBlocking { workflow.run(McpState()) }
        }.isInstanceOf(WorkflowMcpException::class.java)
            .hasMessageContaining("not in allowlist")
    }

    @Test
    fun `mcp step redacts server command identifiers in failure errors`() {
        val secretExecutable = "my-secret-mcp-server"
        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "secret-server",
            definition = McpToolCallDefinition(
                serverCommand = listOf(secretExecutable),
                toolName = "echo",
            ),
            config = McpStepConfig.unrestricted().copy(reconnect = false),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf(secretExecutable), toolName = "echo")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = SubprocessMcpTransportProvider(),
        )

        val workflow = buildWorkflow(listOf(step))

        assertThatThrownBy {
            runBlocking { workflow.run(McpState()) }
        }.isInstanceOf(WorkflowMcpException::class.java)
            .hasMessageContaining("[command]")
            .hasMessageNotContaining(secretExecutable)
    }

    @Test
    fun `mcp step with default config denies all dynamic commands at execution time`() {
        val (transportProvider, _) = createEchoServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "echo",
            definition = McpToolCallDefinition(
                serverCommand = listOf("python", "server.py"),
                toolName = "echo",
            ),
            config = McpStepConfig(reconnect = false),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("python", "server.py"), toolName = "echo")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))

        assertThatThrownBy {
            runBlocking { workflow.run(McpState()) }
        }.isInstanceOf(WorkflowMcpException::class.java)
            .hasMessageContaining("not in allowlist")
    }

    @Test
    fun `mcp step unrestricted factory still allows commands`() {
        val (transportProvider, _) = createEchoServer()

        val step: InternalWorkflowStep<McpState> = McpWorkflowStep(
            name = "echo",
            definition = McpToolCallDefinition(
                serverCommand = listOf("unused"),
                toolName = "echo",
            ),
            config = McpStepConfig.unrestricted(),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("unused"), toolName = "echo")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

        val workflow = buildWorkflow(listOf(step))
        val result = runBlocking { workflow.run(McpState()) }

        assertThat(result.result?.content).contains("echo:")
    }

    // ─── test infrastructure ────────────────────────────────────────────

    private data class McpState(
        val result: McpToolResult? = null,
        val decoded: String? = null,
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
        externalStepExecutorResolver = NoOpExternalStepExecutorResolver,
    )

    @Suppress("UNCHECKED_CAST")
    private fun Workflow<McpState, McpState>.withFirstMcpTransport(
        transportProvider: McpTransportProvider,
    ): Workflow<McpState, McpState> {
        val steps = readPrivate<List<InternalWorkflowStep<McpState>>>("steps").toMutableList()
        val existing = steps.first() as McpWorkflowStep<McpState>
        steps[0] = existing.copy(transportProvider = transportProvider)

        return Workflow(
            name = name,
            definitionVersion = definitionVersion,
            stateType = stateType,
            resultType = resultType,
            schedule = schedule,
            steps = steps,
            resultSelector = readPrivate("resultSelector"),
            stopPolicy = readPrivate("stopPolicy"),
            clock = readPrivate("clock"),
            externalStepExecutorResolver = readPrivate("externalStepExecutorResolver"),
            httpClient = readPrivate("httpClient"),
        )
    }

    private fun <T> Workflow<McpState, McpState>.readPrivate(name: String): T {
        val field = Workflow::class.java.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as T
    }

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

private fun withExecutableScript(
    name: String,
    content: String,
    block: (Path) -> Unit,
) {
    val script = Files.createTempFile(name, ".sh")
    try {
        Files.writeString(script, content + "\n")
        Files.setPosixFilePermissions(
            script,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        block(script)
    } finally {
        Files.deleteIfExists(script)
    }
}

private suspend fun awaitMcpProcessHandle(pidFile: Path): ProcessHandle? {
    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (System.nanoTime() < deadlineNanos) {
        if (Files.exists(pidFile)) {
            val rawPid = Files.readString(pidFile).trim()
            if (rawPid.isNotEmpty()) {
                val pid = rawPid.toLong()
                // Null means the process already exited before its handle could
                // be captured — a legitimate outcome for termination tests.
                return ProcessHandle.of(pid).orElse(null)
            }
        }
        delay(25)
    }
    error("Timed out waiting for MCP PID at $pidFile")
}

private fun awaitMcpProcessExit(process: ProcessHandle?) {
    if (process == null) {
        return
    }
    process.onExit().get(20, TimeUnit.SECONDS)
}
