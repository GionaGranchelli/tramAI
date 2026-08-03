package dev.tramai.orchestration

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
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
                        security = dev.tramai.core.security.StepSecurityConfig.Disabled,
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
                    |[ "$2" = "--" ] || exit 22
                    |printf 'cwd=%s prompt=%s' "${'$'}PWD" "$3"
                """.trimMargin(),
            ) { codexCli ->
                val workflow = agentWorkflow("codex-review") {
                    codexStep(
                        name = "review-ui",
                        config = CodexStepConfig(
                            cliPath = codexCli.toString(),
                            workdir = workdir.toString(),
                            security = dev.tramai.core.security.StepSecurityConfig.Disabled,
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
    fun `hermes typed overload decodes the cli response before merge`() {
        withExecutableScript(
            name = "fake-hermes-typed",
            content = """
                |#!/bin/sh
                |printf '42'
            """.trimMargin(),
        ) { hermesCli ->
            val workflow = agentWorkflow("hermes-typed") {
                hermesStep(
                    name = "review-ui",
                    config = HermesStepConfig(cliPath = hermesCli.toString()),
                    prompt = { _, _ -> "decode this" },
                    decode = { response -> response.toInt() },
                    merge = { state, response, _ -> state.copy(hermesDecoded = response) },
                )
            }

            val result = runBlocking { workflow.run(AgentState()) }

            assertThat(result.hermesDecoded).isEqualTo(42)
        }
    }

    @Test
    fun `codex typed overload decodes the cli response before merge`() {
        withExecutableScript(
            name = "fake-codex-typed",
            content = """
                |#!/bin/sh
                |[ "$1" = "exec" ] || exit 21
                |[ "$2" = "--" ] || exit 22
                |printf '7'
            """.trimMargin(),
        ) { codexCli ->
            val workflow = agentWorkflow("codex-typed") {
                codexStep(
                    name = "review-ui",
                    config = CodexStepConfig(cliPath = codexCli.toString()),
                    prompt = { _, _ -> "decode this" },
                    decode = { response -> response.toInt() },
                    merge = { state, response, _ -> state.copy(codexDecoded = response) },
                )
            }

            val result = runBlocking { workflow.run(AgentState()) }

            assertThat(result.codexDecoded).isEqualTo(7)
        }
    }

    @Test
    fun `codex step treats prompts starting with a dash as prompt text`() {
        withExecutableScript(
            name = "fake-codex-dash-prompt",
            content = """
                |#!/bin/sh
                |[ "$1" = "exec" ] || exit 41
                |[ "$2" = "--" ] || exit 42
                |printf 'prompt=%s' "$3"
            """.trimMargin(),
        ) { codexCli ->
            val workflow = agentWorkflow("codex-dash-prompt") {
                codexStep(
                    name = "review-ui",
                    config = CodexStepConfig(
                        cliPath = codexCli.toString(),
                        security = dev.tramai.core.security.StepSecurityConfig.Disabled,
                    ),
                    prompt = { _, _ -> "-review frontend" },
                    merge = { state, response, _ -> state.copy(codexResponse = response) },
                )
            }

            val result = runBlocking { workflow.run(AgentState()) }

            assertThat(result.codexResponse).isEqualTo("prompt=-review frontend")
        }
    }

    @Test
    fun `hermes typed overload wraps decode failures as workflow hermes exceptions`() {
        withExecutableScript(
            name = "fake-hermes-decode-failure",
            content = """
                |#!/bin/sh
                |printf 'not-a-number'
            """.trimMargin(),
        ) { hermesCli ->
            val workflow = agentWorkflow("hermes-decode-failure") {
                hermesStep(
                    name = "review-ui",
                    config = HermesStepConfig(cliPath = hermesCli.toString()),
                    prompt = { _, _ -> "decode this" },
                    decode = { response ->
                        response.toIntOrNull() ?: error("decode failed for '$response'")
                    },
                    merge = { state, response, _ -> state.copy(hermesDecoded = response) },
                )
            }

            assertThatThrownBy {
                runBlocking { workflow.run(AgentState()) }
            }.isInstanceOf(WorkflowHermesException::class.java)
                .hasMessageContaining("decode failed for 'not-a-number'")
        }
    }

    @Test
    fun `codex typed overload wraps decode failures as workflow codex exceptions`() {
        withExecutableScript(
            name = "fake-codex-decode-failure",
            content = """
                |#!/bin/sh
                |[ "$1" = "exec" ] || exit 21
                |[ "$2" = "--" ] || exit 22
                |printf 'not-a-number'
            """.trimMargin(),
        ) { codexCli ->
            val workflow = agentWorkflow("codex-decode-failure") {
                codexStep(
                    name = "review-ui",
                    config = CodexStepConfig(cliPath = codexCli.toString()),
                    prompt = { _, _ -> "decode this" },
                    decode = { response ->
                        response.toIntOrNull() ?: error("decode failed for '$response'")
                    },
                    merge = { state, response, _ -> state.copy(codexDecoded = response) },
                )
            }

            assertThatThrownBy {
                runBlocking { workflow.run(AgentState()) }
            }.isInstanceOf(WorkflowCodexException::class.java)
                .hasMessageContaining("decode failed for 'not-a-number'")
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

                runBlocking {
                    supervisorScope {
                        val execution = async { workflow.run(AgentState()) }
                        val process = awaitAgentProcessHandle(pidFile)

                        val failure = runCatching { execution.await() }.exceptionOrNull()
                        assertThat(failure).isInstanceOf(WorkflowHermesException::class.java)
                            .hasMessageContaining("timed out after 1s")

                        awaitAgentProcessExit(process)
                        assertThat(process.isAlive).isFalse()
                    }
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    @Test
    fun `hermes step includes stderr in non-zero exit failures`() {
        withExecutableScript(
            name = "failing-hermes",
            content = """
                |#!/bin/sh
                |echo 'hermes stderr message' >&2
                |exit 17
            """.trimMargin(),
        ) { hermesCli ->
            val workflow = agentWorkflow("hermes-non-zero") {
                hermesStep(
                    name = "review-ui",
                    config = HermesStepConfig(cliPath = hermesCli.toString()),
                    prompt = { _, _ -> "fail" },
                    merge = { state, response, _ -> state.copy(hermesResponse = response) },
                )
            }

            assertThatThrownBy {
                runBlocking { workflow.run(AgentState()) }
            }.isInstanceOf(WorkflowHermesException::class.java)
                .hasMessageContaining("exit code 17")
                .hasMessageContaining("hermes stderr message")
        }
    }

    @Test
    fun `codex step includes stderr in non-zero exit failures`() {
        withExecutableScript(
            name = "failing-codex",
            content = """
                |#!/bin/sh
                |echo 'codex stderr message' >&2
                |exit 27
            """.trimMargin(),
        ) { codexCli ->
            val workflow = agentWorkflow("codex-non-zero") {
                codexStep(
                    name = "review-ui",
                    config = CodexStepConfig(cliPath = codexCli.toString()),
                    prompt = { _, _ -> "fail" },
                    merge = { state, response, _ -> state.copy(codexResponse = response) },
                )
            }

            assertThatThrownBy {
                runBlocking { workflow.run(AgentState()) }
            }.isInstanceOf(WorkflowCodexException::class.java)
                .hasMessageContaining("exit code 27")
                .hasMessageContaining("codex stderr message")
        }
    }

    @Test
    fun `codex step uses a custom cli path`() {
        withExecutableScript(
            name = "custom-codex-cli",
            content = """
                |#!/bin/sh
                |[ "$1" = "exec" ] || exit 31
                |[ "$2" = "--" ] || exit 32
                |printf 'custom:%s' "$3"
            """.trimMargin(),
        ) { codexCli ->
            val workflow = agentWorkflow("codex-custom-path") {
                codexStep(
                    name = "review-ui",
                    config = CodexStepConfig(
                        cliPath = codexCli.toString(),
                        security = dev.tramai.core.security.StepSecurityConfig.Disabled,
                    ),
                    prompt = { _, _ -> "hello codex" },
                    merge = { state, response, _ -> state.copy(codexResponse = response) },
                )
            }

            val result = runBlocking { workflow.run(AgentState()) }

            assertThat(result.codexResponse).isEqualTo("custom:hello codex")
        }
    }

    @Test
    fun `hermes step timeout cleans up descendant processes`() {
        val parentPidFile = Files.createTempFile("workflow-hermes-timeout-parent", ".pid")
        val childPidFile = Files.createTempFile("workflow-hermes-timeout-child", ".pid")
        try {
            withExecutableScript(
                name = "slow-hermes-descendants",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${parentPidFile.toAbsolutePath()}'
                    |sleep 30 &
                    |child=$!
                    |echo ${'$'}child > '${childPidFile.toAbsolutePath()}'
                    |wait ${'$'}child
                """.trimMargin(),
            ) { hermesCli ->
                val workflow = agentWorkflow("hermes-timeout-descendants") {
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

                runBlocking {
                    supervisorScope {
                        val execution = async { workflow.run(AgentState()) }
                        val parentProcess = awaitAgentProcessHandle(parentPidFile)
                        val childProcess = awaitAgentProcessHandle(childPidFile)

                        val failure = runCatching { execution.await() }.exceptionOrNull()
                        assertThat(failure).isInstanceOf(WorkflowHermesException::class.java)
                            .hasMessageContaining("timed out after 1s")

                        awaitAgentProcessExit(parentProcess)
                        awaitAgentProcessExit(childProcess)
                        assertThat(parentProcess.isAlive).isFalse()
                        assertThat(childProcess.isAlive).isFalse()
                    }
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
            Files.deleteIfExists(childPidFile)
        }
    }

    @Test
    fun `codex step cancellation cleans up descendant processes`() {
        val parentPidFile = Files.createTempFile("workflow-codex-cancel-parent", ".pid")
        val childPidFile = Files.createTempFile("workflow-codex-cancel-child", ".pid")
        try {
            withExecutableScript(
                name = "slow-codex-descendants",
                content = """
                    |#!/bin/sh
                    |echo $$ > '${parentPidFile.toAbsolutePath()}'
                    |sleep 30 &
                    |child=$!
                    |echo ${'$'}child > '${childPidFile.toAbsolutePath()}'
                    |wait ${'$'}child
                """.trimMargin(),
            ) { codexCli ->
                val workflow = agentWorkflow("codex-cancel-descendants") {
                    codexStep(
                        name = "review-ui",
                        config = CodexStepConfig(cliPath = codexCli.toString()),
                        prompt = { _, _ -> "slow prompt" },
                        merge = { state, response, _ -> state.copy(codexResponse = response) },
                    )
                }

                runBlocking {
                    val job = launch {
                        workflow.run(AgentState())
                    }

                    val parentProcess = awaitAgentProcessHandle(parentPidFile)
                    val childProcess = awaitAgentProcessHandle(childPidFile)

                    job.cancel()
                    job.join()

                    awaitAgentProcessExit(parentProcess)
                    awaitAgentProcessExit(childProcess)
                    assertThat(parentProcess.isAlive).isFalse()
                    assertThat(childProcess.isAlive).isFalse()
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
            Files.deleteIfExists(childPidFile)
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
    val hermesDecoded: Int? = null,
    val codexDecoded: Int? = null,
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

private suspend fun awaitAgentProcessHandle(pidFile: Path): ProcessHandle {
    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (System.nanoTime() < deadlineNanos) {
        if (Files.exists(pidFile)) {
            val rawPid = Files.readString(pidFile).trim()
            if (rawPid.isNotEmpty()) {
                val pid = rawPid.toLong()
                return ProcessHandle.of(pid).orElseThrow {
                    IllegalStateException("Agent process $pid exited before its handle was captured")
                }
            }
        }
        delay(25)
    }
    error("Timed out waiting for agent PID at $pidFile")
}

private fun awaitAgentProcessExit(process: ProcessHandle) {
    process.onExit().get(20, TimeUnit.SECONDS)
}
