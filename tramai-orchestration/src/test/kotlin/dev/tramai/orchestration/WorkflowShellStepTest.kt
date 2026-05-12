package dev.tramai.orchestration

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
                config = ShellStepConfig(failOnNonZeroExit = false),
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
                config = ShellStepConfig(timeoutSeconds = 1),
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
                    config = ShellStepConfig(timeoutSeconds = 1),
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

            assertThatThrownBy {
                runBlocking { workflow.run(ShellState()) }
            }.isInstanceOf(WorkflowShellException::class.java)
                .hasMessageContaining("timed out")

            runBlocking {
                val parentPid = awaitPid(parentPidFile)
                val childPid = awaitPid(childPidFile)
                awaitProcessExit(parentPid)
                awaitProcessExit(childPid)
                assertThat(ProcessHandle.of(parentPid).map { it.isAlive }.orElse(false)).isFalse()
                assertThat(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false)).isFalse()
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

                val parentPid = awaitPid(parentPidFile)
                val childPid = awaitPid(childPidFile)
                job.cancelAndJoin()

                awaitProcessExit(parentPid)
                awaitProcessExit(childPid)
                assertThat(ProcessHandle.of(parentPid).map { it.isAlive }.orElse(false)).isFalse()
                assertThat(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false)).isFalse()
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
                config = ShellStepConfig(maxOutputBytes = 1_024),
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
                    definition = ShellCommandDefinition(hasWorkdir = true),
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
                definition = ShellCommandDefinition(envKeys = setOf("MY_VAR")),
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
                config = ShellStepConfig(deniedCommands = setOf("echo")),
                command = { _, _ -> ShellCommand(command = listOf("echo", "hello")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        assertThatThrownBy {
            runBlocking { workflow.run(ShellState()) }
        }.isInstanceOf(WorkflowShellException::class.java)
            .hasMessageContaining("denylist")
    }

    @Test
    fun `shell step blocks commands outside the allowlist`() {
        val workflow = shellWorkflow("shell-allowlist") {
            shellStep(
                name = "echo",
                config = ShellStepConfig(allowedCommands = setOf("pwd")),
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
                definition = ShellCommandDefinition(envKeys = setOf("MY_SECRET")),
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
                command = { _, _ -> ShellCommand(command = listOf("echo", "arg1", "arg2", "arg3")) },
                merge = { state, result, _ -> state.copy(result = result) },
            )
        }

        val result = runBlocking { workflow.run(ShellState()) }

        assertThat(result.result?.stdout).isEqualTo("arg1 arg2 arg3\n")
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
    error("Timed out waiting for shell step PID at $pidFile")
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
