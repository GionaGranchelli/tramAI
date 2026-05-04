package dev.tramai.orchestration

import java.io.File

data class CodexStepConfig(
    val timeoutSeconds: Long = 180,
    val maxOutputBytes: Long = 1_048_576,
    val cliPath: String = "codex",
    val workdir: String? = null,
) {
    init {
        require(timeoutSeconds > 0) { "CodexStepConfig.timeoutSeconds must be greater than zero" }
        require(maxOutputBytes >= 0) { "CodexStepConfig.maxOutputBytes must be zero or greater" }
        require(maxOutputBytes <= Int.MAX_VALUE.toLong()) {
            "CodexStepConfig.maxOutputBytes must be less than or equal to ${Int.MAX_VALUE}"
        }
        require(cliPath.isNotBlank()) { "CodexStepConfig.cliPath must not be blank" }
        require(workdir == null || workdir.isNotBlank()) { "CodexStepConfig.workdir must not be blank" }
    }
}

class WorkflowCodexException(
    val stepName: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("Workflow Codex step '$stepName' $message", cause)

internal data class CodexWorkflowStep<S>(
    override val name: String,
    val promptBuilder: suspend (S, WorkflowContext) -> String,
    val merge: suspend (S, String, WorkflowContext) -> S,
    val config: CodexStepConfig = CodexStepConfig(),
) : InternalWorkflowStep<S> {
    suspend fun execute(
        workflowName: String,
        state: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
    ): S {
        val prompt = try {
            promptBuilder(state, context)
        } catch (error: Throwable) {
            error.rethrowAgentCancellation()
            throw wrapCodexError(error)
        }

        val result = try {
            executeAgentCli(
                workflowName = workflowName,
                stepName = name,
                eventPrefix = "tramai.workflow.codex",
                agentType = "codex",
                processBuilder = ProcessBuilder(
                    listOf(config.cliPath, "exec", prompt),
                ).apply {
                    config.workdir?.let { directory(File(it)) }
                },
                timeoutSeconds = config.timeoutSeconds,
                maxOutputBytes = config.maxOutputBytes,
                promptLength = prompt.length,
                context = context,
                observer = observer,
            )
        } catch (error: Throwable) {
            error.rethrowAgentCancellation()
            throw wrapCodexError(error)
        }

        if (result.exitCode != 0) {
            throw WorkflowCodexException(
                stepName = name,
                message = "failed with exit code ${result.exitCode}",
            )
        }

        return try {
            merge(state, result.output, context)
        } catch (error: Throwable) {
            error.rethrowAgentCancellation()
            throw wrapCodexError(error)
        }
    }

    private fun wrapCodexError(error: Throwable): WorkflowCodexException = when (error) {
        is WorkflowCodexException -> error
        is AgentCliTimeoutException -> WorkflowCodexException(
            stepName = name,
            message = error.message ?: "timed out after ${config.timeoutSeconds}s",
            cause = error,
        )
        else -> WorkflowCodexException(
            stepName = name,
            message = "failed: ${error.message ?: error::class.java.simpleName}",
            cause = error,
        )
    }
}
