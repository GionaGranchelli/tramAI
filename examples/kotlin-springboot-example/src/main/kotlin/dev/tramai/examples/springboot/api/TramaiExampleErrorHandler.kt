package dev.tramai.examples.springboot.api

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.StructuredOutputException
import dev.tramai.orchestration.WorkflowCheckpointConflictException
import dev.tramai.orchestration.WorkflowLeaseConflictException
import dev.tramai.orchestration.WorkflowResumeException
import dev.tramai.examples.springboot.workflow.WorkflowAlreadyRunningException
import dev.tramai.examples.springboot.workflow.WorkflowNotFoundException
import dev.tramai.examples.springboot.workflow.WorkflowNotRunningException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Small JSON-oriented error mapper for the example API.
 */
@RestControllerAdvice
class TramaiExampleErrorHandler {
    private val logger = LoggerFactory.getLogger(TramaiExampleErrorHandler::class.java)

    @ExceptionHandler(StructuredOutputException::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun handleStructuredOutputFailure(error: StructuredOutputException): Map<String, Any?> {
        logger.warn(
            "Tramai structured output failed: validationError='{}', attempts={}, rawResponse='{}'",
            error.validationError,
            error.attemptCount,
            error.lastRawResponse?.replace("\n", "\\n")?.take(800),
        )

        return linkedMapOf(
            "error" to "structured_output_failed",
            "message" to (error.message ?: "Tramai could not produce valid structured output"),
            "validationError" to error.validationError,
            "attemptCount" to error.attemptCount,
            "lastRawResponse" to error.lastRawResponse,
        )
    }

    @ExceptionHandler(ProviderException::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun handleProviderFailure(error: ProviderException): Map<String, Any?> {
        logger.warn(
            "Tramai provider failed: statusCode={}, retryable={}, message='{}'",
            error.statusCode,
            error.retryable,
            error.message,
        )

        return linkedMapOf(
            "error" to "provider_failed",
            "message" to (error.message ?: "Tramai provider call failed"),
            "statusCode" to error.statusCode,
            "retryable" to error.retryable,
        )
    }

    @ExceptionHandler(WorkflowResumeException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleWorkflowResumeFailure(error: WorkflowResumeException): Map<String, Any?> = linkedMapOf(
        "error" to "workflow_resume_failed",
        "message" to (error.message ?: "Workflow checkpoint was not found"),
    )

    @ExceptionHandler(
        WorkflowCheckpointConflictException::class,
        WorkflowLeaseConflictException::class,
    )
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleWorkflowConflict(error: RuntimeException): Map<String, Any?> = linkedMapOf(
        "error" to "workflow_conflict",
        "message" to (error.message ?: "Workflow persistence conflict"),
    )

    @ExceptionHandler(WorkflowAlreadyRunningException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleWorkflowAlreadyRunning(error: WorkflowAlreadyRunningException): Map<String, Any?> = linkedMapOf(
        "error" to "workflow_already_running",
        "message" to (error.message ?: "Workflow is already running"),
    )

    @ExceptionHandler(WorkflowNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleWorkflowNotFound(error: WorkflowNotFoundException): Map<String, Any?> = linkedMapOf(
        "error" to "workflow_not_found",
        "message" to (error.message ?: "Workflow was not found"),
    )

    @ExceptionHandler(WorkflowNotRunningException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleWorkflowNotRunning(error: WorkflowNotRunningException): Map<String, Any?> = linkedMapOf(
        "error" to "workflow_not_running",
        "message" to (error.message ?: "Workflow is not running"),
    )
}
