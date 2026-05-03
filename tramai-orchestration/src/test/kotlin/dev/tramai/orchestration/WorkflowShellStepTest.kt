package dev.tramai.orchestration

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.nio.file.Files
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
    fun `shell step workflow events redact command arguments and output content`() {
        val observer = RecordingShellWorkflowObserver()
        val workflow = shellWorkflow("shell-redaction") {
            shellStep(
                name = "redacted",
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
            .allMatch { it in setOf("exit_code", "stdout_bytes", "stderr_bytes", "stream", "actual_size", "max_size") }
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
