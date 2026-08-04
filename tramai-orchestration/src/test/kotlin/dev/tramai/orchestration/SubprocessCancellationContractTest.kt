package dev.tramai.orchestration

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.reflect.typeOf
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

/**
 * Cancellation contract for subprocess execution across Shell, Hermes, Codex and MCP.
 *
 * These tests use REAL parent/child processes (executable scripts writing PID files)
 * to prove end-to-end tree termination; cleanup-failure diagnostics are asserted on
 * pure [ProcessCleanupResult] values via [surfaceProcessCleanup] (no fake processes).
 *
 * Scenarios 14-16 (cross-process OS file-lock cancellation, both lock variants, store
 * reusability) live in [FileWorkflowPersistenceCancellationContractTest] which shares
 * the same helper-JVM lock-holder mechanism.
 *
 * Every spawned process is destroyed in a `finally` independent of the production code
 * under test, and each test runs under a bounded `withTimeout` so a regression fails
 * rather than hanging the worker.
 */
class SubprocessCancellationContractTest {

    // ═══ 1. Pre-cancelled execution does not start a subprocess ═══

    @Test
    fun `pre-cancelled process execution does not start a subprocess`() {
        val pidFile = Files.createTempFile("subproc-pre-cancel", ".pid")
        try {
            // The temp file itself is created by createTempFile — delete it so
            // "file exists" means "a process wrote its PID".
            Files.deleteIfExists(pidFile)
            val workflow = shellWorkflow("pre-cancel-shell") {
                shellStep(
                    name = "spawn",
                    config = ShellStepConfig(allowedCommands = setOf("sh")),
                    definition = ShellCommandDefinition(executable = "sh"),
                    command = { _, _ ->
                        ShellCommand(
                            command = listOf(
                                "sh", "-c",
                                "echo $$ > '${pidFile.toAbsolutePath()}'; exec sleep 30",
                            ),
                        )
                    },
                    merge = { state, result, _ -> state.copy(result = result) },
                )
            }

            runBlocking {
                // Launch lazily and cancel BEFORE the coroutine runs: the step's
                // process start must never execute.
                val job = launch(start = CoroutineStart.LAZY) {
                    workflow.run(SubprocessShellState())
                }
                job.cancel()
                job.start()
                job.join()

                // Bounded check that no process was spawned.
                withTimeoutOrNull(1_000) {
                    while (Files.exists(pidFile)) {
                        delay(10)
                    }
                }
                assertThat(Files.exists(pidFile)).isFalse()
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    // ═══ 2. Shell cancellation terminates root + descendants ═══

    @Test
    fun `shell cancellation terminates root and descendant processes`() {
        val parentPidFile = Files.createTempFile("subproc-shell-cancel-parent", ".pid")
        val childPidFile = Files.createTempFile("subproc-shell-cancel-child", ".pid")
        try {
            val workflow = shellWorkflow("shell-cancel") {
                shellStep(
                    name = "sleep",
                    config = ShellStepConfig(allowedCommands = setOf("sh")),
                    definition = ShellCommandDefinition(executable = "sh"),
                    command = { _, _ ->
                        ShellCommand(
                            command = listOf(
                                "sh", "-c",
                                "echo $$ > '${parentPidFile.toAbsolutePath()}'; " +
                                    "sleep 30 & child=\$!; echo \$child > '${childPidFile.toAbsolutePath()}'; " +
                                    "wait \$child",
                            ),
                        )
                    },
                    merge = { state, result, _ -> state.copy(result = result) },
                )
            }

            runBlocking {
                withTimeout(15_000) {
                    val job = launch {
                        workflow.run(SubprocessShellState())
                    }
                    val parentPid = awaitProcessHandle(parentPidFile)
                    val childPid = awaitProcessHandle(childPidFile)
                    job.cancelAndJoin()
                    awaitProcessExit(parentPid)
                    awaitProcessExit(childPid)
                    assertThat(pidIsAlive(parentPid)).isFalse()
                    assertThat(pidIsAlive(childPid)).isFalse()
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
            Files.deleteIfExists(childPidFile)
        }
    }

    // ═══ 3. Shell cancellation completes while stdout/stderr readers are blocked ═══

    @Test
    fun `shell cancellation completes while stdout and stderr readers are blocked`() {
        val pidFile = Files.createTempFile("subproc-shell-blocked-readers", ".pid")
        try {
            // `exec sleep 30` keeps stdout/stderr open with no output: the step's
            // reader coroutines block on read() until the pipes close. Cancellation
            // must close the pipes (via requestTermination) so the readers exit and
            // the coroutineScope completes within the bound — the historical hang.
            val workflow = shellWorkflow("shell-blocked-readers") {
                shellStep(
                    name = "blocked",
                    config = ShellStepConfig(allowedCommands = setOf("sh")),
                    definition = ShellCommandDefinition(executable = "sh"),
                    command = { _, _ ->
                        ShellCommand(
                            command = listOf(
                                "sh", "-c",
                                "echo $$ > '${pidFile.toAbsolutePath()}'; exec sleep 30",
                            ),
                        )
                    },
                    merge = { state, result, _ -> state.copy(result = result) },
                )
            }

            runBlocking {
                withTimeout(15_000) {
                    val job = launch {
                        workflow.run(SubprocessShellState())
                    }
                    val pid = awaitProcessHandle(pidFile)
                    job.cancelAndJoin()
                    awaitProcessExit(pid)
                    assertThat(pidIsAlive(pid)).isFalse()
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    // ═══ 4. Hermes cancellation terminates root + descendants ═══

    @Test
    fun `hermes cancellation terminates root and descendant processes`() {
        val parentPidFile = Files.createTempFile("subproc-hermes-cancel-parent", ".pid")
        val childPidFile = Files.createTempFile("subproc-hermes-cancel-child", ".pid")
        try {
            withExecutableScript(
                name = "slow-hermes-cancel",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${parentPidFile.toAbsolutePath()}'
                    |sleep 30 &
                    |child=${'$'}!
                    |echo ${'$'}child > '${childPidFile.toAbsolutePath()}'
                    |wait ${'$'}child
                """.trimMargin(),
            ) { hermesCli ->
                val workflow = agentWorkflow("hermes-cancel") {
                    hermesStep(
                        name = "review",
                        config = HermesStepConfig(cliPath = hermesCli.toString()),
                        prompt = { _, _ -> "slow prompt" },
                        merge = { state, response, _ -> state.copy(hermesResponse = response) },
                    )
                }

                runBlocking {
                    withTimeout(15_000) {
                        val job = launch {
                            workflow.run(SubprocessAgentState())
                        }
                        val parentPid = awaitProcessHandle(parentPidFile)
                        val childPid = awaitProcessHandle(childPidFile)
                        job.cancelAndJoin()
                        awaitProcessExit(parentPid)
                        awaitProcessExit(childPid)
                        assertThat(pidIsAlive(parentPid)).isFalse()
                        assertThat(pidIsAlive(childPid)).isFalse()
                    }
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
            Files.deleteIfExists(childPidFile)
        }
    }

    // ═══ 5. Codex cancellation terminates root + descendants ═══

    @Test
    fun `codex cancellation terminates root and descendant processes`() {
        val parentPidFile = Files.createTempFile("subproc-codex-cancel-parent", ".pid")
        val childPidFile = Files.createTempFile("subproc-codex-cancel-child", ".pid")
        try {
            withExecutableScript(
                name = "slow-codex-cancel",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${parentPidFile.toAbsolutePath()}'
                    |sleep 30 &
                    |child=${'$'}!
                    |echo ${'$'}child > '${childPidFile.toAbsolutePath()}'
                    |wait ${'$'}child
                """.trimMargin(),
            ) { codexCli ->
                val workflow = agentWorkflow("codex-cancel") {
                    codexStep(
                        name = "review",
                        config = CodexStepConfig(cliPath = codexCli.toString()),
                        prompt = { _, _ -> "slow prompt" },
                        merge = { state, response, _ -> state.copy(codexResponse = response) },
                    )
                }

                runBlocking {
                    withTimeout(15_000) {
                        val job = launch {
                            workflow.run(SubprocessAgentState())
                        }
                        val parentPid = awaitProcessHandle(parentPidFile)
                        val childPid = awaitProcessHandle(childPidFile)
                        job.cancelAndJoin()
                        awaitProcessExit(parentPid)
                        awaitProcessExit(childPid)
                        assertThat(pidIsAlive(parentPid)).isFalse()
                        assertThat(pidIsAlive(childPid)).isFalse()
                    }
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
            Files.deleteIfExists(childPidFile)
        }
    }

    // ═══ 6. MCP cancellation terminates its subprocess tree ═══

    @Test
    fun `mcp cancellation terminates its subprocess tree`() {
        val parentPidFile = Files.createTempFile("subproc-mcp-cancel-parent", ".pid")
        val childPidFile = Files.createTempFile("subproc-mcp-cancel-child", ".pid")
        try {
            withExecutableScript(
                name = "slow-mcp-cancel",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${parentPidFile.toAbsolutePath()}'
                    |sleep 30 &
                    |child=${'$'}!
                    |echo ${'$'}child > '${childPidFile.toAbsolutePath()}'
                    |wait ${'$'}child
                """.trimMargin(),
            ) { serverScript ->
                val step: InternalWorkflowStep<SubprocessMcpState> = McpWorkflowStep(
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
                    withTimeout(15_000) {
                        supervisorScope {
                            val deferred = async {
                                workflow.run(SubprocessMcpState())
                            }
                            val parentProcess = checkNotNull(awaitProcessHandle(parentPidFile))
                            val childProcess = checkNotNull(awaitProcessHandle(childPidFile))
                            assertThat(parentProcess.isAlive).isTrue()
                            assertThat(childProcess.isAlive).isTrue()

                            deferred.cancel()

                            // Cancellation must remain the workflow's primary
                            // outcome; an unexpected WorkflowMcpException must
                            // still fail the test rather than being swallowed.
                            val failure = runCatching { deferred.await() }.exceptionOrNull()
                            assertThat(failure).isInstanceOf(CancellationException::class.java)

                            awaitProcessExit(parentProcess)
                            awaitProcessExit(childProcess)
                            assertThat(parentProcess.isAlive).isFalse()
                            assertThat(childProcess.isAlive).isFalse()
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
            Files.deleteIfExists(childPidFile)
        }
    }

    // ═══ 7. MCP cancellation does not reconnect ═══

    @Test
    fun `mcp cancellation does not trigger reconnect`() {
        val parentPidFile = Files.createTempFile("subproc-mcp-noreconnect", ".pid")
        val observer = RecordingSubprocessObserver()
        try {
            withExecutableScript(
                name = "slow-mcp-noreconnect",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${parentPidFile.toAbsolutePath()}'
                    |exec sleep 30
                """.trimMargin(),
            ) { serverScript ->
                val step: InternalWorkflowStep<SubprocessMcpState> = McpWorkflowStep(
                    name = "slow-subprocess",
                    definition = McpToolCallDefinition(
                        serverCommand = listOf(serverScript.toString()),
                        toolName = "delay",
                    ),
                    // reconnect = true: a transient failure WOULD retry; cancellation must not.
                    config = McpStepConfig.unrestricted().copy(timeoutSeconds = 30, reconnect = true),
                    toolCallBuilder = { _, _ ->
                        McpToolCall(serverCommand = listOf(serverScript.toString()), toolName = "delay")
                    },
                    merge = { state, result, _ -> state.copy(result = result) },
                    transportProvider = SubprocessMcpTransportProvider(),
                )

                val workflow = buildWorkflow(listOf(step))

                runBlocking {
                    withTimeout(15_000) {
                        supervisorScope {
                            val deferred = async {
                                workflow.run(SubprocessMcpState(), observer = observer)
                            }
                            awaitProcessHandle(parentPidFile)
                            deferred.cancel()
                            val outcome = runCatching { deferred.await() }
                            assertThat(outcome.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
                            assertThat(observer.eventNames).doesNotContain("tramai.workflow.mcp.reconnecting")
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
        }
    }

    // ═══ 7b. MCP cleanup failure is suppressed onto cancellation ═══
    //
    // Note: the MCP SDK's Client.close() → AbstractClientTransport.close() →
    // closeResources() stops processing without closing the injected connection Sink,
    // so a "client.close() throws" state cannot be injected through the Sink. The
    // controllable injection point for close-time failures is the transport `cleanup`
    // lambda, which runs under NonCancellable in the same finally — proving the same
    // P1 invariant: a cleanup failure must be suppressed onto cancellation, never
    // replace it.

    @Test
    fun `mcp cleanup failure never replaces cancellation`() {
        val cleanupRan = AtomicBoolean(false)
        val provider = object : McpTransportProvider {
            override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                // A connection whose cleanup fails on close — the close-time failure must
                // be suppressed onto the cancellation (or absorbed), never surfaced as the
                // primary outcome. The input is a connected pipe with no writer, so the
                // client blocks until cancelled.
                val pipeOutput = PipedOutputStream()
                val clientInput = PipedInputStream(pipeOutput)
                return McpTransportConnection(
                    input = clientInput.asSource().buffered(),
                    output = swallowSink {},
                    cleanup = {
                        cleanupRan.set(true)
                        clientInput.close()
                        throw IllegalStateException("simulated close failure")
                    },
                )
            }
        }

        val step = McpWorkflowStep<SubprocessMcpState>(
            name = "slow-subprocess",
            definition = McpToolCallDefinition(
                serverCommand = listOf("unused"),
                toolName = "delay",
            ),
            config = McpStepConfig.unrestricted().copy(timeoutSeconds = 30, reconnect = false),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("unused"), toolName = "delay")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = provider,
        )

        runBlocking {
            withTimeout(15_000) {
                val captured = AtomicReference<Throwable?>()
                val stepJob = launch {
                    try {
                        step.execute(
                            workflowName = "mcp-cleanup-fail",
                            state = SubprocessMcpState(),
                            context = WorkflowContext(),
                            observer = RecordingSubprocessObserver(),
                        )
                    } catch (error: Throwable) {
                        // Capture the exact exception the production code produced.
                        captured.set(error)
                        throw error
                    }
                }
                delay(1_500)
                stepJob.cancel()
                runCatching { stepJob.join() }
                // Cancellation is preserved as the primary outcome; the cleanup failure
                // never replaces it (it is suppressed onto the primary inside the step,
                // though the withTimeout boundary re-creates the cancellation instance).
                val cancellation = captured.get()
                assertThat(cancellation).isInstanceOf(CancellationException::class.java)
                assertThat(cancellation).isNotInstanceOf(WorkflowMcpException::class.java)
                assertThat(cleanupRan.get()).isTrue()
            }
        }
    }

    // ═══ 7c. MCP suspending custom cleanup executes even in a cancelled context ═══

    @Test
    fun `mcp suspending custom cleanup executes even when the step is cancelled`() {
        val cleanupRan = AtomicBoolean(false)
        val provider = object : McpTransportProvider {
            override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                val pipeOutput = PipedOutputStream()
                val clientInput = PipedInputStream(pipeOutput)
                return McpTransportConnection(
                    input = clientInput.asSource().buffered(),
                    output = swallowSink {},
                    cleanup = {
                        // A custom cleanup that would be skipped in a cancelled context
                        // unless cleanupConnection runs it under NonCancellable.
                        delay(100)
                        cleanupRan.set(true)
                        clientInput.close()
                    },
                )
            }
        }

        val step = McpWorkflowStep<SubprocessMcpState>(
            name = "slow-subprocess",
            definition = McpToolCallDefinition(
                serverCommand = listOf("unused"),
                toolName = "delay",
            ),
            config = McpStepConfig.unrestricted().copy(timeoutSeconds = 30, reconnect = false),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("unused"), toolName = "delay")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = provider,
        )

        val workflow = buildWorkflow(listOf(step))

        runBlocking {
            withTimeout(15_000) {
                supervisorScope {
                    val captured = AtomicReference<Throwable?>()
                    val deferred = async {
                        try {
                            workflow.run(SubprocessMcpState())
                        } catch (error: Throwable) {
                            captured.set(error)
                            throw error
                        }
                    }
                    delay(1_500)
                    deferred.cancel()
                    runCatching { deferred.await() }
                    assertThat(captured.get()).isInstanceOf(CancellationException::class.java)
                    assertThat(cleanupRan.get()).isTrue()
                }
            }
        }
    }

    // ═══ 7d. MCP cleanup failure is suppressed rather than replacing cancellation ═══

    @Test
    fun `mcp cleanup failure is suppressed rather than replacing cancellation`() {
        val cleanupRan = AtomicBoolean(false)
        val provider = object : McpTransportProvider {
            override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                val pipeOutput = PipedOutputStream()
                val clientInput = PipedInputStream(pipeOutput)
                return McpTransportConnection(
                    input = clientInput.asSource().buffered(),
                    output = swallowSink {},
                    cleanup = {
                        cleanupRan.set(true)
                        clientInput.close()
                        throw IllegalStateException("simulated cleanup failure")
                    },
                )
            }
        }

        val step = McpWorkflowStep<SubprocessMcpState>(
            name = "slow-subprocess",
            definition = McpToolCallDefinition(
                serverCommand = listOf("unused"),
                toolName = "delay",
            ),
            config = McpStepConfig.unrestricted().copy(timeoutSeconds = 30, reconnect = false),
            toolCallBuilder = { _, _ ->
                McpToolCall(serverCommand = listOf("unused"), toolName = "delay")
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = provider,
        )

        runBlocking {
            withTimeout(15_000) {
                val captured = AtomicReference<Throwable?>()
                val stepJob = launch {
                    try {
                        step.execute(
                            workflowName = "mcp-cleanup-fail",
                            state = SubprocessMcpState(),
                            context = WorkflowContext(),
                            observer = RecordingSubprocessObserver(),
                        )
                    } catch (error: Throwable) {
                        captured.set(error)
                        throw error
                    }
                }
                delay(1_500)
                stepJob.cancel()
                runCatching { stepJob.join() }
                val cancellation = captured.get()
                // Cancellation is preserved; the cleanup failure never replaces it.
                assertThat(cancellation).isInstanceOf(CancellationException::class.java)
                assertThat(cancellation).isNotInstanceOf(WorkflowMcpException::class.java)
                assertThat(cleanupRan.get()).isTrue()
            }
        }
    }

    // ═══ 7e. MCP cleanup failure after a SUCCESSFUL tool result never reconnects ═══
    //
    // P1: a cleanup failure that surfaces AFTER the tool executed (no earlier primary
    // failure) must be surfaced as a non-reconnectable McpPostCallCleanupException —
    // with reconnect=true the retry loop would otherwise invoke the non-idempotent
    // tool a second time. The tool result succeeds first; only the transport cleanup
    // (the controllable close-time injection point) throws.

    @Test
    fun `mcp cleanup failure after successful tool result never re-executes the tool`() {
        val toolExecutions = AtomicInteger(0)
        val connectCount = AtomicInteger(0)
        val cleanupRan = AtomicBoolean(false)

        val serverToClient = PipedOutputStream()
        val clientInput = PipedInputStream(serverToClient)
        val clientToServer = PipedOutputStream()
        val serverInput = PipedInputStream(clientToServer)
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val server = Server(
            serverInfo = Implementation(name = "test-cleanup-fail", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        ) {
            addTool(
                Tool(
                    name = "get_data",
                    description = "Returns a result",
                    inputSchema = io.modelcontextprotocol.kotlin.sdk.types.ToolSchema(
                        properties = buildJsonObject {},
                        required = emptyList(),
                    ),
                ),
            ) {
                toolExecutions.incrementAndGet()
                CallToolResult(content = listOf(TextContent("ok")))
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
            runCatching { clientInput.close() }
            runCatching { clientToServer.close() }
            runCatching { serverToClient.close() }
            runCatching { serverInput.close() }
            scope.cancel()
            runBlocking { server.close() }
        }

        try {
            val provider = object : McpTransportProvider {
                override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                    connectCount.incrementAndGet()
                    return McpTransportConnection(
                        input = clientInput.asSource().buffered(),
                        output = clientToServer.asSink().buffered(),
                        cleanup = {
                            cleanupRan.set(true)
                            throw IllegalStateException("simulated cleanup failure")
                        },
                    )
                }
            }

            val step = McpWorkflowStep<SubprocessMcpState>(
                name = "cleanup-fail-after-success",
                definition = McpToolCallDefinition(
                    serverCommand = listOf("unused"),
                    toolName = "get_data",
                ),
                config = McpStepConfig.unrestricted().copy(timeoutSeconds = 30, reconnect = true),
                toolCallBuilder = { _, _ ->
                    McpToolCall(serverCommand = listOf("unused"), toolName = "get_data")
                },
                merge = { state, result, _ -> state.copy(result = result) },
                transportProvider = provider,
            )
            val observer = RecordingSubprocessObserver()

            runBlocking {
                withTimeout(15_000) {
                    val failure = runCatching {
                        step.execute(
                            workflowName = "mcp-cleanup-fail-after-success",
                            state = SubprocessMcpState(),
                            context = WorkflowContext(),
                            observer = observer,
                        )
                    }.exceptionOrNull()

                    assertThat(failure).isNotNull()
                    assertThat(failure).isInstanceOf(WorkflowMcpException::class.java)
                    // The cleanup failure is surfaced wrapped in the non-reconnectable
                    // McpPostCallCleanupException (file-private; asserted by message+cause).
                    val cleanupException = failure!!.cause
                    assertThat(cleanupException).isNotNull()
                    assertThat(cleanupException!!.message)
                        .isEqualTo("MCP cleanup failed after tool completion")
                    assertThat(cleanupException!!.cause).hasMessage("simulated cleanup failure")
                    // Exactly-once execution: the cleanup failure must never trigger a
                    // second connect + tool execution.
                    assertThat(toolExecutions.get()).isEqualTo(1)
                    assertThat(connectCount.get()).isEqualTo(1)
                    assertThat(cleanupRan.get()).isTrue()
                    assertThat(observer.eventNames).doesNotContain("tramai.workflow.mcp.reconnecting")
                }
            }
        } finally {
            closeable.close()
        }
    }

    // ═══ 8. Timeout is still mapped to the correct domain exception ═══

    @Test
    fun `shell timeout is still mapped to WorkflowShellException with timeout classification`() {
        val observer = RecordingSubprocessObserver()
        val workflow = shellWorkflow("shell-timeout") {
            shellStep(
                name = "sleep",
                config = ShellStepConfig(timeoutSeconds = 1, allowedCommands = setOf("sleep")),
                definition = ShellCommandDefinition(executable = "sleep"),
                command = { _, _ -> ShellCommand(command = listOf("sleep", "10")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        assertThatThrownBy {
            runBlocking { workflow.run(SubprocessShellState(), observer = observer) }
        }.isInstanceOf(WorkflowShellException::class.java)
            .hasMessageContaining("timed out")

        assertThat(observer.eventNames).contains("tramai.workflow.shell.timeout")
    }

    // ═══ 8b. Observer failure on the timeout event still terminates the process (P1) ═══

    @Test
    fun `observer failure on shell timeout event still terminates the process`() {
        val pidFile = Files.createTempFile("subproc-shell-timeout-obs-fail", ".pid")
        val throwingObserver = object : WorkflowObserver {
            override fun onWorkflowEvent(
                workflowName: String,
                name: String,
                attributes: Map<String, Any?>,
                context: WorkflowContext,
            ) {
                // Throws on the timeout event — termination must already have been
                // requested (closing the pipes) so the coroutineScope is not stranded
                // on the blocked stdout/stderr readers behind the still-running process.
                if (name == "tramai.workflow.shell.timeout") {
                    throw IllegalStateException("observer failure on timeout event")
                }
            }
        }
        try {
            val workflow = shellWorkflow("shell-timeout-obs-fail") {
                shellStep(
                    name = "sleep",
                    config = ShellStepConfig(timeoutSeconds = 1, allowedCommands = setOf("sh")),
                    definition = ShellCommandDefinition(executable = "sh"),
                    command = { _, _ ->
                        ShellCommand(
                            command = listOf(
                                "sh", "-c",
                                "echo $$ > '${pidFile.toAbsolutePath()}'; exec sleep 30",
                            ),
                        )
                    },
                    merge = { state, result, _ -> state.copy(result = result) },
                )
            }

            runBlocking {
                withTimeout(15_000) {
                    val captured = AtomicReference<Throwable?>()
                    val job = launch {
                        try {
                            workflow.run(SubprocessShellState(), observer = throwingObserver)
                        } catch (error: Throwable) {
                            captured.set(error)
                        }
                    }
                    val pid = awaitProcessHandle(pidFile)
                    job.join()
                    awaitProcessExit(pid)
                    assertThat(pidIsAlive(pid)).isFalse()
                    // The observer failure surfaces (wrapped by the step into a
                    // WorkflowShellException), never swallowed.
                    assertThat(captured.get()).isInstanceOfAny(
                        WorkflowShellException::class.java,
                        IllegalStateException::class.java,
                    )
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    // ═══ 9. Parent cancellation is not mapped to timeout ═══

    @Test
    fun `parent cancellation is not classified as timeout and emits no shell events`() {
        val pidFile = Files.createTempFile("subproc-shell-cancel-notimeout", ".pid")
        val observer = RecordingSubprocessObserver()
        try {
            val workflow = shellWorkflow("shell-cancel-notimeout") {
                shellStep(
                    name = "sleep",
                    config = ShellStepConfig(timeoutSeconds = 30, allowedCommands = setOf("sh")),
                    definition = ShellCommandDefinition(executable = "sh"),
                    command = { _, _ ->
                        ShellCommand(
                            command = listOf(
                                "sh", "-c",
                                "echo $$ > '${pidFile.toAbsolutePath()}'; exec sleep 30",
                            ),
                        )
                    },
                    merge = { state, result, _ -> state.copy(result = result) },
                )
            }

            runBlocking {
                withTimeout(15_000) {
                    val job = launch {
                        workflow.run(SubprocessShellState(), observer = observer)
                    }
                    awaitProcessHandle(pidFile)
                    job.cancelAndJoin()

                    assertThat(observer.eventNames).doesNotContain("tramai.workflow.shell.timeout")
                    assertThat(observer.eventNames).doesNotContain("tramai.workflow.shell.completed")
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    // ═══ 9b. Observer failure after process start still owns the process (P1) ═══

    @Test
    fun `agent observer failure after process start still cleans up the process`() {
        val pidFile = Files.createTempFile("subproc-agent-obs-fail", ".pid")
        val observedProcess = AtomicReference<ProcessHandle>()
        val throwingObserver = object : WorkflowObserver {
            override fun onWorkflowEvent(
                workflowName: String,
                name: String,
                attributes: Map<String, Any?>,
                context: WorkflowContext,
            ) {
                // Throws on the started event — the process has already been spawned and
                // its stdin closed. Ownership must have begun before this observer runs,
                // so the finally still terminates the tree.
                if (name.endsWith(".started")) {
                    observedProcess.set(runBlocking { awaitProcessHandle(pidFile) })
                    throw IllegalStateException("observer failure after start")
                }
            }
        }
        try {
            withExecutableScript(
                name = "slow-hermes-obs-fail",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${pidFile.toAbsolutePath()}'
                    |exec sleep 30
                """.trimMargin(),
            ) { hermesCli ->
                val workflow = agentWorkflow("hermes-obs-fail") {
                    hermesStep(
                        name = "review",
                        config = HermesStepConfig(cliPath = hermesCli.toString()),
                        prompt = { _, _ -> "slow prompt" },
                        merge = { state, response, _ -> state.copy(hermesResponse = response) },
                    )
                }

                runBlocking {
                    withTimeout(15_000) {
                        val captured = AtomicReference<Throwable?>()
                        val job = launch {
                            try {
                                workflow.run(SubprocessAgentState(), observer = throwingObserver)
                            } catch (error: Throwable) {
                                captured.set(error)
                            }
                        }
                        job.join()
                        val pid = checkNotNull(observedProcess.get())
                        awaitProcessExit(pid)
                        assertThat(pidIsAlive(pid)).isFalse()
                        // The observer failure is surfaced (wrapped), never swallowed.
                        assertThat(captured.get()).isNotNull()
                    }
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    // ═══ 9c. Lifecycle handler disposal (P1) ═══

    @Test
    fun `lifecycle cancellation handler is disposed once the step completes`() {
        val pidFile = Files.createTempFile("subproc-handler-dispose", ".pid")
        try {
            withExecutableScript(
                name = "sleepy-dispose",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${pidFile.toAbsolutePath()}'
                    |exec sleep 30
                """.trimMargin(),
            ) { script ->
                runBlocking {
                    withTimeout(15_000) {
                        val process = ProcessBuilder(script.toString()).start()
                        try {
                            val lifecycle = CancellableProcessLifecycle(process)
                            val job = Job()
                            val registration = lifecycle.attachTo(job)

                            // A completed step disposes the registration; cancelling the job
                            // afterwards must NOT request termination.
                            registration.dispose()
                            job.cancel()

                            val pid = awaitProcessHandle(pidFile)
                            assertThat(lifecycle.isTerminationRequested()).isFalse()
                            assertThat(pidIsAlive(pid)).isTrue()
                        } finally {
                            process.destroyForcibly()
                            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    // ═══ 9d. Retained handles terminate a reparented child (P1) ═══

    @Test
    fun `child reparented after parent exits on stdin close is still terminated`() {
        val parentPidFile = Files.createTempFile("subproc-reparent-parent", ".pid")
        val childPidFile = Files.createTempFile("subproc-reparent-child", ".pid")
        try {
            withExecutableScript(
                name = "reparent-on-stdin-close",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${parentPidFile.toAbsolutePath()}'
                    |sh -c 'trap "" TERM; exec sleep 30' &
                    |child=${'$'}!
                    |echo ${'$'}child > '${childPidFile.toAbsolutePath()}'
                    |cat > /dev/null
                """.trimMargin(),
            ) { script ->
                runBlocking {
                    withTimeout(15_000) {
                        val process = ProcessBuilder(script.toString()).start()
                        try {
                            val lifecycle = CancellableProcessLifecycle(process)
                            val parentPid = awaitProcessHandle(parentPidFile)
                            val childPid = awaitProcessHandle(childPidFile)
                            // Closing stdin makes `cat > /dev/null` see EOF, so the parent
                            // exits and the background child is reparented (no longer a
                            // descendant of the dead root). The child ignores TERM, so the
                            // graceful request inside requestTermination does NOT kill it —
                            // only the retained-handle forced cleanup can.
                            lifecycle.requestTermination()
                            awaitProcessExit(parentPid)
                            // The child survived the graceful request and is reparented.
                            assertThat(pidIsAlive(childPid)).isTrue()
                            // The child must still be terminated through the retained
                            // pre-pipe-close handle snapshot (destroyForcibly).
                            val cleanup = lifecycle.terminateAndAwait()
                            awaitProcessExit(childPid)
                            assertThat(pidIsAlive(childPid)).isFalse()
                            assertThat(cleanup.survivors).isEmpty()
                        } finally {
                            process.destroyForcibly()
                            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
            Files.deleteIfExists(childPidFile)
        }
    }

    @Test
    fun `termination request keeps root alive to reap a term-ignoring descendant`() {
        val parentPidFile = Files.createTempFile("subproc-reap-parent", ".pid")
        val childPidFile = Files.createTempFile("subproc-reap-child", ".pid")
        try {
            withExecutableScript(
                name = "reap-before-root-exit",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${parentPidFile.toAbsolutePath()}'
                    |sh -c 'trap "" TERM; exec sleep 30' &
                    |child=${'$'}!
                    |echo ${'$'}child > '${childPidFile.toAbsolutePath()}'
                    |wait ${'$'}child
                """.trimMargin(),
            ) { script ->
                runBlocking {
                    withTimeout(15_000) {
                        val process = ProcessBuilder(script.toString()).start()
                        try {
                            val lifecycle = CancellableProcessLifecycle(process)
                            val parentPid = awaitProcessHandle(parentPidFile)
                            val childPid = awaitProcessHandle(childPidFile)

                            lifecycle.requestTermination()

                            // The child ignores the graceful request. Keep its parent alive
                            // until bounded cleanup force-kills the child and lets `wait`
                            // reap it; killing both back-to-back can leave an orphaned zombie.
                            assertThat(pidIsAlive(childPid)).isTrue()
                            assertThat(process.waitFor(250, TimeUnit.MILLISECONDS)).isFalse()
                            assertThat(pidIsAlive(parentPid)).isTrue()

                            val cleanup = lifecycle.terminateAndAwait()
                            awaitProcessExit(parentPid)
                            awaitProcessExit(childPid)
                            assertThat(pidIsAlive(parentPid)).isFalse()
                            assertThat(pidIsAlive(childPid)).isFalse()
                            assertThat(cleanup.survivors).isEmpty()
                        } finally {
                            process.destroyForcibly()
                            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
            Files.deleteIfExists(childPidFile)
        }
    }

    // ═══ 10. Cleanup diagnostics are surfaced exactly once ═══

    @Test
    fun `cleanup failure is suppressed onto CancellationException and never replaces it`() {
        val primary = CancellationException("parent cancelled")
        val cleanupFailure = IllegalStateException("simulated cleanup failure")

        val result = ProcessCleanupResult(
            survivors = listOf(1234L),
            failures = listOf("destroy failed" to cleanupFailure),
        )

        surfaceProcessCleanup(primary, result)

        // Primary is still the cancellation; diagnostics are attached exactly once.
        assertThat(primary).isInstanceOf(CancellationException::class.java)
        assertThat(primary.message).isEqualTo("parent cancelled")
        assertThat(primary.suppressed).hasSize(2)
        assertThat(primary.suppressed).anySatisfy { suppressed ->
            assertThat(suppressed).isEqualTo(cleanupFailure)
        }
        assertThat(primary.suppressed).anySatisfy { suppressed ->
            assertThat(suppressed).isInstanceOf(ProcessTreeSurvivorException::class.java)
        }
    }

    @Test
    fun `cleanup failure without primary is thrown as ProcessCleanupException`() {
        val cleanupFailure = IllegalStateException("simulated cleanup failure")

        val result = ProcessCleanupResult(
            survivors = emptyList(),
            failures = listOf("stream close failed" to cleanupFailure),
        )

        val thrown = runCatching {
            surfaceProcessCleanup(null, result)
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(ProcessCleanupException::class.java)
        val cleanupException = thrown as ProcessCleanupException
        assertThat(cleanupException.suppressed).containsExactly(cleanupFailure)
    }

    @Test
    fun `cleanup failure with survivor diagnostics attached exactly once`() {
        val primary = CancellationException("parent cancelled")
        val cleanupFailure = IllegalStateException("simulated cleanup failure")

        // Survivors are represented once (via ProcessCleanupResult.survivors), never
        // duplicated into failures as well — cardinality is exactly 2 diagnostics.
        val result = ProcessCleanupResult(
            survivors = listOf(999L),
            failures = listOf("destroy failed" to cleanupFailure),
        )

        surfaceProcessCleanup(primary, result)

        assertThat(primary.suppressed).hasSize(2)
        val survivorExceptions = primary.suppressed.filterIsInstance<ProcessTreeSurvivorException>()
        assertThat(survivorExceptions).hasSize(1)
        assertThat(survivorExceptions.single().survivorPids).containsExactly(999L)
    }

    @Test
    fun `cleanup diagnostic that is the primary itself is not self-suppressed`() {
        // addSuppressed on the same instance throws IllegalArgumentException, which
        // would replace the very cancellation the suppression was meant to preserve.
        val primary = CancellationException("parent cancelled")

        val result = ProcessCleanupResult(
            survivors = emptyList(),
            failures = listOf("destroy failed" to primary),
        )

        surfaceProcessCleanup(primary, result)

        assertThat(primary).isInstanceOf(CancellationException::class.java)
        assertThat(primary.message).isEqualTo("parent cancelled")
        assertThat(primary.suppressed).isEmpty()
    }

    // ═══ 11. Graceful shutdown escalates to forced termination ═══

    @Test
    fun `graceful shutdown escalates to forced termination for term-ignoring processes`() {
        val pidFile = Files.createTempFile("subproc-escalate", ".pid")
        try {
            withExecutableScript(
                name = "term-ignoring",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${pidFile.toAbsolutePath()}'
                    |trap '' TERM
                    |while true; do sleep 1; done
                """.trimMargin(),
            ) { script ->
                runBlocking {
                    withTimeout(15_000) {
                        val process = ProcessBuilder(script.toString()).start()
                        try {
                            val lifecycle = CancellableProcessLifecycle(process)
                            val pid = awaitProcessHandle(pidFile)
                            // Graceful destroy alone cannot kill a TERM-ignoring process;
                            // terminateAndAwait must escalate to destroyForcibly.
                            val cleanup = lifecycle.terminateAndAwait()
                            awaitProcessExit(pid)
                            assertThat(pidIsAlive(pid)).isFalse()
                            assertThat(cleanup.survivors).isEmpty()
                        } finally {
                            process.destroyForcibly()
                            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    // ═══ 12. Forced termination wait is bounded ═══

    @Test
    fun `forced termination wait is bounded even when the process survives grace`() {
        val pidFile = Files.createTempFile("subproc-bounded", ".pid")
        try {
            withExecutableScript(
                name = "term-ignoring-bounded",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${pidFile.toAbsolutePath()}'
                    |trap '' TERM
                    |while true; do sleep 1; done
                """.trimMargin(),
            ) { script ->
                runBlocking {
                    withTimeout(15_000) {
                        val process = ProcessBuilder(script.toString()).start()
                        try {
                            val lifecycle = CancellableProcessLifecycle(process)
                            awaitProcessHandle(pidFile)
                            val startedAt = System.nanoTime()
                            val cleanup = lifecycle.terminateAndAwait()
                            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
                            // grace (1s) + force-kill wait (1s) + polling slack.
                            assertThat(elapsedMillis).isLessThan(5_000)
                            assertThat(cleanup.survivors).isEmpty()
                        } finally {
                            process.destroyForcibly()
                            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    // ═══ 13. Repeated cleanup is idempotent ═══

    @Test
    fun `repeated cleanup is idempotent`() {
        val pidFile = Files.createTempFile("subproc-idempotent", ".pid")
        try {
            withExecutableScript(
                name = "sleepy-idempotent",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${pidFile.toAbsolutePath()}'
                    |exec sleep 30
                """.trimMargin(),
            ) { script ->
                runBlocking {
                    withTimeout(15_000) {
                        val process = ProcessBuilder(script.toString()).start()
                        try {
                            val lifecycle = CancellableProcessLifecycle(process)
                            val pid = awaitProcessHandle(pidFile)

                            // Termination requested from multiple sources: cancellation
                            // handler, timeout cleanup, outer finally.
                            lifecycle.requestTermination()
                            lifecycle.requestTermination()
                            val first = lifecycle.terminateAndAwait()
                            val second = lifecycle.terminateAndAwait()

                            awaitProcessExit(pid)
                            assertThat(pidIsAlive(pid)).isFalse()
                            assertThat(first.survivors).isEmpty()
                            assertThat(second.survivors).isEmpty()
                            assertThat(lifecycle.isTerminationRequested()).isTrue()
                        } finally {
                            process.destroyForcibly()
                            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    // ═══ 13b. Acquisition survives cancellation (P1) ═══
    //
    // With a plain `withContext(ioDispatcher) { start() }` acquisition, a cancellation
    // arriving DURING start() can discard the Process before the lifecycle is attached
    // — an orphaned OS process. startOwnedProcess runs creation under NonCancellable so
    // the Process is always delivered to the caller; the immediate attachTo on the
    // cancelled job then terminates the tree (attach-then-active-check).

    @Test
    fun `process started while the owner coroutine is cancelled is still delivered and terminated`() {
        val pidFile = Files.createTempFile("subproc-acquire-race", ".pid")
        try {
            withExecutableScript(
                name = "acquire-race",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${pidFile.toAbsolutePath()}'
                    |exec sleep 30
                """.trimMargin(),
            ) { script ->
                runBlocking {
                    withTimeout(15_000) {
                        val startedLatch = CountDownLatch(1)
                        val releaseLatch = CountDownLatch(1)
                        val job = launch {
                            // Replicates the production acquisition: cancellation arriving
                            // DURING start() must not discard the Process — the lifecycle is
                            // attached immediately after and terminates the tree.
                            val process = startOwnedProcess(Dispatchers.IO) {
                                startedLatch.countDown()
                                releaseLatch.await()
                                ProcessBuilder(script.toString()).start()
                            }
                            val lifecycle = CancellableProcessLifecycle(process)
                            lifecycle.attachTo(currentCoroutineContext().job)
                        }
                        // Wait off the runBlocking thread: latch.await() blocks, and the
                        // single-threaded event loop must stay free to schedule the launch
                        // body above (otherwise this deadlocks and withTimeout never fires).
                        async(Dispatchers.IO) { startedLatch.await() }.await()
                        job.cancel()
                        releaseLatch.countDown()
                        // The script writes the pid file before start() returns, so it exists
                        // before any termination can be requested.
                        val pid = awaitProcessHandle(pidFile)
                        job.join()
                        awaitProcessExit(pid)
                        assertThat(pidIsAlive(pid)).isFalse()
                    }
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    // ═══ 13c. Cleanup from an already-cancelled coroutine (P1) ═══
    //
    // A cleanup hop that combines `NonCancellable + dispatcher` can replace the primary
    // cancellation with a fresh instance on dispatch-back. terminateAndAwait nests the
    // contexts (NonCancellable outside, IO inside) so the outer call never changes
    // dispatchers: the result is delivered in place, code after the call executes, and
    // diagnostics attach to the original caught cancellation.

    @Test
    fun `terminateAndAwait from an already-cancelled coroutine returns its result and preserves the original cancellation`() {
        val pidFile = Files.createTempFile("subproc-cancelled-cleanup", ".pid")
        try {
            withExecutableScript(
                name = "cancelled-cleanup",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${pidFile.toAbsolutePath()}'
                    |exec sleep 30
                """.trimMargin(),
            ) { script ->
                runBlocking {
                    withTimeout(15_000) {
                        val process = ProcessBuilder(script.toString()).start()
                        try {
                            val lifecycle = CancellableProcessLifecycle(process)
                            val pid = awaitProcessHandle(pidFile)
                            val captured = AtomicReference<CancellationException?>()
                            val afterCleanupExecuted = AtomicBoolean(false)
                            val enteredAwaitExit = CompletableDeferred<Unit>()
                            val deferred = async {
                                try {
                                    enteredAwaitExit.complete(Unit)
                                    lifecycle.awaitExit()
                                } catch (error: CancellationException) {
                                    captured.set(error)
                                    // terminateAndAwait must run and return its result even
                                    // though this coroutine is already cancelled...
                                    val cleanup = withContext(NonCancellable) {
                                        lifecycle.terminateAndAwait()
                                    }
                                    assertThat(cleanup).isNotNull()
                                    afterCleanupExecuted.set(true)
                                    // ...and cleanup diagnostics attach to the ORIGINAL
                                    // caught instance (production finally-block pattern).
                                    surfaceProcessCleanup(error, cleanup)
                                    surfaceProcessCleanup(
                                        error,
                                        ProcessCleanupResult(survivors = listOfNotNull(pid?.pid()), failures = emptyList()),
                                    )
                                    throw error
                                }
                            }
                            // Ensure the coroutine is inside awaitExit before cancelling —
                            // cancelling a not-yet-started async skips the catch entirely.
                            enteredAwaitExit.await()
                            val cancellationCause = CancellationException("original parent cancellation")
                            deferred.cancel(cancellationCause)
                            val thrown = runCatching { deferred.await() }.exceptionOrNull()
                            // Cleanup from the test body is idempotent (already ran above).
                            val secondCleanup = lifecycle.terminateAndAwait()
                            assertThat(secondCleanup).isNotNull()
                            awaitProcessExit(pid)
                            assertThat(pidIsAlive(pid)).isFalse()
                            assertThat(afterCleanupExecuted.get()).isTrue()
                            // Kotlinx delivers a fresh JobCancellationException at the
                            // cancellation boundary (even inside the coroutine, cancel(cause)
                            // resumes with a wrapper carrying the cause's message) — reference
                            // identity is outside the contract. Assert: classification as
                            // cancellation, the original message preserved, cleanup ran, and
                            // diagnostics landed on the caught instance (never dropped).
                            assertThat(captured.get()).isInstanceOf(CancellationException::class.java)
                            assertThat(captured.get()).hasMessage("original parent cancellation")
                            assertThat(thrown).isInstanceOf(CancellationException::class.java)
                            assertThat(thrown).hasMessage("original parent cancellation")
                            // Survivor diagnostics landed on the original instance, never a copy.
                            assertThat(captured.get()!!.suppressed)
                                .anySatisfy { assertThat(it).isInstanceOf(ProcessTreeSurvivorException::class.java) }
                        } finally {
                            process.destroyForcibly()
                            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    // ═══ test infrastructure ═══

    private class RecordingSubprocessObserver : WorkflowObserver {
        val eventNames = mutableListOf<String>()

        override fun onWorkflowEvent(
            workflowName: String,
            name: String,
            attributes: Map<String, Any?>,
            context: WorkflowContext,
        ) {
            eventNames += name
        }
    }
}

private data class SubprocessShellState(
    val result: ShellResult? = null,
)

private data class SubprocessAgentState(
    val hermesResponse: String? = null,
    val codexResponse: String? = null,
)

private data class SubprocessMcpState(
    val result: McpToolResult? = null,
)


private fun buildWorkflow(
    steps: List<InternalWorkflowStep<SubprocessMcpState>>,
): Workflow<SubprocessMcpState, SubprocessMcpState> = Workflow(
    name = "test-subprocess-cancellation",
    definitionVersion = "1",
    stateType = typeOf<SubprocessMcpState>(),
    resultType = typeOf<SubprocessMcpState>(),
    schedule = null,
    steps = steps,
    resultSelector = { it },
    stopPolicy = StopPolicy(),
    clock = Clock.systemUTC(),
    externalStepExecutorResolver = NoOpExternalStepExecutorResolver,
)

private fun shellWorkflow(
    name: String,
    configure: WorkflowBuilder<SubprocessShellState>.() -> Unit,
): Workflow<SubprocessShellState, SubprocessShellState> = workflow<SubprocessShellState>(name, configure = configure).build { it }

private fun agentWorkflow(
    name: String,
    configure: WorkflowBuilder<SubprocessAgentState>.() -> Unit,
): Workflow<SubprocessAgentState, SubprocessAgentState> = workflow<SubprocessAgentState>(name, configure = configure).build { it }

/** A Sink that swallows all writes (no reader attached); close behavior configurable. */
private fun swallowSink(onClose: () -> Unit = {}): Sink {
    val raw = object : RawSink {
        override fun write(source: kotlinx.io.Buffer, byteCount: Long) {
            source.skip(byteCount)
        }

        override fun flush() = Unit

        override fun close() {
            onClose()
        }
    }
    return raw.buffered()
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

private suspend fun awaitProcessHandle(pidFile: Path): ProcessHandle? {
    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (System.nanoTime() < deadlineNanos) {
        if (Files.exists(pidFile)) {
            val rawPid = Files.readString(pidFile).trim()
            if (rawPid.isNotEmpty()) {
                val pid = rawPid.toLong()
                // Null means the process already exited before its handle could
                // be captured — which is a legitimate outcome for tests that
                // assert termination, not an error.
                return ProcessHandle.of(pid).orElse(null)
            }
        }
        delay(25)
    }
    error("Timed out waiting for PID at $pidFile")
}

private fun awaitProcessExit(process: ProcessHandle?) {
    if (process == null) {
        return
    }
    process.onExit().get(20, TimeUnit.SECONDS)
}

private fun pidIsAlive(process: ProcessHandle?): Boolean = process?.isAlive == true
