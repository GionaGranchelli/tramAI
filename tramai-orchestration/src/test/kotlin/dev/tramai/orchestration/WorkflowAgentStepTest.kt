package dev.tramai.orchestration

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class WorkflowAgentStepTest {
    @Test
    fun `hermes step sends the prompt captures the response and emits observer attributes`() {
        withExecutableScript(
            name = "fake-hermes",
            content = """
                |#!/bin/sh
                |[ "$1" = "chat" ] || exit 11
                |[ "$2" = "-q" ] || exit 12
                |[ "$4" = "--model" ] || exit 13
                |printf 'model=%s prompt=%s' "$5" "$3"
            """.trimMargin(),
        ) { hermesCli ->
            val observer = RecordingAgentWorkflowObserver()
            val workflow = agentWorkflow("hermes-review") {
                hermesStep(
                    name = "review-ui",
                    config = HermesStepConfig(
                        cliPath = hermesCli.toString(),
                        model = "claude-sonnet-4",
                    ),
                    prompt = { state, _ -> "Audit ${state.target}" },
                    merge = { state, response, _ -> state.copy(hermesResponse = response) },
                )
            }

            val result = runBlocking {
                workflow.run(
                    initialState = AgentState(target = "https://example.test"),
                    observer = observer,
                )
            }

            assertThat(result.hermesResponse)
                .isEqualTo("model=claude-sonnet-4 prompt=Audit https://example.test")

            val startedAttributes = observer.events.single { (eventName, _) ->
                eventName == "tramai.workflow.hermes.started"
            }.second
            val completedAttributes = observer.events.single { (eventName, _) ->
                eventName == "tramai.workflow.hermes.completed"
            }.second

            assertThat(startedAttributes)
                .containsEntry("step_name", "review-ui")
                .containsEntry("agent_type", "hermes")
                .containsEntry("prompt_length", "Audit https://example.test".length)
            assertThat(completedAttributes)
                .containsEntry("step_name", "review-ui")
                .containsEntry("agent_type", "hermes")
                .containsEntry("prompt_length", "Audit https://example.test".length)
                .containsEntry(
                    "response_length",
                    "model=claude-sonnet-4 prompt=Audit https://example.test".length,
                )
                .containsKey("duration_ms")
        }
    }

    @Test
    fun `codex step sends the prompt and captures the response from the configured workdir`() {
        val workdir = Files.createTempDirectory("workflow-codex-workdir")
        try {
            withExecutableScript(
                name = "fake-codex",
                content = """
                    |#!/bin/sh
                    |[ "$1" = "exec" ] || exit 21
                    |printf 'cwd=%s prompt=%s' "${'$'}PWD" "$2"
                """.trimMargin(),
            ) { codexCli ->
                val workflow = agentWorkflow("codex-review") {
                    codexStep(
                        name = "review-ui",
                        config = CodexStepConfig(
                            cliPath = codexCli.toString(),
                            workdir = workdir.toString(),
                        ),
                        prompt = { state, _ -> "Inspect ${state.target}" },
                        merge = { state, response, _ -> state.copy(codexResponse = response) },
                    )
                }

                val result = runBlocking {
                    workflow.run(AgentState(target = "frontend"))
                }

                assertThat(result.codexResponse)
                    .isEqualTo("cwd=${workdir} prompt=Inspect frontend")
            }
        } finally {
            Files.deleteIfExists(workdir)
        }
    }

    @Test
    fun `hermes step timeout kills the cli process and fails the step`() {
        val pidFile = Files.createTempFile("workflow-hermes-timeout", ".pid")
        try {
            withExecutableScript(
                name = "slow-hermes",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${pidFile.toAbsolutePath()}'
                    |exec sleep 30
                """.trimMargin(),
            ) { hermesCli ->
                val workflow = agentWorkflow("hermes-timeout") {
                    hermesStep(
                        name = "review-ui",
                        config = HermesStepConfig(
                            cliPath = hermesCli.toString(),
                            timeoutSeconds = 1,
                        ),
                        prompt = { _, _ -> "slow prompt" },
                        merge = { state, response, _ -> state.copy(hermesResponse = response) },
                    )
                }

                assertThatThrownBy {
                    runBlocking { workflow.run(AgentState()) }
                }.isInstanceOf(WorkflowHermesException::class.java)
                    .hasMessageContaining("timed out after 1s")

                runBlocking {
                    val pid = awaitAgentPid(pidFile)
                    awaitAgentProcessExit(pid)
                    assertThat(ProcessHandle.of(pid).map { it.isAlive }.orElse(false)).isFalse()
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    @Test
    fun `codex step uses a custom cli path`() {
        withExecutableScript(
            name = "custom-codex-cli",
            content = """
                |#!/bin/sh
                |[ "$1" = "exec" ] || exit 31
                |printf 'custom:%s' "$2"
            """.trimMargin(),
        ) { codexCli ->
            val workflow = agentWorkflow("codex-custom-path") {
                codexStep(
                    name = "review-ui",
                    config = CodexStepConfig(cliPath = codexCli.toString()),
                    prompt = { _, _ -> "hello codex" },
                    merge = { state, response, _ -> state.copy(codexResponse = response) },
                )
            }

            val result = runBlocking { workflow.run(AgentState()) }

            assertThat(result.codexResponse).isEqualTo("custom:hello codex")
        }
    }

    @Test
    fun `hermes step appends a truncation footer when output exceeds the limit`() {
        withExecutableScript(
            name = "large-hermes",
            content = """
                |#!/bin/sh
                |python3 - <<'PY'
                |print("x" * 128, end="")
                |PY
            """.trimMargin(),
        ) { hermesCli ->
            val workflow = agentWorkflow("hermes-truncation") {
                hermesStep(
                    name = "review-ui",
                    config = HermesStepConfig(
                        cliPath = hermesCli.toString(),
                        maxOutputBytes = 32,
                    ),
                    prompt = { _, _ -> "large output" },
                    merge = { state, response, _ -> state.copy(hermesResponse = response) },
                )
            }

            val result = runBlocking { workflow.run(AgentState()) }

            assertThat(result.hermesResponse)
                .startsWith("x".repeat(32))
                .contains("[truncated output: captured 32 bytes of 128 total bytes]")
        }
    }
}

private data class AgentState(
    val target: String = "",
    val hermesResponse: String? = null,
    val codexResponse: String? = null,
)

private fun agentWorkflow(
    name: String,
    configure: WorkflowBuilder<AgentState>.() -> Unit,
): Workflow<AgentState, AgentState> = workflow<AgentState>(name, configure = configure).build { it }

private class RecordingAgentWorkflowObserver : WorkflowObserver {
    val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    override fun onWorkflowEvent(
        workflowName: String,
        name: String,
        attributes: Map<String, Any?>,
        context: WorkflowContext,
    ) {
        events += name to attributes
    }
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

private suspend fun awaitAgentPid(pidFile: Path): Long {
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
    error("Timed out waiting for agent PID at $pidFile")
}

private suspend fun awaitAgentProcessExit(pid: Long) {
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
