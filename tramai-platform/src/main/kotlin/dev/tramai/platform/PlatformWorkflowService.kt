package dev.tramai.platform

import dev.tramai.core.observation.event.RuntimeEvents
import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.orchestration.NoOpWorkflowObserver
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowSuspendedException
import dev.tramai.server.WorkflowConflictException
import dev.tramai.server.WorkflowEntry
import dev.tramai.server.WorkflowRegistry
import dev.tramai.server.WorkflowRunDetail
import dev.tramai.server.WorkflowRunPage
import dev.tramai.server.WorkflowRunRecord
import dev.tramai.server.WorkflowRunResponse
import dev.tramai.server.WorkflowRunStatus
import dev.tramai.server.WorkflowRunStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlatformBadRequestException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class TeamProjectRegistry(
    private val teamRepository: TeamRepository,
    private val projectRepository: ProjectRepository,
) {
    fun requireProject(
        teamId: String,
        projectId: String,
    ) {
        require(teamRepository.exists(teamId)) { "Unknown team '$teamId'" }
        require(projectRepository.exists(teamId, projectId)) {
            "Unknown project '$projectId' for team '$teamId'"
        }
    }
}

class TenantWorkflowRunStores {
    private data class Key(
        val teamId: String,
        val projectId: String,
    )

    private val stores = ConcurrentHashMap<Key, WorkflowRunStore>()

    fun get(
        teamId: String,
        projectId: String,
    ): WorkflowRunStore = stores.computeIfAbsent(Key(teamId, projectId)) {
        WorkflowRunStore()
    }
}

class PlatformWorkflowService(
    private val registry: WorkflowRegistry,
    private val runStores: TenantWorkflowRunStores,
    private val auditLogService: AuditLogService,
    private val workflowExecutionScope: CoroutineScope,
    private val objectMapper: ObjectMapper,
    private val teamProjectRegistry: TeamProjectRegistry,
    private val webhookAdapterRegistry: WebhookAdapterRegistry,
) {
    fun runWorkflow(
        teamId: String,
        projectId: String,
        actorId: String,
        workflowName: String,
        body: String,
        idempotencyKey: String?,
    ): WorkflowRunResponse {
        teamProjectRegistry.requireProject(teamId, projectId)
        val entry = registry.get(workflowName)
        val initialState = decodeInitialState(entry, body)
        val response = startWorkflow(teamId, projectId, actorId, entry, initialState, idempotencyKey)
        auditLogService.record(
            actorId = actorId,
            action = "workflow.start",
            resourceType = "workflow_run",
            resourceId = response.workflowId,
            teamId = teamId,
            metadata = mapOf(
                "project_id" to projectId,
                "workflow_name" to workflowName,
            ),
        )
        return response
    }

    fun runWebhook(
        teamId: String,
        projectId: String,
        workflowName: String,
        sourceId: String,
        payload: String,
        headers: Map<String, String>,
        idempotencyKey: String?,
    ): WorkflowRunResponse {
        val adapted = runBlocking { webhookAdapterRegistry.create(sourceId).adapt(payload, headers) }
        return runWorkflow(
            teamId = teamId,
            projectId = projectId,
            actorId = "webhook:$sourceId",
            workflowName = workflowName,
            body = objectMapper.writeValueAsString(adapted),
            idempotencyKey = idempotencyKey,
        )
    }

    fun listRuns(
        teamId: String,
        projectId: String,
        workflowName: String,
        offset: Int,
        limit: Int,
    ): WorkflowRunPage {
        teamProjectRegistry.requireProject(teamId, projectId)
        require(offset >= 0) { "offset must be greater than or equal to zero" }
        require(limit in 1..200) { "limit must be between 1 and 200" }
        registry.get(workflowName)
        val runs = runStores.get(teamId, projectId)
            .list(workflowName, offset, limit)
            .map(WorkflowRunRecord::toSummary)
        return WorkflowRunPage(
            workflowName = workflowName,
            offset = offset,
            limit = limit,
            runs = runs,
        )
    }

    fun getRun(
        teamId: String,
        projectId: String,
        workflowName: String,
        workflowId: String,
    ): WorkflowRunDetail {
        teamProjectRegistry.requireProject(teamId, projectId)
        registry.get(workflowName)
        return runStores.get(teamId, projectId).get(workflowName, workflowId).toDetail()
    }

    private fun startWorkflow(
        teamId: String,
        projectId: String,
        actorId: String,
        entry: WorkflowEntry<*, *>,
        initialState: Any?,
        idempotencyKey: String?,
    ): WorkflowRunResponse {
        val runStore = runStores.get(teamId, projectId)
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
        runStore.event(entry.workflow.name, workflowId, RuntimeEvents.WORKFLOW_RUNNING.name, status = WorkflowRunStatus.RUNNING)
        val running = runStore.get(entry.workflow.name, workflowId).toResponse()
        val job = workflowExecutionScope.launch(start = CoroutineStart.LAZY) {
            executeRunSafely(
                teamId = teamId,
                projectId = projectId,
                actorId = actorId,
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
        throw PlatformBadRequestException("Workflow '${entry.workflow.name}' state JSON is invalid", error)
    }

    private suspend fun executeRunSafely(
        teamId: String,
        projectId: String,
        actorId: String,
        entry: WorkflowEntry<*, *>,
        workflowId: String,
        initialState: Any?,
    ) {
        @Suppress("UNCHECKED_CAST")
        val typedEntry = entry as WorkflowEntry<Any?, Any?>
        val persistence = entry.persistenceFactory(workflowId)
        val runStore = runStores.get(teamId, projectId)
        val observer = PlatformWorkflowObserver(runStore, workflowId)
        try {
            val result = typedEntry.run(
                initialState = initialState,
                context = WorkflowContext(
                    workflowId = workflowId,
                    attributes = mapOf(
                        "team_id" to teamId,
                        "project_id" to projectId,
                        "actor_id" to actorId,
                    ),
                ),
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
}

private class PlatformWorkflowObserver(
    private val runStore: WorkflowRunStore,
    private val workflowId: String,
) : WorkflowObserver by NoOpWorkflowObserver {
    override fun onWorkflowStarted(
        workflowName: String,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, RuntimeEvents.WORKFLOW_STARTED.name, status = WorkflowRunStatus.RUNNING)
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
        runStore.event(workflowName, workflowId, RuntimeEvents.STEP_STARTED.name, stepName, WorkflowRunStatus.RUNNING)
    }

    override fun onStepCompleted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, RuntimeEvents.STEP_COMPLETED.name, stepName, WorkflowRunStatus.RUNNING)
    }

    override fun onStepFailed(
        workflowName: String,
        stepName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, RuntimeEvents.STEP_FAILED.name, stepName)
    }

    override fun onWorkflowCompleted(
        workflowName: String,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, RuntimeEvents.WORKFLOW_COMPLETED.name)
    }

    override fun onWorkflowFailed(
        workflowName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        runStore.event(workflowName, workflowId, RuntimeEvents.WORKFLOW_FAILED.name)
    }
}

private fun WorkflowRunRecord.toResponse(): WorkflowRunResponse = WorkflowRunResponse(
    workflowId = workflowId,
    status = status.wireName,
    definitionVersion = definitionVersion,
    result = result,
)

private fun WorkflowRunRecord.toSummary() = dev.tramai.server.WorkflowRunSummary(
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
