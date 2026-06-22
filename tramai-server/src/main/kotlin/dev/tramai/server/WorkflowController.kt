package dev.tramai.server

import dev.tramai.orchestration.NoOpWorkflowObserver
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowResumeException
import dev.tramai.orchestration.WorkflowSuspendedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

@RestController
class WorkflowController(
    private val registry: WorkflowRegistry,
    private val runStore: WorkflowRunStore,
    private val workflowExecutionScope: CoroutineScope,
    private val signatureVerifier: WebhookSignatureVerifier,
) {
    private val logger = LoggerFactory.getLogger(WorkflowController::class.java)

    @GetMapping("/workflows")
    fun listWorkflows(): List<Map<String, String>> =
        registry.list().map { mapOf("name" to it.workflow.name) }

    @PostMapping("/workflows/{name}/run")
    fun runWorkflow(
        @PathVariable name: String,
        @RequestBody body: String,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): WorkflowRunResponse {
        val entry = registry.get(name)
        val initialState = decodeInitialState(entry, body)
        return startWorkflow(entry, initialState, idempotencyKey)
    }

    @PostMapping("/webhooks/{name}")
    fun receiveWebhook(
        @PathVariable name: String,
        @RequestBody body: String,
        @RequestHeader("X-Hub-Signature-256", required = false) signature: String?,
        @RequestHeader("X-GitHub-Delivery", required = false) deliveryId: String?,
    ): ResponseEntity<WorkflowRunResponse> {
        val entry = registry.get(name)
        val payloadSize = body.toByteArray(UTF_8).size
        logger.info(
            "Received webhook for workflow '{}' source={} size={} bytes",
            entry.workflow.name,
            signatureVerifier.name,
            payloadSize,
        )
        val headers = mapOf("X-Hub-Signature-256" to (signature ?: ""))
        if (!signatureVerifier.verify(body, headers)) {
            throw InvalidWebhookSignatureException("Webhook signature is invalid")
        }
        val initialState = decodeInitialState(entry, body)
        val running = startWorkflow(entry, initialState, idempotencyKey = deliveryId)
        logger.info(
            "Started workflow '{}' from webhook source={} workflowId={} size={} bytes",
            entry.workflow.name,
            signatureVerifier.name,
            running.workflowId,
            payloadSize,
        )
        return ResponseEntity.accepted().body(running)
    }

    @PostMapping("/workflows/{name}/runs/{id}/resume")
    fun resumeWorkflow(
        @PathVariable name: String,
        @PathVariable id: String,
    ): WorkflowRunResponse {
        val entry = registry.get(name)
        val persistence = persistenceOrConflict(entry, id)
        val running = runStore.markResuming(entry.workflow.name, id).toResponse()
        val job = workflowExecutionScope.launch(start = CoroutineStart.LAZY) {
            executeResumeSafely(
                entry = entry,
                workflowId = id,
                persistence = persistence,
            )
        }
        runStore.attachExecution(entry.workflow.name, id, job)
        job.start()
        return running
    }

    @GetMapping("/workflows/{name}/runs")
    fun listRuns(
        @PathVariable name: String,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int,
    ): WorkflowRunPage {
        registry.get(name)
        require(offset >= 0) { "offset must be greater than or equal to zero" }
        require(limit in 1..200) { "limit must be between 1 and 200" }
        val runs = runStore.list(name, offset, limit).map(WorkflowRunRecord::toSummary)
        return WorkflowRunPage(
            workflowName = name,
            offset = offset,
            limit = limit,
            runs = runs,
        )
    }

    @GetMapping("/workflows/{name}/runs/{id}")
    fun getRun(
        @PathVariable name: String,
        @PathVariable id: String,
    ): WorkflowRunDetail {
        registry.get(name)
        return runStore.get(name, id).toDetail()
    }

    @DeleteMapping("/workflows/{name}/runs/{id}")
    fun cancelRun(
        @PathVariable name: String,
        @PathVariable id: String,
    ): ResponseEntity<WorkflowRunResponse> {
        registry.get(name)
        val cancelled = runStore.cancel(name, id)
        return ResponseEntity
            .accepted()
            .body(cancelled.toResponse())
    }

    @GetMapping("/workflows/{name}/runs/{id}/events")
    fun getRunEvents(
        @PathVariable name: String,
        @PathVariable id: String,
        @RequestHeader("Last-Event-ID", required = false) since: String?,
    ): SseEmitter {
        registry.get(name)
        val emitter = SseEmitter(-1L)
        runStore.registerSseEmitter(
            workflowName = name,
            workflowId = id,
            emitter = emitter,
            since = since?.toLongOrNull(),
        )
        return emitter
    }

    @GetMapping("/openapi.json")
    fun openApi(): Map<String, Any> {
        val paths = registry.list().flatMap { entry ->
            val workflowPath = "/workflows/${entry.workflow.name}"
            listOf(
                "$workflowPath/run" to mapOf("post" to operation("Start ${entry.workflow.name} workflow")),
                "/webhooks/${entry.workflow.name}" to mapOf("post" to operation("Trigger ${entry.workflow.name} webhook")),
                "$workflowPath/runs" to mapOf("get" to operation("List ${entry.workflow.name} runs")),
                "$workflowPath/runs/{id}" to mapOf(
                    "get" to operation("Inspect ${entry.workflow.name} run"),
                    "delete" to operation("Cancel ${entry.workflow.name} run"),
                ),
                "$workflowPath/runs/{id}/resume" to mapOf("post" to operation("Resume ${entry.workflow.name} run")),
            )
        }.toMap()
        return mapOf(
            "openapi" to "3.1.0",
            "info" to mapOf(
                "title" to "Tramai Workflow Server",
                "version" to VERSION,
            ),
            "paths" to paths,
        )
    }

    private fun startWorkflow(
        entry: WorkflowEntry<*, *>,
        initialState: Any?,
        idempotencyKey: String?,
    ): WorkflowRunResponse {
        val workflowId = UUID.randomUUID().toString()
        val creation = runStore.getOrCreate(
            workflowName = entry.workflow.name,
            workflowId = workflowId,
            definitionVersion = entry.workflow.definitionVersion,
            idempotencyKey = idempotencyKey,
        )
        if (!creation.created) {
            return creation.record.toResponse()
        }
        runStore.event(entry.workflow.name, workflowId, "tramai.workflow.running", status = WorkflowRunStatus.RUNNING)
        val running = runStore.get(entry.workflow.name, workflowId).toResponse()
        val job = workflowExecutionScope.launch(start = CoroutineStart.LAZY) {
            executeRunSafely(
                entry = entry,
                workflowId = workflowId,
                initialState = initialState,
            )
        }
        runStore.attachExecution(entry.workflow.name, workflowId, job)
        job.start()
        return running
    }

    private fun decodeInitialState(
        entry: WorkflowEntry<*, *>,
        body: String,
    ): Any? = try {
        entry.decodeState(body)
    } catch (error: Throwable) {
        throw BadWorkflowRequestException("Workflow '${entry.workflow.name}' state JSON is invalid", error)
    }

    private suspend fun executeRunSafely(
        entry: WorkflowEntry<*, *>,
        workflowId: String,
        initialState: Any?,
    ) {
        @Suppress("UNCHECKED_CAST")
        val typedEntry = entry as WorkflowEntry<Any?, Any?>
        val persistence = entry.persistenceFactory(workflowId)
        val observer = ServerWorkflowObserver(runStore, workflowId)
        try {
            val result = typedEntry.run(
                initialState = initialState,
                context = WorkflowContext(workflowId = workflowId),
                observer = observer,
                persistence = persistence,
            )
            runStore.complete(entry.workflow.name, workflowId, result)
        } catch (suspended: WorkflowSuspendedException) {
            runStore.fail(entry.workflow.name, workflowId, suspended, WorkflowRunStatus.DELAYED)
        } catch (_: CancellationException) {
            if (runStore.get(entry.workflow.name, workflowId).status != WorkflowRunStatus.CANCELLED) {
                throw CancellationException("Workflow '${entry.workflow.name}' run '$workflowId' was cancelled")
            }
        } catch (error: Throwable) {
            runStore.fail(entry.workflow.name, workflowId, error)
        }
    }

    private suspend fun executeResumeSafely(
        entry: WorkflowEntry<*, *>,
        workflowId: String,
        @Suppress("UNCHECKED_CAST")
        persistence: WorkflowPersistence<*>,
    ) {
        val observer = ServerWorkflowObserver(runStore, workflowId)
        @Suppress("UNCHECKED_CAST")
        val typedEntry = entry as WorkflowEntry<Any?, Any?>
        @Suppress("UNCHECKED_CAST")
        val typedPersistence = persistence as WorkflowPersistence<Any?>
        try {
            val result = typedEntry.resume(
                context = WorkflowContext(workflowId = workflowId),
                observer = observer,
                persistence = typedPersistence,
            )
            runStore.complete(entry.workflow.name, workflowId, result)
        } catch (suspended: WorkflowSuspendedException) {
            runStore.fail(entry.workflow.name, workflowId, suspended, WorkflowRunStatus.DELAYED)
        } catch (_: CancellationException) {
            if (runStore.get(entry.workflow.name, workflowId).status != WorkflowRunStatus.CANCELLED) {
                throw CancellationException("Workflow '${entry.workflow.name}' run '$workflowId' was cancelled")
            }
        } catch (error: Throwable) {
            runStore.fail(entry.workflow.name, workflowId, error)
        }
    }

    private fun <S, R> persistenceOrConflict(
        entry: WorkflowEntry<S, R>,
        workflowId: String,
    ): WorkflowPersistence<S> = entry.persistenceFactory(workflowId)
        ?: throw WorkflowConflictException(
            "Workflow '${entry.workflow.name}' has no persistence configured; resume requires WorkflowPersistence",
        )

    private fun operation(summary: String): Map<String, Any> = mapOf(
        "summary" to summary,
        "responses" to mapOf(
            "200" to mapOf("description" to "OK"),
            "400" to mapOf("description" to "Problem Details"),
        ),
    )
}

data class WorkflowRunResponse(
    val workflowId: String,
    val status: String,
    val definitionVersion: String,
    val result: Any? = null,
)

data class WorkflowRunPage(
    val workflowName: String,
    val offset: Int,
    val limit: Int,
    val runs: List<WorkflowRunSummary>,
)

data class WorkflowRunSummary(
    val workflowId: String,
    val status: String,
    val definitionVersion: String,
    val currentStep: String?,
)

data class WorkflowRunDetail(
    val workflowId: String,
    val status: String,
    val definitionVersion: String,
    val currentStep: String?,
    val history: List<WorkflowRunEvent>,
    val result: Any?,
    val error: String?,
)

class BadWorkflowRequestException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class WorkflowConflictException(
    message: String,
) : RuntimeException(message)

class InvalidWebhookSignatureException(
    message: String,
) : RuntimeException(message)

@RestControllerAdvice
class WorkflowErrorHandler {
    private val logger = LoggerFactory.getLogger(WorkflowErrorHandler::class.java)

    @ExceptionHandler(BadWorkflowRequestException::class, IllegalArgumentException::class)
    fun badRequest(error: RuntimeException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, ERROR_INVALID_REQUEST, error.message ?: "Request is invalid")

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun validation(error: HandlerMethodValidationException): ResponseEntity<ProblemDetail> =
        problem(
            HttpStatus.BAD_REQUEST,
            ERROR_INVALID_REQUEST,
            error.allErrors
                .mapNotNull { it.defaultMessage?.takeIf(String::isNotBlank) }
                .ifEmpty { listOf(error.message ?: "Request is invalid") }
                .joinToString("; "),
        )

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun argumentTypeMismatch(error: MethodArgumentTypeMismatchException): ResponseEntity<ProblemDetail> =
        problem(
            HttpStatus.BAD_REQUEST,
            ERROR_INVALID_REQUEST,
            "Parameter '${error.name}' has an invalid value",
        )

    @ExceptionHandler(WorkflowNotRegisteredException::class, WorkflowRunNotFoundException::class, WorkflowResumeException::class)
    fun notFound(error: RuntimeException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.NOT_FOUND, "Workflow resource not found", error.message ?: "Workflow resource was not found")

    @ExceptionHandler(WorkflowConflictException::class)
    fun conflict(error: WorkflowConflictException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.CONFLICT, "Workflow conflict", error.message ?: "Workflow request conflicts with current state")

    @ExceptionHandler(InvalidWebhookSignatureException::class)
    fun unauthorized(error: InvalidWebhookSignatureException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.UNAUTHORIZED, "Invalid webhook signature", error.message ?: "Webhook signature is invalid")

    @ExceptionHandler(RequestBodyTooLargeException::class)
    fun payloadTooLarge(error: RequestBodyTooLargeException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.PAYLOAD_TOO_LARGE, "Request body too large", error.message ?: "Request body is too large")

    @ExceptionHandler(Throwable::class)
    fun unexpected(error: Throwable): ResponseEntity<ProblemDetail> {
        logger.error("Unexpected workflow server error", error)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Workflow execution failed",
            "Workflow execution failed unexpectedly",
        )
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.type = URI.create("https://tramai.dev/problems/${status.value()}")
        problem.title = title
        return ResponseEntity.status(status).body(problem)
    }
}

private class ServerWorkflowObserver(
    private val runStore: WorkflowRunStore,
    private val workflowId: String,
) : WorkflowObserver by NoOpWorkflowObserver {
    override fun onWorkflowStarted(
        workflowName: String,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, "tramai.workflow.started", status = WorkflowRunStatus.RUNNING)
    }

    override fun onWorkflowEvent(
        workflowName: String,
        name: String,
        attributes: Map<String, Any?>,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, name)
    }

    override fun onStepStarted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, "tramai.step.started", stepName, WorkflowRunStatus.RUNNING)
    }

    override fun onStepCompleted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, "tramai.step.completed", stepName, WorkflowRunStatus.RUNNING)
    }

    override fun onStepFailed(
        workflowName: String,
        stepName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, "tramai.step.failed", stepName)
    }

    override fun onWorkflowCompleted(
        workflowName: String,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, "tramai.workflow.completed")
    }

    override fun onWorkflowFailed(
        workflowName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, "tramai.workflow.failed")
    }
}

private fun WorkflowRunRecord.toResponse(): WorkflowRunResponse = WorkflowRunResponse(
    workflowId = workflowId,
    status = status.wireName,
    definitionVersion = definitionVersion,
    result = result,
)

private fun WorkflowRunRecord.toSummary(): WorkflowRunSummary = WorkflowRunSummary(
    workflowId = workflowId,
    status = status.wireName,
    definitionVersion = definitionVersion,
    currentStep = currentStep,
)

private fun WorkflowRunRecord.toDetail(): WorkflowRunDetail = WorkflowRunDetail(
    workflowId = workflowId,
    status = status.wireName,
    definitionVersion = definitionVersion,
    currentStep = currentStep,
    history = history,
    result = result,
    error = error,
)

/** @see WorkflowController */
private const val VERSION = "0.2.0"

/** @see WorkflowController */
private const val ERROR_INVALID_REQUEST = "Invalid workflow request"
