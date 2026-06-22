package dev.tramai.orchestration

import dev.tramai.core.security.StepSecurityConfig
import dev.tramai.core.security.ValidationResult
import java.io.File

data class CodexStepConfig(
    val timeoutSeconds: Long = 180,
    val maxOutputBytes: Long = 1_048_576,
    val cliPath: String = "codex",
    val workdir: String? = null,
    val security: StepSecurityConfig = StepSecurityConfig.Default,
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
        val resolvedSecurity = resolveStepSecurity(prompt, config.security)
        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = SecurityEvents.STEP_EXECUTED,
            attributes = mapOf(
                "step_name" to name,
                "step_type" to "codex",
                "sanitizer_active" to resolvedSecurity.defenseActive,
                "validator_active" to (resolvedSecurity.validator != null),
                "instruction_defense_active" to resolvedSecurity.defenseActive,
                "defense_mode" to config.security.defenseMode(),
            ),
            context = context,
        )
        emitSanitizerEventIfNeeded(workflowName, prompt, resolvedSecurity, observer, context)

        val result = try {
            executeAgentCli(
                AgentCliRequest(
                    workflowName = workflowName,
                    stepName = name,
                    eventPrefix = "tramai.workflow.codex",
                    agentType = "codex",
                    processBuilder = ProcessBuilder(
                        listOf(config.cliPath, "exec", "--", resolvedSecurity.defendedPrompt),
                    ).apply {
                        config.workdir?.let { directory(File(it)) }
                    },
                    timeoutSeconds = config.timeoutSeconds,
                    maxOutputBytes = config.maxOutputBytes,
                    promptLength = resolvedSecurity.defendedPrompt.length,
                    context = context,
                    observer = observer,
                ),
            )
        } catch (error: Throwable) {
            error.rethrowAgentCancellation()
            throw wrapCodexError(error)
        }

        if (result.exitCode != 0) {
            throw WorkflowCodexException(
                stepName = name,
                message = result.describeNonZeroExit(),
            )
        }

        handleOutputValidation(workflowName, result, resolvedSecurity, observer, context)

        return try {
            merge(state, result.output, context)
        } catch (error: Throwable) {
            error.rethrowAgentCancellation()
            if (resolvedSecurity.defenseActive) {
                observer.onWorkflowEvent(
                    workflowName = workflowName,
                    name = SecurityEvents.OUTPUT_REJECTED,
                    attributes = mapOf(
                        "step_name" to name,
                        "reason" to "parse_failure",
                    ),
                    context = context,
                )
            }
            throw wrapCodexError(error)
        }
    }

    private fun emitSanitizerEventIfNeeded(
        workflowName: String,
        prompt: String,
        resolvedSecurity: dev.tramai.orchestration.ResolvedStepSecurity,
        observer: WorkflowObserver,
        context: WorkflowContext,
    ) {
        if (!resolvedSecurity.defenseActive) return
        val sanitizedPrompt = resolvedSecurity.sanitizedPrompt
        if (prompt.length != sanitizedPrompt.length || prompt != sanitizedPrompt) {
            val customConfig = config.security as? StepSecurityConfig.Custom
            val hasCustomSanitizer = customConfig?.sanitizer != null
            val sanitizerRuleId = if (hasCustomSanitizer) {
                null
            } else {
                DefaultPromptSanitizer.getTriggeredRules(prompt).joinToString(",")
            }
            observer.onWorkflowEvent(
                workflowName = workflowName,
                name = SecurityEvents.SANITIZER_TRIGGERED,
                attributes = mapOf(
                    "step_name" to name,
                    "original_size_bytes" to prompt.length,
                    "modified_size_bytes" to sanitizedPrompt.length,
                    "rule_id" to sanitizerRuleId,
                ),
                context = context,
            )
        }
    }

    private fun handleOutputValidation(
        workflowName: String,
        result: AgentCliExecution,
        resolvedSecurity: ResolvedStepSecurity,
        observer: WorkflowObserver,
        context: WorkflowContext,
    ) {
        if (!resolvedSecurity.defenseActive) return
        when (val validation = validateStepOutput(result.output, resolvedSecurity.validator)) {
            is ValidationResult.Rejected -> {
                observer.onWorkflowEvent(
                    workflowName = workflowName,
                    name = SecurityEvents.OUTPUT_REJECTED,
                    attributes = mapOf(
                        "step_name" to name,
                        "reason" to validation.reason,
                        "rule_id" to validation.ruleId,
                    ),
                    context = context,
                )
                throw WorkflowCodexException(
                    stepName = name,
                    message = "output validation rejected: ${validation.reason} (rule: ${validation.ruleId ?: "unknown"})",
                )
            }
            is ValidationResult.Valid -> Unit
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
