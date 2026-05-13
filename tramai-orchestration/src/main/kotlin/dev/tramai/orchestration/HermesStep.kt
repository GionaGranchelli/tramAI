package dev.tramai.orchestration

import dev.tramai.core.security.StepSecurityConfig
import dev.tramai.core.security.ValidationResult

data class HermesStepConfig(
    val timeoutSeconds: Long = 180,
    val maxOutputBytes: Long = 1_048_576,
    val cliPath: String = "hermes",
    val model: String = "claude-sonnet-4",
    val security: StepSecurityConfig = StepSecurityConfig.Default,
) {
    init {
        require(timeoutSeconds > 0) { "HermesStepConfig.timeoutSeconds must be greater than zero" }
        require(maxOutputBytes >= 0) { "HermesStepConfig.maxOutputBytes must be zero or greater" }
        require(maxOutputBytes <= Int.MAX_VALUE.toLong()) {
            "HermesStepConfig.maxOutputBytes must be less than or equal to ${Int.MAX_VALUE}"
        }
        require(cliPath.isNotBlank()) { "HermesStepConfig.cliPath must not be blank" }
        require(model.isNotBlank()) { "HermesStepConfig.model must not be blank" }
    }
}

class WorkflowHermesException(
    val stepName: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("Workflow Hermes step '$stepName' $message", cause)

internal data class HermesWorkflowStep<S>(
    override val name: String,
    val promptBuilder: suspend (S, WorkflowContext) -> String,
    val merge: suspend (S, String, WorkflowContext) -> S,
    val config: HermesStepConfig = HermesStepConfig(),
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
            throw wrapHermesError(error)
        }
        val resolvedSecurity = resolveStepSecurity(prompt, config.security)

        val result = try {
            executeAgentCli(
                workflowName = workflowName,
                stepName = name,
                eventPrefix = "tramai.workflow.hermes",
                agentType = "hermes",
                processBuilder = ProcessBuilder(
                    listOf(config.cliPath, "chat", "-q", resolvedSecurity.defendedPrompt, "--model", config.model),
                ),
                timeoutSeconds = config.timeoutSeconds,
                maxOutputBytes = config.maxOutputBytes,
                promptLength = resolvedSecurity.defendedPrompt.length,
                context = context,
                observer = observer,
            )
        } catch (error: Throwable) {
            error.rethrowAgentCancellation()
            throw wrapHermesError(error)
        }

        if (result.exitCode != 0) {
            throw WorkflowHermesException(
                stepName = name,
                message = result.describeNonZeroExit(),
            )
        }

        if (resolvedSecurity.defenseActive) {
            when (val validation = validateStepOutput(result.output, resolvedSecurity.validator)) {
                is ValidationResult.Rejected -> throw WorkflowHermesException(
                    stepName = name,
                    message = "output validation rejected: ${validation.reason} (rule: ${validation.ruleId ?: "unknown"})",
                )
                is ValidationResult.Valid -> Unit
            }
        }

        return try {
            merge(state, result.output, context)
        } catch (error: Throwable) {
            error.rethrowAgentCancellation()
            throw wrapHermesError(error)
        }
    }

    private fun wrapHermesError(error: Throwable): WorkflowHermesException = when (error) {
        is WorkflowHermesException -> error
        is AgentCliTimeoutException -> WorkflowHermesException(
            stepName = name,
            message = error.message ?: "timed out after ${config.timeoutSeconds}s",
            cause = error,
        )
        else -> WorkflowHermesException(
            stepName = name,
            message = "failed: ${error.message ?: error::class.java.simpleName}",
            cause = error,
        )
    }
}
