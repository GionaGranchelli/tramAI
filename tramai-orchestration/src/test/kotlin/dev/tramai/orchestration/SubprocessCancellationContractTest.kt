package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.reflect.typeOf
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * Cancellation contract for subprocess execution across Shell, Hermes, Codex and MCP.
 *
 * These tests use REAL parent/child processes (executable scripts writing PID files)
 * to prove end-to-end tree termination; fake process abstractions are used only for
 * deterministic cleanup-failure assertions (scenario 10).
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
                    val parentPid = awaitPid(parentPidFile)
                    val childPid = awaitPid(childPidFile)
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
                    val pid = awaitPid(pidFile)
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
                        val parentPid = awaitPid(parentPidFile)
                        val childPid = awaitPid(childPidFile)
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
                        val parentPid = awaitPid(parentPidFile)
                        val childPid = awaitPid(childPidFile)
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
                        val deferred = async {
                            workflow.run(SubprocessMcpState())
                        }
                        val parentPid = awaitPid(parentPidFile)
                        val childPid = awaitPid(childPidFile)
                        deferred.cancel()
                        runCatching { deferred.await() }
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
                        val deferred = async {
                            workflow.run(SubprocessMcpState(), observer = observer)
                        }
                        awaitPid(parentPidFile)
                        deferred.cancel()
                        val outcome = runCatching { deferred.await() }
                        assertThat(outcome.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
                        assertThat(observer.eventNames).doesNotContain("tramai.workflow.mcp.reconnecting")
                    }
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
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
                    awaitPid(pidFile)
                    job.cancelAndJoin()

                    assertThat(observer.eventNames).doesNotContain("tramai.workflow.shell.timeout")
                    assertThat(observer.eventNames).doesNotContain("tramai.workflow.shell.completed")
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    // ═══ 10. Cleanup failure is suppressed onto CancellationException ═══

    @Test
    fun `cleanup failure is suppressed onto CancellationException and never replaces it`() {
        val primary = CancellationException("parent cancelled")
        val cleanupFailure = IllegalStateException("simulated cleanup failure")

        val result = ProcessCleanupResult(
            survivors = listOf(1234L),
            failures = listOf("destroy failed" to cleanupFailure),
        )

        primary.suppressCleanup(result)

        // Primary is still the cancellation; cleanup failures are suppressed onto it.
        assertThat(primary).isInstanceOf(CancellationException::class.java)
        assertThat(primary.message).isEqualTo("parent cancelled")
        assertThat(primary.suppressed).anySatisfy { suppressed ->
            assertThat(suppressed).isEqualTo(cleanupFailure)
        }
        assertThat(primary.suppressed).anySatisfy { suppressed ->
            assertThat(suppressed).isInstanceOf(ProcessTreeSurvivorException::class.java)
        }
    }

    @Test
    fun `terminateAndAwait records cleanup failures instead of throwing over cancellation`() {
        // A fake Process whose destroy/destroyForcibly always fail — deterministic
        // cleanup-failure injection without a real OS process.
        val fakeProcess = FailingDestroyProcess()
        val lifecycle = CancellableProcessLifecycle(fakeProcess)

        runBlocking {
            val cleanup = lifecycle.terminateAndAwait()
            assertThat(cleanup.failures).isNotEmpty()
            assertThat(cleanup.survivors).isNotEmpty()

            val cancellation = CancellationException("parent cancelled")
            cancellation.suppressCleanup(cleanup)
            assertThat(cancellation).isInstanceOf(CancellationException::class.java)
            assertThat(cancellation.suppressed).isNotEmpty()
        }
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
                            val pid = awaitPid(pidFile)
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
                            awaitPid(pidFile)
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
                            val pid = awaitPid(pidFile)

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

/** Process whose destroy/destroyForcibly always fail — deterministic cleanup-failure injection. */
private class FailingDestroyProcess : Process() {
    private val alive = java.util.concurrent.atomic.AtomicBoolean(true)

    override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
    override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
    override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        alive.set(false)
        return 0
    }

    override fun exitValue(): Int = 0

    override fun destroy() {
        throw IllegalStateException("simulated destroy failure")
    }

    override fun destroyForcibly(): Process {
        throw IllegalStateException("simulated destroyForcibly failure")
    }

    override fun isAlive(): Boolean = alive.get()
    override fun pid(): Long = 1234L
    override fun toHandle(): ProcessHandle = ProcessHandle.current()
}

private fun shellWorkflow(
    name: String,
    configure: WorkflowBuilder<SubprocessShellState>.() -> Unit,
): Workflow<SubprocessShellState, SubprocessShellState> = workflow<SubprocessShellState>(name, configure = configure).build { it }

private fun agentWorkflow(
    name: String,
    configure: WorkflowBuilder<SubprocessAgentState>.() -> Unit,
): Workflow<SubprocessAgentState, SubprocessAgentState> = workflow<SubprocessAgentState>(name, configure = configure).build { it }

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

private suspend fun awaitPid(pidFile: Path): Long {
    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadlineNanos) {
        if (Files.exists(pidFile)) {
            val rawPid = Files.readString(pidFile).trim()
            if (rawPid.isNotEmpty()) {
                return rawPid.toLong()
            }
        }
        delay(25)
    }
    error("Timed out waiting for PID at $pidFile")
}

private suspend fun awaitProcessExit(pid: Long) {
    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadlineNanos) {
        val handle = ProcessHandle.of(pid)
        if (handle.isEmpty || !handle.get().isAlive) {
            return
        }
        delay(25)
    }
    error("Timed out waiting for process $pid to exit")
}

private fun pidIsAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
