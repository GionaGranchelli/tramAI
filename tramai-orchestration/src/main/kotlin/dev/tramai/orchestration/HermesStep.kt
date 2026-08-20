package dev.tramai.orchestration

import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.security.StepSecurityConfig
import dev.tramai.core.security.ValidationResult
import dev.tramai.core.coroutines.rethrowIfCancellation

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

class WorkflowHermesException : RuntimeException {
    val stepName: String
    constructor(stepName: String, message: String, cause: Throwable? = null) :
        super("Workflow Hermes step '$stepName' $message", cause) { this.stepName = stepName }
    var failureCode: WorkflowStepFailureCode? = null
        internal set
    internal var safeFactoryTrusted: Boolean = false
    internal constructor(stepName: String, safeMessage: String, safe: Boolean) : super(safeMessage) { this.stepName = stepName }
}

internal data class HermesWorkflowStep<S>(
    override val name: String,
    val promptBuilder: suspend (S, WorkflowContext) -> String,
    val merge: suspend (S, String, WorkflowContext) -> S,
    val config: HermesStepConfig = HermesStepConfig(),
) : InternalWorkflowStep<S> {
    override suspend fun execute(
        request: WorkflowStepExecutionRequest<S>,
    ): WorkflowStepExecutionResult<S> = WorkflowStepExecutionResult.Completed(
        execute(
            workflowName = request.workflowName,
            state = request.state,
            context = request.context,
            observer = request.observer,
            failureDiagnosticObserver = request.services.failureDiagnosticObserver,
        ),
    )

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
        observer.emitWorkflowEvent(
            workflowName = workflowName,
            event = RuntimeEvent.of(RuntimeEvents.WORKFLOW_SECURITY_STEP_EXECUTED) {
                set(RuntimeAttributes.STEP_NAME, name)
                set(RuntimeAttributes.STEP_TYPE, "hermes")
                set(RuntimeAttributes.SANITIZER_ACTIVE, resolvedSecurity.defenseActive)
                set(RuntimeAttributes.VALIDATOR_ACTIVE, resolvedSecurity.validator != null)
                set(RuntimeAttributes.INSTRUCTION_DEFENSE_ACTIVE, resolvedSecurity.defenseActive)
                set(RuntimeAttributes.DEFENSE_MODE, config.security.defenseMode())
            },
            context = context,
        )
        emitSanitizerEventIfNeeded(workflowName, prompt, resolvedSecurity, observer, context)

        val result = try {
            executeAgentCli(
                AgentCliRequest(
                    workflowName = workflowName,
                    stepName = name,
                    startedEvent = RuntimeEvents.WORKFLOW_HERMES_STARTED,
                    completedEvent = RuntimeEvents.WORKFLOW_HERMES_COMPLETED,
                    agentType = "hermes",
                    processBuilder = ProcessBuilder(
                        listOf(config.cliPath, "chat", "-q", resolvedSecurity.defendedPrompt, "--model", config.model),
                    ),
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
                observer.emitWorkflowEvent(
                    workflowName = workflowName,
                    event = RuntimeEvent.of(RuntimeEvents.WORKFLOW_SECURITY_OUTPUT_REJECTED) {
                        set(RuntimeAttributes.STEP_NAME, name)
                        set(RuntimeAttributes.REASON, "parse_failure")
                    },
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
            observer.emitWorkflowEvent(
                workflowName = workflowName,
                event = RuntimeEvent.of(RuntimeEvents.WORKFLOW_SECURITY_SANITIZER_TRIGGERED) {
                    set(RuntimeAttributes.STEP_NAME, name)
                    set(RuntimeAttributes.ORIGINAL_SIZE_BYTES, prompt.length.toLong())
                    set(RuntimeAttributes.MODIFIED_SIZE_BYTES, sanitizedPrompt.length.toLong())
                    sanitizerRuleId?.let { set(RuntimeAttributes.RULE_ID, it) }
                },
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
                observer.emitWorkflowEvent(
                    workflowName = workflowName,
                    event = RuntimeEvent.of(RuntimeEvents.WORKFLOW_SECURITY_OUTPUT_REJECTED) {
                        set(RuntimeAttributes.STEP_NAME, name)
                        validation.ruleId.takeIf {
                            resolvedSecurity.validator is DefaultOutputValidator && isBuiltInValidationRule(it)
                        }?.let { set(RuntimeAttributes.RULE_ID, it) }
                    },
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
            workflowName, name, WorkflowStepKind.HERMES, code, 1, false, error, detail.text, detail.truncated,
            result?.let { mapOf("exitCode" to it.exitCode.toLong()) } ?: emptyMap(),
        ))
        return safeWorkflowStepFailure(WorkflowStepKind.HERMES, code, fixedWorkflowStepMessage(WorkflowStepKind.HERMES, code), name, 1)
    }

    private fun codeForCliFailure(error: Throwable): WorkflowStepFailureCode = when (error) {
        is AgentCliTimeoutException -> WorkflowStepFailureCode.TIMEOUT
        is ProcessCleanupException -> WorkflowStepFailureCode.CLEANUP_FAILED
        is AgentCliStartException -> WorkflowStepFailureCode.START_FAILED
        else -> WorkflowStepFailureCode.EXECUTION_FAILED
    }
}
