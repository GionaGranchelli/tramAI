package dev.tramai.orchestration

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class WorkflowShellStepTest {
    @Test
    fun `shell step executes a command and merges stdout into state`() {
        val workflow = shellWorkflow("shell-echo") {
            shellStep(
                name = "echo",
                config = ShellStepConfig(allowedCommands = setOf("echo")),
                definition = ShellCommandDefinition(executable = "echo"),
                command = { _, _ -> ShellCommand(command = listOf("echo", "hello")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        val result = runBlocking { workflow.run(ShellState()) }

        assertThat(result.result).isEqualTo(
            ShellResult(
                exitCode = 0,
                stdout = "hello\n",
                stderr = "",
                truncated = false,
            ),
        )
    }

    @Test
    fun `shell step captures stdout and stderr independently`() {
        val workflow = shellWorkflow("shell-stderr") {
            shellStep(
                name = "capture",
                config = ShellStepConfig(allowedCommands = setOf("sh")),
                definition = ShellCommandDefinition(executable = "sh"),
                command = { _, _ -> ShellCommand(command = listOf("sh", "-c", "echo ok; echo err >&2")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        val result = runBlocking { workflow.run(ShellState()) }

        assertThat(result.result?.exitCode).isEqualTo(0)
        assertThat(result.result?.stdout).isEqualTo("ok\n")
        assertThat(result.result?.stderr).isEqualTo("err\n")
    }

    @Test
    fun `shell step throws on non-zero exit by default`() {
        val workflow = shellWorkflow("shell-non-zero") {
            shellStep(
                name = "fail",
                config = ShellStepConfig(allowedCommands = setOf("sh")),
                definition = ShellCommandDefinition(executable = "sh"),
                command = { _, _ -> ShellCommand(command = listOf("sh", "-c", "exit 3")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        assertThatThrownBy {
            runBlocking { workflow.run(ShellState()) }
        }.isInstanceOf(WorkflowShellException::class.java)
            .hasMessageContaining("exit code 3")
    }

    @Test
    fun `shell step can allow non-zero exits`() {
        val workflow = shellWorkflow("shell-non-zero-allowed") {
            shellStep(
                name = "allow",
                config = ShellStepConfig(
                    failOnNonZeroExit = false,
                    allowedCommands = setOf("sh"),
                ),
                definition = ShellCommandDefinition(executable = "sh"),
                command = { _, _ -> ShellCommand(command = listOf("sh", "-c", "echo warn >&2; exit 3")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        val result = runBlocking { workflow.run(ShellState()) }

        assertThat(result.result?.exitCode).isEqualTo(3)
        assertThat(result.result?.stderr).isEqualTo("warn\n")
    }

    @Test
    fun `shell step kills timed out processes and emits a timeout event`() {
        val observer = RecordingShellWorkflowObserver()
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
            runBlocking {
                workflow.run(
                    initialState = ShellState(),
                    observer = observer,
                )
            }
        }.isInstanceOf(WorkflowShellException::class.java)
            .hasMessageContaining("timed out")

        assertThat(observer.eventNames).contains("tramai.workflow.shell.timeout")
    }

    @Test
    fun `shell step timeout cleans up descendant processes`() {
        val parentPidFile = Files.createTempFile("workflow-shell-timeout-parent", ".pid")
        val childPidFile = Files.createTempFile("workflow-shell-timeout-child", ".pid")
        try {
            val workflow = shellWorkflow("shell-timeout-descendants") {
                shellStep(
                    name = "sleep",
                    config = ShellStepConfig(timeoutSeconds = 1, allowedCommands = setOf("sh")),
                    definition = ShellCommandDefinition(executable = "sh"),
                    command = { _, _ ->
                        ShellCommand(
                            command = listOf(
                                "sh",
                                "-c",
                                "echo $$ > '${parentPidFile.toAbsolutePath()}'; sleep 30 & child=$!; echo \$child > '${childPidFile.toAbsolutePath()}'; wait \$child",
                            ),
                        )
                    },
                    merge = { state, result, _ -> state.copy(result = result) },
                )
            }

            runBlocking {
                supervisorScope {
                    val execution = async { workflow.run(ShellState()) }
                    val parentProcess = awaitProcessHandle(parentPidFile)
                    val childProcess = awaitProcessHandle(childPidFile)

                    val failure = runCatching { execution.await() }.exceptionOrNull()
                    assertThat(failure).isInstanceOf(WorkflowShellException::class.java)
                        .hasMessageContaining("timed out")

                    awaitProcessExit(parentProcess)
                    awaitProcessExit(childProcess)
                    assertThat(parentProcess?.isAlive ?: false).isFalse()
                    assertThat(childProcess?.isAlive ?: false).isFalse()
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
            Files.deleteIfExists(childPidFile)
        }
    }

    @Test
    fun `shell step destroys the process when the workflow coroutine is cancelled`() {
        val parentPidFile = Files.createTempFile("workflow-shell-cancel-parent", ".pid")
        val childPidFile = Files.createTempFile("workflow-shell-cancel-child", ".pid")
        try {
            val workflow = shellWorkflow("shell-cancel") {
                shellStep(
                    name = "sleep",
                    config = ShellStepConfig(allowedCommands = setOf("sh")),
                    definition = ShellCommandDefinition(executable = "sh"),
                    command = { _, _ ->
                        ShellCommand(
                            command = listOf(
                                "sh",
                                "-c",
                                "echo $$ > '${parentPidFile.toAbsolutePath()}'; sleep 30 & child=$!; echo \$child > '${childPidFile.toAbsolutePath()}'; wait \$child",
                            ),
                        )
                    },
                    merge = { state, result, _ -> state.copy(result = result) },
                )
            }
            runBlocking {
                val job = launch {
                    workflow.run(ShellState())
                }

                val parentProcess = awaitProcessHandle(parentPidFile)
                val childProcess = awaitProcessHandle(childPidFile)
                job.cancelAndJoin()

                awaitProcessExit(parentProcess)
                awaitProcessExit(childProcess)
                assertThat(parentProcess?.isAlive ?: false).isFalse()
                assertThat(childProcess?.isAlive ?: false).isFalse()
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
            Files.deleteIfExists(childPidFile)
        }
    }

    @Test
    fun `shell step truncates oversized output and records it`() {
        val observer = RecordingShellWorkflowObserver()
        val workflow = shellWorkflow("shell-truncate") {
            shellStep(
                name = "large-output",
                config = ShellStepConfig(maxOutputBytes = 1_024, allowedCommands = setOf("sh")),
                definition = ShellCommandDefinition(executable = "sh"),
                command = { _, _ ->
                    ShellCommand(
                        command = listOf("sh", "-c", "head -c 102400 /dev/zero | tr '\\000' 'a'"),
                    )
                },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        val result = runBlocking {
            workflow.run(
                initialState = ShellState(),
                observer = observer,
            )
        }

        assertThat(result.result?.stdout).hasSize(1_024)
        assertThat(result.result?.stderr).isEmpty()
        assertThat(result.result?.truncated).isTrue()
        assertThat(observer.eventNames).contains("tramai.workflow.shell.truncated")
    }

    @Test
    fun `shell step runs in the configured working directory`() {
        val workdir = Files.createTempDirectory("workflow-shell-step")
        try {
            val workflow = shellWorkflow("shell-workdir") {
                shellStep(
                    name = "pwd",
                    config = ShellStepConfig(allowedCommands = setOf("pwd")),
                    definition = ShellCommandDefinition(
                        hasWorkdir = true,
                        executable = "pwd",
                    ),
                    command = { _, _ ->
                        ShellCommand(
                            command = listOf("pwd"),
                            workdir = workdir.toString(),
                        )
                    },
                    merge = { state, result, _ -> state.copy(result = result) },
                )
            }

            val result = runBlocking { workflow.run(ShellState()) }

            assertThat(result.result?.stdout).isEqualTo("${workdir}\n")
        } finally {
            Files.deleteIfExists(workdir)
        }
    }

    @Test
    fun `shell step sets environment variables for the process`() {
        val workflow = shellWorkflow("shell-env") {
            shellStep(
                name = "env",
                config = ShellStepConfig(allowedCommands = setOf("sh")),
                definition = ShellCommandDefinition(
                    envKeys = setOf("MY_VAR"),
                    executable = "sh",
                ),
                command = { _, _ ->
                    ShellCommand(
                        command = listOf("sh", "-c", "echo \$MY_VAR"),
                        env = mapOf("MY_VAR" to "hello"),
                    )
                },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        val result = runBlocking { workflow.run(ShellState()) }

        assertThat(result.result?.stdout).isEqualTo("hello\n")
    }

    @Test
    fun `shell step redacts command names in failure errors`() {
        val observer = RecordingShellWorkflowObserver()
        val secretCommand = "my-secret-tool"
        val workflow = shellWorkflow("shell-redaction-failure") {
            shellStep(
                name = "missing-command",
                config = ShellStepConfig(allowedCommands = setOf(secretCommand)),
                definition = ShellCommandDefinition(executable = secretCommand),
                command = { _, _ -> ShellCommand(command = listOf(secretCommand)) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = ShellState(),
                    observer = observer,
                )
            }
        }.isInstanceOf(WorkflowShellException::class.java)
            .hasMessageContaining("[command]")
            .hasMessageNotContaining(secretCommand)

        assertThat(observer.failedErrors)
            .isNotEmpty
            .allSatisfy { error ->
                assertThat(error)
                    .isInstanceOf(WorkflowShellException::class.java)
                    .hasMessageContaining("[command]")
                    .hasMessageNotContaining(secretCommand)
            }
    }

    @Test
    fun `shell step rejects denied commands`() {
        val workflow = shellWorkflow("shell-denied") {
            shellStep(
                name = "echo",
                config = ShellStepConfig(
                    allowedCommands = setOf("echo"),
                    deniedCommands = setOf("echo"),
                ),
                definition = ShellCommandDefinition(executable = "echo"),
                command = { _, _ -> ShellCommand(command = listOf("echo", "hello")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        val error = runCatching {
            runBlocking { workflow.run(ShellState()) }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(WorkflowShellException::class.java)
        assertThat(error).hasMessageContaining("denylist")
        assertThat(error?.cause).isNull()
    }

    @Test
    fun `shell step blocks commands outside the allowlist`() {
        val workflow = shellWorkflow("shell-allowlist") {
            shellStep(
                name = "echo",
                config = ShellStepConfig(allowedCommands = setOf("pwd")),
                definition = ShellCommandDefinition(executable = "echo"),
                command = { _, _ -> ShellCommand(command = listOf("echo", "hello")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        assertThatThrownBy {
            runBlocking { workflow.run(ShellState()) }
        }.isInstanceOf(WorkflowShellException::class.java)
            .hasMessageContaining("allowlist")
    }

    @Test
    fun `shell step workflow events redact command arguments and output content`() {
        val observer = RecordingShellWorkflowObserver()
        val workflow = shellWorkflow("shell-redaction") {
            shellStep(
                name = "redacted",
                config = ShellStepConfig(allowedCommands = setOf("sh")),
                definition = ShellCommandDefinition(
                    envKeys = setOf("MY_SECRET"),
                    executable = "sh",
                ),
                command = { _, _ ->
                    ShellCommand(
                        command = listOf(
                            "sh",
                            "-c",
                            "printf 'cmd-secret-token'; printf 'stderr-secret-token' >&2",
                        ),
                        env = mapOf("MY_SECRET" to "env-secret-token"),
                    )
                },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        runBlocking {
            workflow.run(
                initialState = ShellState(),
                observer = observer,
            )
        }

        val shellEvents = observer.events.filter { (eventName, _) ->
            eventName.startsWith("tramai.workflow.shell.")
        }
        val renderedAttributes = shellEvents.joinToString(separator = "\n") { (_, attributes) ->
            attributes.entries.joinToString(separator = "\n") { "${it.key}=${it.value}" }
        }

        assertThat(renderedAttributes).doesNotContain("cmd-secret-token")
        assertThat(renderedAttributes).doesNotContain("stderr-secret-token")
        assertThat(renderedAttributes).doesNotContain("env-secret-token")
        assertThat(shellEvents.flatMap { (_, attributes) -> attributes.keys })
            .allMatch { it in setOf("step_name", "exit_code", "stdout_bytes", "stderr_bytes", "stream", "actual_size", "max_size") }
    }

    @Test
    fun `shell step started and completed events include the step name`() {
        val observer = RecordingShellWorkflowObserver()
        val workflow = shellWorkflow("shell-event-step-name") {
            shellStep(
                name = "echo",
                config = ShellStepConfig(allowedCommands = setOf("echo")),
                definition = ShellCommandDefinition(executable = "echo"),
                command = { _, _ -> ShellCommand(command = listOf("echo", "hello")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        runBlocking {
            workflow.run(
                initialState = ShellState(),
                observer = observer,
            )
        }

        val startedAttributes = observer.events.single { (eventName, _) ->
            eventName == "tramai.workflow.shell.started"
        }.second
        val completedAttributes = observer.events.single { (eventName, _) ->
            eventName == "tramai.workflow.shell.completed"
        }.second

        assertThat(startedAttributes).containsEntry("step_name", "echo")
        assertThat(completedAttributes).containsEntry("step_name", "echo")
    }

    @Test
    fun `shell step passes multiple command arguments in order`() {
        val workflow = shellWorkflow("shell-multi-arg") {
            shellStep(
                name = "echo-many",
                config = ShellStepConfig(allowedCommands = setOf("echo")),
                definition = ShellCommandDefinition(executable = "echo"),
                command = { _, _ -> ShellCommand(command = listOf("echo", "arg1", "arg2", "arg3")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        val result = runBlocking { workflow.run(ShellState()) }

        assertThat(result.result?.stdout).isEqualTo("arg1 arg2 arg3\n")
    }

    @Test
    fun `shell step config default constructor denies all commands`() {
        val workflow = shellWorkflow("shell-default-deny-all") {
            shellStep(
                name = "echo",
                config = ShellStepConfig(),
                definition = ShellCommandDefinition(executable = "echo"),
                command = { _, _ -> ShellCommand(command = listOf("echo", "hello")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        assertThatThrownBy {
            runBlocking { workflow.run(ShellState()) }
        }.isInstanceOf(WorkflowShellException::class.java)
            .hasMessageContaining("not in allowlist")
    }

    @Test
    fun `shell step config allows only git commands when explicitly allowlisted`() {
        val workflow = shellWorkflow("shell-git-allowlist") {
            shellStep(
                name = "git-version",
                config = ShellStepConfig(allowedCommands = setOf("git")),
                definition = ShellCommandDefinition(executable = "git"),
                command = { _, _ -> ShellCommand(command = listOf("git", "--version")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        val result = runBlocking { workflow.run(ShellState()) }

        assertThat(result.result?.exitCode).isEqualTo(0)
        assertThat(result.result?.stdout).contains("git version")
    }

    @Test
    fun `shell step execute throws when allowed commands is empty`() {
        val workflow = shellWorkflow("shell-explicit-empty-allowlist") {
            shellStep(
                name = "echo",
                config = ShellStepConfig(allowedCommands = emptySet()),
                definition = ShellCommandDefinition(executable = "echo"),
                command = { _, _ -> ShellCommand(command = listOf("echo", "hello")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        assertThatThrownBy {
            runBlocking { workflow.run(ShellState()) }
        }.isInstanceOf(WorkflowShellException::class.java)
            .hasMessageContaining("not in allowlist")
    }
    @Test
    fun `shell step with default config denies all commands`() {
        val workflow = shellWorkflow("shell-default-deny-all") {
            shellStep(
                name = "echo",
                config = ShellStepConfig(),
                definition = ShellCommandDefinition(executable = "echo"),
                command = { _, _ -> ShellCommand(command = listOf("echo", "hello")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        assertThatThrownBy {
            runBlocking { workflow.run(ShellState()) }
        }.isInstanceOf(WorkflowShellException::class.java)
            .hasMessageContaining("not in allowlist")
    }

    @Test
    fun `shell step with explicit allowlist continues to work unchanged`() {
        val workflow = shellWorkflow("shell-allowlist-continues") {
            shellStep(
                name = "echo",
                config = ShellStepConfig(allowedCommands = setOf("echo")),
                definition = ShellCommandDefinition(executable = "echo"),
                command = { _, _ -> ShellCommand(command = listOf("echo", "migration-ok")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        val result = runBlocking { workflow.run(ShellState()) }

        assertThat(result.result?.stdout).contains("migration-ok")
    }
}

private data class ShellState(
    val result: ShellResult? = null,
)

private fun shellWorkflow(
    name: String,
    configure: WorkflowBuilder<ShellState>.() -> Unit,
): Workflow<ShellState, ShellState> = workflow<ShellState>(name, configure = configure).build { it }

private class RecordingShellWorkflowObserver : WorkflowObserver {
    val eventNames = mutableListOf<String>()
    val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    val failedErrors = mutableListOf<Throwable>()

    override fun onWorkflowEvent(
        workflowName: String,
        name: String,
        attributes: Map<String, Any?>,
        context: WorkflowContext,
    ) {
        eventNames += name
        events += name to attributes
    }

    override fun onStepFailed(
        workflowName: String,
        stepName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        failedErrors += error
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
                // be captured — a legitimate outcome for termination tests.
                return ProcessHandle.of(pid).orElse(null)
            }
        }
        delay(25)
    }
    error("Timed out waiting for shell step PID at $pidFile")
}

private fun awaitProcessExit(process: ProcessHandle?) {
    if (process == null) {
        return
    }
    process.onExit().get(20, TimeUnit.SECONDS)
}
