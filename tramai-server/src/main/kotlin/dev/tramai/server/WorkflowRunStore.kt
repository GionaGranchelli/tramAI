package dev.tramai.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant

enum class WorkflowRunStatus(
    val wireName: String,
) {
    PENDING("pending"),
    RUNNING("running"),
    DELAYED("delayed"),
    WAITING_FOR_GATE("waiting_for_gate"),
    CANCELLING("cancelling"),
    CANCELLED("cancelled"),
    FAILED("failed"),
    COMPLETED("completed"),
}

data class WorkflowRunRecord(
    val workflowName: String,
    val workflowId: String,
    val definitionVersion: String,
    val status: WorkflowRunStatus,
    val currentStep: String?,
    val history: List<WorkflowRunEvent>,
    val result: Any?,
    val error: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val idempotencyKey: String?,
)

data class WorkflowRunEvent(
    val sequence: Long,
    val name: String,
    val stepName: String?,
    val timestamp: Instant,
)

data class SseEvent(
    val id: String,
    val event: String,
    val data: String,
)

class WorkflowRunStore(
    private val maxHistorySize: Int = 1_000,
    private val sseEventBufferSize: Int = 100,
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules(),
) {
    private val logger = LoggerFactory.getLogger(WorkflowRunStore::class.java)

    init {
        require(maxHistorySize > 0) { "maxHistorySize must be greater than zero" }
    }

    private val monitor = Any()
    private val runs = linkedMapOf<RunKey, MutableWorkflowRunRecord>()
    private val idempotencyKeys = linkedMapOf<IdempotencyKey, RunKey>()

    fun getOrCreate(
        workflowName: String,
        workflowId: String,
        definitionVersion: String,
        idempotencyKey: String?,
    ): WorkflowRunCreation = synchronized(monitor) {
        val existing = idempotencyKey?.takeIf(String::isNotBlank)
            ?.let { IdempotencyKey(workflowName, it) }
            ?.let(idempotencyKeys::get)
            ?.let(runs::get)
        if (existing != null) {
            WorkflowRunCreation(existing.snapshot(), created = false)
        } else {
            WorkflowRunCreation(
                createLocked(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    definitionVersion = definitionVersion,
                    idempotencyKey = idempotencyKey,
                ),
                created = true,
            )
        }
    }

    fun findByIdempotencyKey(
        workflowName: String,
        idempotencyKey: String?,
    ): WorkflowRunRecord? = synchronized(monitor) {
        idempotencyKey?.takeIf(String::isNotBlank)
            ?.let { IdempotencyKey(workflowName, it) }
            ?.let(idempotencyKeys::get)
                ?.let(runs::get)
                ?.snapshot()
    }

    fun create(
        workflowName: String,
        workflowId: String,
        definitionVersion: String,
        idempotencyKey: String?,
    ): WorkflowRunRecord = synchronized(monitor) {
        createLocked(
            workflowName = workflowName,
            workflowId = workflowId,
            definitionVersion = definitionVersion,
            idempotencyKey = idempotencyKey,
        )
    }

    private fun createLocked(
        workflowName: String,
        workflowId: String,
        definitionVersion: String,
        idempotencyKey: String?,
    ): WorkflowRunRecord {
        val key = RunKey(workflowName, workflowId)
        val now = Instant.now()
        val record = MutableWorkflowRunRecord(
            workflowName = workflowName,
            workflowId = workflowId,
            definitionVersion = definitionVersion,
            status = WorkflowRunStatus.PENDING,
            currentStep = null,
            history = mutableListOf(),
            result = null,
            error = null,
            createdAt = now,
            updatedAt = now,
            idempotencyKey = idempotencyKey?.takeIf(String::isNotBlank),
            nextSequence = 1,
            sseEvents = mutableListOf(),
            emitters = mutableListOf(),
            executionJob = null,
        )
        runs[key] = record
        record.idempotencyKey?.let {
            idempotencyKeys[IdempotencyKey(workflowName, it)] = key
        }
        evictOldestIfNeeded()
        return record.snapshot()
    }

    fun event(
        workflowName: String,
        workflowId: String,
        name: String,
        stepName: String? = null,
        status: WorkflowRunStatus? = null,
    ) {
        val dispatch = synchronized(monitor) {
            val record = runs[RunKey(workflowName, workflowId)] ?: return@synchronized null
            if (status != null && record.status.isTerminal()) {
                return@synchronized null
            }
            val now = Instant.now()
            val event = WorkflowRunEvent(
                sequence = record.nextSequence++,
                name = name,
                stepName = stepName,
                timestamp = now,
            )
            record.history += event
            record.sseEvents += event
            if (record.sseEvents.size > sseEventBufferSize) {
                record.sseEvents.removeAt(0)
            }
            record.currentStep = stepName ?: record.currentStep
            if (status != null) {
                record.status = status
            }
            record.updatedAt = now
            SseDispatch(
                workflowName = workflowName,
                workflowId = workflowId,
                sseEvent = event.toSseEvent(),
                emitters = record.emitters.toList(),
            )
        }
        if (dispatch == null) {
            return
        }
        dispatchToEmitters(dispatch)
    }

    fun complete(
        workflowName: String,
        workflowId: String,
        result: Any?,
    ): WorkflowRunRecord = terminal(
        workflowName = workflowName,
        workflowId = workflowId,
        status = WorkflowRunStatus.COMPLETED,
        result = result,
        error = null,
    )

    fun fail(
        workflowName: String,
        workflowId: String,
        error: Throwable,
        status: WorkflowRunStatus = WorkflowRunStatus.FAILED,
    ): WorkflowRunRecord {
        logger.error("Workflow '{}' run '{}' failed", workflowName, workflowId, error)
        return terminal(
            workflowName = workflowName,
            workflowId = workflowId,
            status = status,
            result = null,
            error = "Workflow execution failed",
        )
    }

    fun cancel(
        workflowName: String,
        workflowId: String,
    ): WorkflowRunRecord {
        val executionJob = synchronized(monitor) {
            val record = runs[RunKey(workflowName, workflowId)]
                ?: throw WorkflowRunNotFoundException(workflowName, workflowId)
            if (record.status.isTerminal()) {
                throw WorkflowConflictException(
                    "Workflow '$workflowName' run '$workflowId' is ${record.status.wireName} and cannot be cancelled",
                )
            }
            record.executionJob.also { record.executionJob = null }
        }

        event(
            workflowName = workflowName,
            workflowId = workflowId,
            name = "tramai.workflow.cancelling",
            stepName = currentStep(workflowName, workflowId),
            status = WorkflowRunStatus.CANCELLING,
        )
        event(
            workflowName = workflowName,
            workflowId = workflowId,
            name = "tramai.workflow.cancelled",
            stepName = currentStep(workflowName, workflowId),
            status = WorkflowRunStatus.CANCELLED,
        )

        executionJob?.cancel()
        completeEmitters(workflowName, workflowId)
        return get(workflowName, workflowId)
    }

    fun requireResumable(
        workflowName: String,
        workflowId: String,
    ): WorkflowRunRecord = synchronized(monitor) {
        val record = runs[RunKey(workflowName, workflowId)]
            ?: throw WorkflowRunNotFoundException(workflowName, workflowId)
        if (record.status != WorkflowRunStatus.DELAYED && record.status != WorkflowRunStatus.WAITING_FOR_GATE) {
            throw WorkflowConflictException(
                "Workflow '$workflowName' run '$workflowId' is ${record.status.wireName} and cannot be resumed",
            )
        }
        record.snapshot()
    }

    fun markResuming(
        workflowName: String,
        workflowId: String,
    ): WorkflowRunRecord {
        synchronized(monitor) {
            val record = runs[RunKey(workflowName, workflowId)]
                ?: throw WorkflowRunNotFoundException(workflowName, workflowId)
            if (record.status != WorkflowRunStatus.DELAYED && record.status != WorkflowRunStatus.WAITING_FOR_GATE) {
                throw WorkflowConflictException(
                    "Workflow '$workflowName' run '$workflowId' is ${record.status.wireName} and cannot be resumed",
                )
            }
            record.status = WorkflowRunStatus.RUNNING
            record.updatedAt = Instant.now()
            record.snapshot()
        }
        event(
            workflowName = workflowName,
            workflowId = workflowId,
            name = "tramai.workflow.running",
        )
        return get(workflowName, workflowId)
    }

    fun attachExecution(
        workflowName: String,
        workflowId: String,
        job: Job,
    ) {
        val cancelImmediately = synchronized(monitor) {
            val record = runs[RunKey(workflowName, workflowId)]
                ?: throw WorkflowRunNotFoundException(workflowName, workflowId)
            if (record.status.isTerminal()) {
                true
            } else {
                record.executionJob = job
                false
            }
        }
        if (cancelImmediately) {
            job.cancel()
        }
    }

    fun list(
        workflowName: String,
        offset: Int,
        limit: Int,
    ): List<WorkflowRunRecord> = synchronized(monitor) {
        runs.values
            .asSequence()
            .filter { it.workflowName == workflowName }
            .drop(offset)
            .take(limit)
            .map { it.snapshot() }
            .toList()
    }

    fun get(
        workflowName: String,
        workflowId: String,
    ): WorkflowRunRecord = synchronized(monitor) {
        runs[RunKey(workflowName, workflowId)]?.snapshot()
            ?: throw WorkflowRunNotFoundException(workflowName, workflowId)
    }

    fun registerSseEmitter(
        workflowName: String,
        workflowId: String,
        emitter: SseEmitter,
        since: Long?,
    ) {
        val registration = synchronized(monitor) {
            val record = runs[RunKey(workflowName, workflowId)]
                ?: throw WorkflowRunNotFoundException(workflowName, workflowId)

            emitter.onCompletion { synchronized(monitor) { record.emitters.remove(emitter) } }
            emitter.onError { synchronized(monitor) { record.emitters.remove(emitter) } }
            record.emitters.add(emitter)

            val eventsToSend = if (since != null) {
                record.sseEvents.filter { it.sequence > since }
            } else {
                record.sseEvents.toList()
            }

            SseRegistration(
                events = eventsToSend.map { it.toSseEvent() },
                completeAfterReplay = record.status.isTerminal(),
            )
        }

        try {
            registration.events.forEach { sseEvent ->
                emitter.send(
                    SseEmitter.event()
                        .id(sseEvent.id)
                        .name(sseEvent.event)
                        .data(sseEvent.data),
                )
            }
            if (registration.completeAfterReplay) {
                emitter.complete()
            }
        } catch (_: Exception) {
            synchronized(monitor) {
                runs[RunKey(workflowName, workflowId)]?.emitters?.remove(emitter)
            }
            emitter.completeWithError(IllegalStateException("Failed to register workflow SSE emitter"))
        }
    }

    private fun terminal(
        workflowName: String,
        workflowId: String,
        status: WorkflowRunStatus,
        result: Any?,
        error: String?,
    ): WorkflowRunRecord {
        val terminalUpdate = synchronized(monitor) {
            val record = runs[RunKey(workflowName, workflowId)]
                ?: throw WorkflowRunNotFoundException(workflowName, workflowId)
            if (record.status.isTerminal()) {
                return@synchronized TerminalUpdate(record.snapshot(), emptyList())
            }
            val now = Instant.now()
            record.status = status
            record.currentStep = null
            record.result = result
            record.error = error
            record.updatedAt = now
            record.executionJob = null
            TerminalUpdate(
                snapshot = record.snapshot(),
                emitters = record.emitters.toList().also { record.emitters.clear() },
            )
        }
        terminalUpdate.emitters.forEach { it.complete() }
        return terminalUpdate.snapshot
    }

    private fun currentStep(
        workflowName: String,
        workflowId: String,
    ): String? = synchronized(monitor) {
        runs[RunKey(workflowName, workflowId)]?.currentStep
    }

    private fun completeEmitters(
        workflowName: String,
        workflowId: String,
    ) {
        val emitters = synchronized(monitor) {
            val record = runs[RunKey(workflowName, workflowId)] ?: return
            record.emitters.toList().also { record.emitters.clear() }
        }
        emitters.forEach { it.complete() }
    }

    private fun dispatchToEmitters(dispatch: SseDispatch) {
        val deadEmitters = mutableListOf<SseEmitter>()
        dispatch.emitters.forEach { emitter ->
            try {
                emitter.send(
                    SseEmitter.event()
                        .id(dispatch.sseEvent.id)
                        .name(dispatch.sseEvent.event)
                        .data(dispatch.sseEvent.data),
                )
            } catch (_: Exception) {
                deadEmitters += emitter
            }
        }
        if (deadEmitters.isNotEmpty()) {
            synchronized(monitor) {
                runs[RunKey(dispatch.workflowName, dispatch.workflowId)]?.emitters?.removeAll(deadEmitters.toSet())
            }
        }
    }

    private fun WorkflowRunEvent.toSseEvent(): SseEvent = SseEvent(
        id = sequence.toString(),
        event = name,
        data = objectMapper.writeValueAsString(
            SsePayload(
                stepName = stepName,
                timestamp = timestamp,
            ),
        ),
    )

    private fun evictOldestIfNeeded() {
        while (runs.size > maxHistorySize) {
            val oldestKey = runs.keys.first()
            val oldest = runs.remove(oldestKey)
            oldest?.idempotencyKey?.let {
                idempotencyKeys.remove(IdempotencyKey(oldest.workflowName, it))
            }
        }
    }
}

data class WorkflowRunCreation(
    val record: WorkflowRunRecord,
    val created: Boolean,
)

class WorkflowRunNotFoundException(
    workflowName: String,
    workflowId: String,
) : RuntimeException("Workflow '$workflowName' run '$workflowId' was not found")

private data class RunKey(
    val workflowName: String,
    val workflowId: String,
)

private data class IdempotencyKey(
    val workflowName: String,
    val value: String,
)

private data class MutableWorkflowRunRecord(
    val workflowName: String,
    val workflowId: String,
    val definitionVersion: String,
    var status: WorkflowRunStatus,
    var currentStep: String?,
    val history: MutableList<WorkflowRunEvent>,
    var result: Any?,
    var error: String?,
    val createdAt: Instant,
    var updatedAt: Instant,
    val idempotencyKey: String?,
    var nextSequence: Long,
    val sseEvents: MutableList<WorkflowRunEvent>,
    val emitters: MutableList<SseEmitter>,
    var executionJob: Job?,
) {
    fun snapshot(): WorkflowRunRecord = WorkflowRunRecord(
        workflowName = workflowName,
        workflowId = workflowId,
        definitionVersion = definitionVersion,
        status = status,
        currentStep = currentStep,
        history = history.toList(),
        result = result,
        error = error,
        createdAt = createdAt,
        updatedAt = updatedAt,
        idempotencyKey = idempotencyKey,
    )
}

private data class SsePayload(
    val stepName: String?,
    val timestamp: Instant,
)

private data class SseDispatch(
    val workflowName: String,
    val workflowId: String,
    val sseEvent: SseEvent,
    val emitters: List<SseEmitter>,
)

private data class SseRegistration(
    val events: List<SseEvent>,
    val completeAfterReplay: Boolean,
)

private data class TerminalUpdate(
    val snapshot: WorkflowRunRecord,
    val emitters: List<SseEmitter>,
)

private fun WorkflowRunStatus.isTerminal(): Boolean =
    this == WorkflowRunStatus.COMPLETED ||
        this == WorkflowRunStatus.FAILED ||
        this == WorkflowRunStatus.CANCELLED
