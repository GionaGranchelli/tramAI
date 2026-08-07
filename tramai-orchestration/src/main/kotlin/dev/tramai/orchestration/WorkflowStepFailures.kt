package dev.tramai.orchestration

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import dev.tramai.core.coroutines.rethrowIfCancellation

enum class WorkflowStepFailureCode(val value: String) {
    PREPARATION_FAILED("workflow.step.preparation_failed"), VALIDATION_FAILED("workflow.step.validation_failed"),
    POLICY_REJECTED("workflow.step.policy_rejected"), START_FAILED("workflow.step.start_failed"),
    TIMEOUT("workflow.step.timeout"), TRANSPORT_FAILED("workflow.step.transport_failed"),
    EXECUTION_FAILED("workflow.step.execution_failed"), NON_ZERO_EXIT("workflow.step.non_zero_exit"),
    OUTPUT_REJECTED("workflow.step.output_rejected"), RESULT_HANDLING_FAILED("workflow.step.result_handling_failed"),
    CLEANUP_FAILED("workflow.step.cleanup_failed"),
}

enum class WorkflowStepKind { HTTP, SHELL, MCP, CODEX, HERMES }

data class BoundedWorkflowDetail(val text: String, val truncated: Boolean)

fun boundedWorkflowDetailPreview(text: String): BoundedWorkflowDetail =
    boundedWorkflowDetailPreview(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

fun boundedWorkflowDetailPreview(input: InputStream, limitBytes: Int = 8 * 1024): BoundedWorkflowDetail {
    require(limitBytes >= 0) { "limitBytes must not be negative" }
    return input.use { stream ->
        val bytes = ByteArray(limitBytes + 1)
        var read = 0
        while (read < bytes.size) {
            val count = stream.read(bytes, read, bytes.size - read)
            if (count < 0) break
            if (count == 0) {
                val single = stream.read()
                if (single < 0) break
                bytes[read++] = single.toByte()
            } else read += count
        }
        val retained = minOf(read, limitBytes)
        var preview = String(bytes, 0, retained, Charsets.UTF_8)
        while (preview.toByteArray(Charsets.UTF_8).size > limitBytes) preview = preview.dropLast(1)
        BoundedWorkflowDetail(preview, read == limitBytes + 1)
    }
}

internal fun safeWorkflowStepFailure(
    kind: WorkflowStepKind, code: WorkflowStepFailureCode, message: String, stepName: String, attempt: Int,
    statusCode: Int? = null, exitCode: Long? = null,
): RuntimeException {
    val failure = when (kind) {
        WorkflowStepKind.HTTP -> WorkflowHttpException(stepName, attempt, message, true)
        WorkflowStepKind.SHELL -> WorkflowShellException(stepName, message, true)
        WorkflowStepKind.MCP -> WorkflowMcpException(stepName, message, true)
        WorkflowStepKind.CODEX -> WorkflowCodexException(stepName, message, true)
        WorkflowStepKind.HERMES -> WorkflowHermesException(stepName, message, true)
    }
    // HTTP's historic constructor derives its message from its cause.  Its safe
    // constructor therefore uses the fixed redacted marker; the actual message is
    // never derived from an external value.
    when (failure) {
        is WorkflowHttpException -> { failure.failureCode = code; failure.safeFactoryTrusted = true }
        is WorkflowShellException -> { failure.failureCode = code; failure.safeFactoryTrusted = true }
        is WorkflowMcpException -> { failure.failureCode = code; failure.safeFactoryTrusted = true }
        is WorkflowCodexException -> { failure.failureCode = code; failure.safeFactoryTrusted = true }
        is WorkflowHermesException -> { failure.failureCode = code; failure.safeFactoryTrusted = true }
    }
    return failure
}

internal fun workflowFailureCode(error: Throwable): WorkflowStepFailureCode? = when (error) {
    is WorkflowHttpException -> error.failureCode
    is WorkflowShellException -> error.failureCode
    is WorkflowMcpException -> error.failureCode
    is WorkflowCodexException -> error.failureCode
    is WorkflowHermesException -> error.failureCode
    else -> null
}

internal fun workflowFailureTrusted(error: Throwable): Boolean = when (error) {
    is WorkflowHttpException -> error.safeFactoryTrusted
    is WorkflowShellException -> error.safeFactoryTrusted
    is WorkflowMcpException -> error.safeFactoryTrusted
    is WorkflowCodexException -> error.safeFactoryTrusted
    is WorkflowHermesException -> error.safeFactoryTrusted
    else -> false
}

internal suspend fun deliverWorkflowStepFailure(
    observer: WorkflowStepFailureDiagnosticObserver,
    event: WorkflowStepFailureDiagnosticEvent,
) {
    try {
        observer.onFailure(event)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
    } catch (e: Throwable) {
        e.rethrowIfCancellation()
    }
}

internal suspend fun sanitizeStepFailure(
    step: InternalWorkflowStep<*>,
    workflowName: String,
    failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver,
    error: Throwable,
): Throwable {
    val kind = when (step) {
        is HttpWorkflowStep<*> -> WorkflowStepKind.HTTP
        is ShellWorkflowStep<*> -> WorkflowStepKind.SHELL
        is McpWorkflowStep<*> -> WorkflowStepKind.MCP
        is CodexWorkflowStep<*> -> WorkflowStepKind.CODEX
        is HermesWorkflowStep<*> -> WorkflowStepKind.HERMES
        else -> null
    }
    if (kind == null || workflowFailureTrusted(error)) return error
    val code = workflowFailureCode(error) ?: WorkflowStepFailureCode.EXECUTION_FAILED
    val preview = boundedWorkflowDetailPreview(error.message ?: error::class.java.name)
    deliverWorkflowStepFailure(failureDiagnosticObserver, WorkflowStepFailureDiagnosticEvent(
        workflowName, step.name, kind, code, 1, false, error, preview.text, preview.truncated,
    ))
    return safeWorkflowStepFailure(kind, code, fixedWorkflowStepMessage(kind, code), step.name, 1)
}

internal fun fixedWorkflowStepMessage(kind: WorkflowStepKind, code: WorkflowStepFailureCode): String = when (code) {
    WorkflowStepFailureCode.PREPARATION_FAILED -> "Workflow ${kind.name.lowercase()} step input preparation failed"
    WorkflowStepFailureCode.VALIDATION_FAILED -> "Workflow ${kind.name.lowercase()} step validation failed"
    WorkflowStepFailureCode.POLICY_REJECTED -> "Workflow ${kind.name.lowercase()} step was rejected by policy"
    WorkflowStepFailureCode.START_FAILED -> "Workflow ${kind.name.lowercase()} step process could not be started"
    WorkflowStepFailureCode.TIMEOUT -> "Workflow ${kind.name.lowercase()} step timed out"
    WorkflowStepFailureCode.TRANSPORT_FAILED -> "Workflow ${kind.name.lowercase()} step transport failed"
    WorkflowStepFailureCode.EXECUTION_FAILED -> "Workflow step execution failed"
    WorkflowStepFailureCode.NON_ZERO_EXIT -> "Workflow ${kind.name.lowercase()} step exited unsuccessfully"
    WorkflowStepFailureCode.OUTPUT_REJECTED -> "Workflow ${kind.name.lowercase()} step output was rejected"
    WorkflowStepFailureCode.RESULT_HANDLING_FAILED -> "Workflow ${kind.name.lowercase()} step result handling failed"
    WorkflowStepFailureCode.CLEANUP_FAILED -> "Workflow ${kind.name.lowercase()} step cleanup failed"
}
