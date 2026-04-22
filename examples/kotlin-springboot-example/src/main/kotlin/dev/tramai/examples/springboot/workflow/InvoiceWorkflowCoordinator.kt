package dev.tramai.examples.springboot.workflow

import dev.tramai.examples.springboot.ai.InvoiceAnalyzer
import dev.tramai.examples.springboot.domain.toApiResponse
import dev.tramai.orchestration.WorkflowCheckpoint
import dev.tramai.orchestration.WorkflowCheckpointConflictException
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.workflow
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Coordinates a small, audit-friendly workflow:
 * summarize -> triage -> branch -> optional tool enrichment -> finalize.
 *
 * The workflow stays intentionally explicit so the example demonstrates typed orchestration,
 * not a hidden agent loop.
 */
@Component
class InvoiceWorkflowCoordinator(
    private val analyzer: InvoiceAnalyzer,
    private val persistence: WorkflowPersistence<InvoiceWorkflowState>,
    @param:Value("\${tramai.example.workflow.persistence-root:build/tramai-example/workflows}")
    private val persistenceRoot: String,
) {
    private val monitor = Any()
    private val executionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeRuns = ConcurrentHashMap<String, Job>()
    private val activeRunStartedAtMillis = ConcurrentHashMap<String, Long>()
    private val cancellationRequests = ConcurrentHashMap.newKeySet<String>()
    private val workflowEvents = ConcurrentHashMap<String, MutableList<InvoiceWorkflowEventView>>()

    private val workflow = workflow<InvoiceWorkflowState>(WORKFLOW_NAME) {
        aiStep(
            name = "summary",
            input = { it.invoiceText },
            invoke = analyzer::summarize,
            merge = { state, summary -> state.copy(summary = summary) },
        )
        aiStep(
            name = "triage",
            input = { it.invoiceText },
            invoke = analyzer::triage,
            merge = { state, triage -> state.copy(triage = triage.toApiResponse()) },
        )
        branchStep(
            name = "route",
            select = { state ->
                if (state.triage?.needsImmediateAttention == true) {
                    "attention"
                } else {
                    "standard"
                }
            },
        ) {
            branch("attention") {
                localStep(
                    name = "mark-escalation",
                    transform = { state, _ -> state.copy(handlingLane = WorkflowLane.ESCALATION) },
                )
                aiStep(
                    name = "enrich-vendor",
                    input = { it.invoiceText },
                    invoke = analyzer::enrich,
                    merge = { state, enrichment -> state.copy(enrichment = enrichment) },
                )
            }
            default {
                localStep(
                    name = "mark-standard-review",
                    transform = { state, _ -> state.copy(handlingLane = WorkflowLane.STANDARD_REVIEW) },
                )
            }
        }
        localStep(
            name = "finalize",
            transform = { state, _ ->
                val triage = state.triage ?: error("triage must exist before finalize")
                val lane = state.handlingLane ?: error("handling lane must exist before finalize")
                val operatorBrief = buildString {
                    append(state.summary ?: triage.summary)
                    append(" Next step: ")
                    append(triage.nextStep.name)
                    append(". Lane: ")
                    append(lane.name)
                    state.enrichment?.let {
                        append(". Vendor context: ")
                        append(it)
                    }
                }
                state.copy(operatorBrief = operatorBrief)
            },
        )
    }.build { state ->
        InvoiceWorkflowResult(
            summary = state.summary ?: error("summary must exist"),
            triage = state.triage ?: error("triage must exist"),
            enrichment = state.enrichment,
            handlingLane = state.handlingLane ?: error("handling lane must exist"),
            operatorBrief = state.operatorBrief ?: error("operator brief must exist"),
        )
    }

    suspend fun run(request: InvoiceWorkflowRequest): InvoiceWorkflowExecution {
        val workflowId = request.workflowId ?: UUID.randomUUID().toString()
        val result = runManagedWorkflow(
            workflowId = workflowId,
            invoiceText = request.invoiceText,
            entrypoint = "example-http",
        )
        return InvoiceWorkflowExecution(workflowId = workflowId, result = result)
    }

    fun start(request: InvoiceWorkflowRequest): InvoiceWorkflowStartResponse {
        val workflowId = request.workflowId ?: UUID.randomUUID().toString()
        val acceptedAt = System.currentTimeMillis()
        synchronized(monitor) {
            val existing = activeRuns[workflowId]
            if (existing != null && existing.isActive) {
                throw WorkflowAlreadyRunningException(
                    "Workflow '$WORKFLOW_NAME' with workflowId '$workflowId' is already running",
                )
            }

            val job = executionScope.launch {
                runManagedWorkflow(
                    workflowId = workflowId,
                    invoiceText = request.invoiceText,
                    entrypoint = "example-http-start",
                )
            }
            activeRuns[workflowId] = job
            activeRunStartedAtMillis[workflowId] = acceptedAt
            recordEvent(
                workflowId = workflowId,
                type = WorkflowEventType.ACCEPTED,
                status = WorkflowExecutionStatus.PENDING,
                message = "Workflow accepted for asynchronous execution",
            )
            job.invokeOnCompletion {
                activeRuns.remove(workflowId, job)
                activeRunStartedAtMillis.remove(workflowId)
            }
        }

        return InvoiceWorkflowStartResponse(
            workflowId = workflowId,
            status = WorkflowExecutionStatus.PENDING,
            acceptedAtEpochMillis = acceptedAt,
        )
    }

    suspend fun resume(workflowId: String): InvoiceWorkflowExecution {
        recordEvent(
            workflowId = workflowId,
            type = WorkflowEventType.RESUME_REQUESTED,
            status = WorkflowExecutionStatus.RUNNING,
            message = "Workflow resume requested",
        )
        return execute(
            context = WorkflowContext(
                workflowId = workflowId,
                attributes = mapOf("entrypoint" to "example-http-resume"),
            ),
            runner = { context ->
                workflow.resume(
                    context = context,
                    persistence = persistence,
                )
            },
        )
    }

    suspend fun loadRun(workflowId: String): InvoiceWorkflowRunView? {
        val checkpoint = loadCheckpointWithRetry(workflowId)
        val isActive = activeRuns[workflowId]?.isActive == true
        if (checkpoint == null && !isActive) {
            return null
        }

        val state = checkpoint?.let {
            runCatching { persistence.stateCodec.decode(it.statePayload) }.getOrNull()
        }
        val persistedStatus = checkpoint?.metadata?.get(METADATA_STATUS)
            ?.let { raw -> runCatching { WorkflowExecutionStatus.valueOf(raw) }.getOrNull() }
        val status = when {
            isActive && checkpoint == null -> WorkflowExecutionStatus.PENDING
            isActive -> WorkflowExecutionStatus.RUNNING
            persistedStatus != null -> persistedStatus
            state?.operatorBrief != null -> WorkflowExecutionStatus.COMPLETED
            else -> WorkflowExecutionStatus.RUNNING
        }

        return InvoiceWorkflowRunView(
            workflowId = workflowId,
            status = status,
            result = state?.toResultOrNull(),
            errorMessage = checkpoint?.metadata?.get(METADATA_ERROR),
            checkpoint = checkpoint?.toView(),
        )
    }

    suspend fun loadCheckpoint(workflowId: String): InvoiceWorkflowCheckpointView? = loadCheckpointWithRetry(workflowId)
        ?.toView()

    suspend fun loadEvents(workflowId: String): List<InvoiceWorkflowEventView>? {
        val run = loadRun(workflowId)
        if (run == null) {
            return null
        }

        val inMemoryEvents = workflowEvents[workflowId]?.let { timeline ->
            synchronized(timeline) { timeline.toList() }
        }.orEmpty()
        if (inMemoryEvents.isNotEmpty()) {
            return inMemoryEvents.sortedBy { it.timestampEpochMillis }
        }

        val fallback = mutableListOf<InvoiceWorkflowEventView>()
        val savedAt = run.checkpoint?.savedAtEpochMillis ?: System.currentTimeMillis()
        val status = run.status
        val fallbackType = when (status) {
            WorkflowExecutionStatus.PENDING -> WorkflowEventType.ACCEPTED
            WorkflowExecutionStatus.RUNNING -> WorkflowEventType.RUN_STARTED
            WorkflowExecutionStatus.COMPLETED -> WorkflowEventType.RUN_COMPLETED
            WorkflowExecutionStatus.FAILED -> WorkflowEventType.RUN_FAILED
            WorkflowExecutionStatus.CANCELLED -> WorkflowEventType.RUN_CANCELLED
        }
        fallback += InvoiceWorkflowEventView(
            timestampEpochMillis = savedAt,
            type = fallbackType,
            status = status,
            message = "Synthesized from persisted workflow checkpoint metadata",
        )
        return fallback
    }

    suspend fun cancel(workflowId: String): InvoiceWorkflowCancelResponse {
        val activeJob = activeRuns[workflowId]
        if (activeJob != null && activeJob.isActive) {
            cancellationRequests += workflowId
            recordEvent(
                workflowId = workflowId,
                type = WorkflowEventType.CANCEL_REQUESTED,
                status = WorkflowExecutionStatus.CANCELLED,
                message = "Cancellation requested via API",
            )
            activeJob.cancel(CancellationException("Workflow cancelled via API"))
            return InvoiceWorkflowCancelResponse(
                workflowId = workflowId,
                status = WorkflowExecutionStatus.CANCELLED,
                cancelledAtEpochMillis = System.currentTimeMillis(),
            )
        }

        val existing = loadRun(workflowId)
            ?: throw WorkflowNotFoundException(
                "No workflow exists for workflowId '$workflowId'",
            )
        if (existing.status == WorkflowExecutionStatus.CANCELLED) {
            return InvoiceWorkflowCancelResponse(
                workflowId = workflowId,
                status = WorkflowExecutionStatus.CANCELLED,
                cancelledAtEpochMillis = System.currentTimeMillis(),
            )
        }
        throw WorkflowNotRunningException(
            "Workflow '$WORKFLOW_NAME' with workflowId '$workflowId' is not running",
        )
    }

    suspend fun listRuns(limit: Int = 20): List<InvoiceWorkflowRunSummary> {
        val boundedLimit = limit.coerceIn(1, 200)
        val ids = linkedSetOf<String>()
        ids.addAll(activeRuns.keys)
        ids.addAll(discoverCheckpointWorkflowIds())

        val summaries = mutableListOf<InvoiceWorkflowRunSummary>()
        for (workflowId in ids) {
            val run = loadRun(workflowId) ?: continue
            val updatedAt = run.checkpoint?.savedAtEpochMillis
                ?: activeRunStartedAtMillis[workflowId]
                ?: 0L
            summaries += InvoiceWorkflowRunSummary(
                workflowId = workflowId,
                status = run.status,
                updatedAtEpochMillis = updatedAt,
                hasResult = (run.result != null),
                errorMessage = run.errorMessage,
            )
        }

        return summaries
            .sortedByDescending { it.updatedAtEpochMillis }
            .take(boundedLimit)
    }

    @PreDestroy
    fun shutdown() {
        executionScope.cancel("Shutting down example workflow coordinator")
    }

    private suspend fun runManagedWorkflow(
        workflowId: String,
        invoiceText: String,
        entrypoint: String,
    ): InvoiceWorkflowResult {
        recordEvent(
            workflowId = workflowId,
            type = WorkflowEventType.RUN_STARTED,
            status = WorkflowExecutionStatus.RUNNING,
            message = "Workflow execution started",
        )
        val context = WorkflowContext(
            workflowId = workflowId,
            attributes = mapOf("entrypoint" to entrypoint),
        )
        return try {
            val result = workflow.run(
                initialState = InvoiceWorkflowState(invoiceText = invoiceText),
                context = context,
                persistence = persistence,
            )
            persistStatusMetadata(
                workflowId = workflowId,
                status = WorkflowExecutionStatus.COMPLETED,
            )
            recordEvent(
                workflowId = workflowId,
                type = WorkflowEventType.RUN_COMPLETED,
                status = WorkflowExecutionStatus.COMPLETED,
                message = "Workflow completed successfully",
            )
            result
        } catch (error: Throwable) {
            val cancelled = error is CancellationException || cancellationRequests.contains(workflowId)
            val status = if (cancelled) {
                WorkflowExecutionStatus.CANCELLED
            } else {
                WorkflowExecutionStatus.FAILED
            }
            persistStatusMetadata(
                workflowId = workflowId,
                status = status,
                errorMessage = if (cancelled) {
                    "Workflow cancelled via API"
                } else {
                    error.message ?: (error::class.simpleName ?: "Workflow failed")
                },
                waitForCheckpoint = true,
            )
            recordEvent(
                workflowId = workflowId,
                type = if (cancelled) WorkflowEventType.RUN_CANCELLED else WorkflowEventType.RUN_FAILED,
                status = status,
                message = if (cancelled) {
                    "Workflow cancelled"
                } else {
                    error.message ?: (error::class.simpleName ?: "Workflow failed")
                },
            )
            throw error
        } finally {
            cancellationRequests.remove(workflowId)
        }
    }

    private fun recordEvent(
        workflowId: String,
        type: WorkflowEventType,
        status: WorkflowExecutionStatus?,
        message: String,
    ) {
        val timeline = workflowEvents.computeIfAbsent(workflowId) { mutableListOf() }
        synchronized(timeline) {
            timeline += InvoiceWorkflowEventView(
                timestampEpochMillis = System.currentTimeMillis(),
                type = type,
                status = status,
                message = message,
            )
            if (timeline.size > MAX_EVENTS_PER_WORKFLOW) {
                timeline.removeAt(0)
            }
        }
    }

    private suspend fun persistStatusMetadata(
        workflowId: String,
        status: WorkflowExecutionStatus,
        errorMessage: String? = null,
        waitForCheckpoint: Boolean = false,
    ) {
        repeat(80) { attempt ->
            val checkpoint = try {
                persistence.checkpointStore.load(WORKFLOW_NAME, workflowId)
            } catch (_: OverlappingFileLockException) {
                if (attempt < 79) {
                    delay(10)
                }
                return@repeat
            }
            if (checkpoint == null) {
                if (waitForCheckpoint) {
                    delay(50)
                    return@repeat
                }
                return
            }

            val updatedMetadata = linkedMapOf<String, String>()
            updatedMetadata.putAll(checkpoint.metadata)
            updatedMetadata[METADATA_STATUS] = status.name
            updatedMetadata[METADATA_UPDATED_AT] = Instant.now().toString()
            if (errorMessage != null) {
                updatedMetadata[METADATA_ERROR] = errorMessage
            } else {
                updatedMetadata.remove(METADATA_ERROR)
            }

            try {
                persistence.checkpointStore.save(
                    checkpoint = checkpoint.copy(metadata = updatedMetadata),
                    expectedRevision = checkpoint.revision,
                )
                return
            } catch (_: WorkflowCheckpointConflictException) {
                if (attempt < 79) {
                    delay(10)
                }
            } catch (_: OverlappingFileLockException) {
                if (attempt < 79) {
                    delay(10)
                }
            }
        }
    }

    private suspend fun execute(
        context: WorkflowContext,
        runner: suspend (WorkflowContext) -> InvoiceWorkflowResult,
    ): InvoiceWorkflowExecution = InvoiceWorkflowExecution(
        workflowId = context.workflowId,
        result = runner(context),
    )

    private suspend fun loadCheckpointWithRetry(workflowId: String): WorkflowCheckpoint? {
        repeat(40) { attempt ->
            try {
                return persistence.checkpointStore.load(WORKFLOW_NAME, workflowId)
            } catch (_: OverlappingFileLockException) {
                if (attempt < 39) {
                    delay(10)
                }
            }
        }
        return null
    }

    private fun discoverCheckpointWorkflowIds(): List<String> {
        val workflowRoot = Path.of(persistenceRoot).resolve(WORKFLOW_NAME)
        if (!workflowRoot.exists() || !workflowRoot.isDirectory()) {
            return emptyList()
        }
        return runCatching {
            workflowRoot.listDirectoryEntries()
                .filter { it.isDirectory() }
                .map { it.fileName.toString() }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val WORKFLOW_NAME: String = "invoice-review-workflow"
        private const val MAX_EVENTS_PER_WORKFLOW: Int = 200
        private const val METADATA_STATUS: String = "workflow_status"
        private const val METADATA_UPDATED_AT: String = "workflow_updated_at"
        private const val METADATA_ERROR: String = "workflow_error"
    }
}

private fun WorkflowCheckpoint.toView(): InvoiceWorkflowCheckpointView = InvoiceWorkflowCheckpointView(
    workflowName = workflowName,
    workflowId = workflowId,
    nextStepIndex = nextStepIndex,
    stepExecutions = stepExecutions,
    lastCompletedStepName = lastCompletedStepName,
    revision = revision,
    savedAtEpochMillis = savedAtEpochMillis,
    metadata = metadata,
)

private fun InvoiceWorkflowState.toResultOrNull(): InvoiceWorkflowResult? {
    val summaryValue = summary ?: return null
    val triageValue = triage ?: return null
    val handlingLaneValue = handlingLane ?: return null
    val operatorBriefValue = operatorBrief ?: return null
    return InvoiceWorkflowResult(
        summary = summaryValue,
        triage = triageValue,
        enrichment = enrichment,
        handlingLane = handlingLaneValue,
        operatorBrief = operatorBriefValue,
    )
}
