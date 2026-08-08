package dev.tramai.orchestration

import dev.tramai.core.security.StepSecurityConfig
import dev.tramai.core.security.ValidationResult
import dev.tramai.core.coroutines.rethrowIfCancellation
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

class WorkflowCodexException : RuntimeException {
    val stepName: String
    constructor(stepName: String, message: String, cause: Throwable? = null) :
        super("Workflow Codex step '$stepName' $message", cause) { this.stepName = stepName }
    var failureCode: WorkflowStepFailureCode? = null
        internal set
    internal var safeFactoryTrusted: Boolean = false
    internal constructor(stepName: String, safeMessage: String, safe: Boolean) : super(safeMessage) { this.stepName = stepName }
}

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
        failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver = NoOpWorkflowStepFailureDiagnosticObserver,
    ): S {
        val prompt = try {
            promptBuilder(state, context)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw failure(workflowName, error, WorkflowStepFailureCode.PREPARATION_FAILED, failureDiagnosticObserver)
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
            error.rethrowIfCancellation()
            throw failure(workflowName, error, codeForCliFailure(error), failureDiagnosticObserver)
        }

        if (result.exitCode != 0) {
            throw failure(workflowName, IllegalStateException("non-zero exit"), WorkflowStepFailureCode.NON_ZERO_EXIT, failureDiagnosticObserver, result)
        }

        handleOutputValidation(workflowName, result, resolvedSecurity, observer, context, failureDiagnosticObserver)

        return try {
            merge(state, result.output, context)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
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
            throw failure(workflowName, error, WorkflowStepFailureCode.RESULT_HANDLING_FAILED, failureDiagnosticObserver)
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

    private suspend fun handleOutputValidation(
        workflowName: String,
        result: AgentCliExecution,
        resolvedSecurity: ResolvedStepSecurity,
        observer: WorkflowObserver,
        context: WorkflowContext,
        failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver,
    ) {
        if (!resolvedSecurity.defenseActive) return
        when (val validation = validateStepOutput(result.output, resolvedSecurity.validator)) {
            is ValidationResult.Rejected -> {
                observer.onWorkflowEvent(
                    workflowName = workflowName,
                    name = SecurityEvents.OUTPUT_REJECTED,
                    attributes = mapOf(
                        "step_name" to name,
                        "rule_id" to validation.ruleId.takeIf {
                            resolvedSecurity.validator is DefaultOutputValidator && isBuiltInValidationRule(it)
                        },
                    ),
                    context = context,
                )
                throw failure(
                    workflowName, OutputValidationRejectedException(validation.reason, validation.ruleId),
                    WorkflowStepFailureCode.OUTPUT_REJECTED, failureDiagnosticObserver, result,
                )
            }
            is ValidationResult.Valid -> Unit
        }
    }

    private suspend fun failure(
        workflowName: String, error: Throwable, code: WorkflowStepFailureCode,
        failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver, result: AgentCliExecution? = null,
    ): RuntimeException {
        val detail = if (error is OutputValidationRejectedException) {
            boundedWorkflowDetailPreview(error.message ?: error::class.java.name)
        } else {
            boundedWorkflowDetailPreview(error::class.java.name)
        }
        deliverWorkflowStepFailure(failureDiagnosticObserver, WorkflowStepFailureDiagnosticEvent(
            workflowName, name, WorkflowStepKind.CODEX, code, 1, false, error, detail.text, detail.truncated,
            result?.let { mapOf("exitCode" to it.exitCode.toLong()) } ?: emptyMap(),
        ))
        return safeWorkflowStepFailure(WorkflowStepKind.CODEX, code, fixedWorkflowStepMessage(WorkflowStepKind.CODEX, code), name, 1)
    }

    private fun codeForCliFailure(error: Throwable): WorkflowStepFailureCode = when (error) {
        is AgentCliTimeoutException -> WorkflowStepFailureCode.TIMEOUT
        is ProcessCleanupException -> WorkflowStepFailureCode.CLEANUP_FAILED
        is AgentCliStartException -> WorkflowStepFailureCode.START_FAILED
        else -> WorkflowStepFailureCode.EXECUTION_FAILED
    }
}

internal class OutputValidationRejectedException(reason: String, ruleId: String?) :
    RuntimeException("output validation rejected: $reason (rule: ${ruleId ?: "unknown"})")

internal fun isBuiltInValidationRule(ruleId: String?): Boolean = ruleId in setOf(
    DefaultOutputValidator.RULE_EXTRACTION_SYSTEM_PROMPT,
    DefaultOutputValidator.RULE_REPEAT_ABOVE,
    DefaultOutputValidator.RULE_BOUNDARY_PROBING,
)
