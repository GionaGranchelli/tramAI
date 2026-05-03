package dev.tramai.server

import dev.tramai.orchestration.NoOpWorkflowObserver
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowResumeException
import dev.tramai.orchestration.WorkflowSuspendedException
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
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
import java.net.URI
import java.util.UUID

@RestController
class WorkflowController(
    private val registry: WorkflowRegistry,
    private val runStore: WorkflowRunStore,
) {
    @PostMapping("/workflows/{name}/run")
    fun runWorkflow(
        @PathVariable name: String,
        @RequestBody body: String,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): WorkflowRunResponse {
        val entry = registry.get(name)
        val existing = runStore.findByIdempotencyKey(name, idempotencyKey)
        if (existing != null) {
            return existing.toResponse()
        }
        val workflowId = UUID.randomUUID().toString()
        runStore.create(
            workflowName = name,
            workflowId = workflowId,
            definitionVersion = entry.workflow.definitionVersion,
            idempotencyKey = idempotencyKey,
        )
        return runBlocking {
            executeRun(
                entry = entry,
                workflowId = workflowId,
                body = body,
            )
        }
    }

    @PostMapping("/workflows/{name}/runs/{id}/resume")
    fun resumeWorkflow(
        @PathVariable name: String,
        @PathVariable id: String,
    ): WorkflowRunResponse {
        val entry = registry.get(name)
        val persistence = persistenceOrConflict(entry, id)
        return runBlocking {
            executeResume(
                entry = entry,
                workflowId = id,
                persistence = persistence,
            )
        }
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

    @GetMapping("/openapi.json")
    fun openApi(): Map<String, Any> {
        val paths = registry.list().flatMap { entry ->
            val workflowPath = "/workflows/${entry.workflow.name}"
            listOf(
                "$workflowPath/run" to mapOf("post" to operation("Start ${entry.workflow.name} workflow")),
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
                "version" to "0.1.0",
            ),
            "paths" to paths,
        )
    }

    private suspend fun <S, R> executeRun(
        entry: WorkflowEntry<S, R>,
        workflowId: String,
        body: String,
    ): WorkflowRunResponse {
        val initialState = try {
            entry.decodeState(body)
        } catch (error: Throwable) {
            runStore.fail(entry.workflow.name, workflowId, error)
            throw BadWorkflowRequestException("Workflow '${entry.workflow.name}' state JSON is invalid", error)
        }
        val persistence = entry.persistenceFactory(workflowId)
        val observer = ServerWorkflowObserver(runStore, workflowId)
        return try {
            runStore.event(entry.workflow.name, workflowId, "tramai.workflow.running", status = WorkflowRunStatus.RUNNING)
            val result = entry.run(
                initialState = initialState,
                context = WorkflowContext(workflowId = workflowId),
                observer = observer,
                persistence = persistence,
            )
            runStore.complete(entry.workflow.name, workflowId, result).toResponse()
        } catch (suspended: WorkflowSuspendedException) {
            runStore.fail(entry.workflow.name, workflowId, suspended, WorkflowRunStatus.DELAYED).toResponse()
        }
    }

    private suspend fun <S, R> executeResume(
        entry: WorkflowEntry<S, R>,
        workflowId: String,
        @Suppress("UNCHECKED_CAST")
        persistence: WorkflowPersistence<*>,
    ): WorkflowRunResponse {
        val observer = ServerWorkflowObserver(runStore, workflowId)
        @Suppress("UNCHECKED_CAST")
        val typedPersistence = persistence as WorkflowPersistence<S>
        return try {
            runStore.event(entry.workflow.name, workflowId, "tramai.workflow.running", status = WorkflowRunStatus.RUNNING)
            val result = entry.resume(
                context = WorkflowContext(workflowId = workflowId),
                observer = observer,
                persistence = typedPersistence,
            )
            runStore.complete(entry.workflow.name, workflowId, result).toResponse()
        } catch (suspended: WorkflowSuspendedException) {
            runStore.fail(entry.workflow.name, workflowId, suspended, WorkflowRunStatus.DELAYED).toResponse()
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

@RestControllerAdvice
class WorkflowErrorHandler {
    @ExceptionHandler(BadWorkflowRequestException::class, IllegalArgumentException::class)
    fun badRequest(error: RuntimeException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "Invalid workflow request", error.message ?: "Request is invalid")

    @ExceptionHandler(WorkflowNotRegisteredException::class, WorkflowRunNotFoundException::class, WorkflowResumeException::class)
    fun notFound(error: RuntimeException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.NOT_FOUND, "Workflow resource not found", error.message ?: "Workflow resource was not found")

    @ExceptionHandler(WorkflowConflictException::class)
    fun conflict(error: WorkflowConflictException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.CONFLICT, "Workflow conflict", error.message ?: "Workflow request conflicts with current state")

    @ExceptionHandler(Throwable::class)
    fun unexpected(error: Throwable): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.INTERNAL_SERVER_ERROR, "Workflow execution failed", error.message ?: error::class.java.name)

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
        runStore.event(workflowName, workflowId, "tramai.step.failed", stepName, WorkflowRunStatus.FAILED)
    }

    override fun onWorkflowCompleted(
        workflowName: String,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, "tramai.workflow.completed", status = WorkflowRunStatus.COMPLETED)
    }

    override fun onWorkflowFailed(
        workflowName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, "tramai.workflow.failed", status = WorkflowRunStatus.FAILED)
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
