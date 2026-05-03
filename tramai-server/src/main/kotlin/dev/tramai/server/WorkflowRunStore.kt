package dev.tramai.server

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

class WorkflowRunStore {
    private val monitor = Any()
    private val runs = linkedMapOf<RunKey, MutableWorkflowRunRecord>()
    private val idempotencyKeys = linkedMapOf<IdempotencyKey, RunKey>()

    fun findByIdempotencyKey(
        workflowName: String,
        idempotencyKey: String?,
    ): WorkflowRunRecord? = synchronized(monitor) {
        if (idempotencyKey.isNullOrBlank()) {
            null
        } else {
            idempotencyKeys[IdempotencyKey(workflowName, idempotencyKey)]
                ?.let(runs::get)
                ?.snapshot()
        }
    }

    fun create(
        workflowName: String,
        workflowId: String,
        definitionVersion: String,
        idempotencyKey: String?,
    ): WorkflowRunRecord = synchronized(monitor) {
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
        )
        runs[key] = record
        record.idempotencyKey?.let {
            idempotencyKeys[IdempotencyKey(workflowName, it)] = key
        }
        record.snapshot()
    }

    fun event(
        workflowName: String,
        workflowId: String,
        name: String,
        stepName: String? = null,
        status: WorkflowRunStatus? = null,
    ) = synchronized(monitor) {
        val record = runs[RunKey(workflowName, workflowId)] ?: return@synchronized
        val now = Instant.now()
        record.history += WorkflowRunEvent(
            sequence = record.nextSequence++,
            name = name,
            stepName = stepName,
            timestamp = now,
        )
        record.currentStep = stepName ?: record.currentStep
        if (status != null) {
            record.status = status
        }
        record.updatedAt = now
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
    ): WorkflowRunRecord = terminal(
        workflowName = workflowName,
        workflowId = workflowId,
        status = status,
        result = null,
        error = error.message ?: error::class.java.name,
    )

    fun cancel(
        workflowName: String,
        workflowId: String,
    ): WorkflowRunRecord = synchronized(monitor) {
        val record = runs[RunKey(workflowName, workflowId)]
            ?: throw WorkflowRunNotFoundException(workflowName, workflowId)
        val now = Instant.now()
        record.status = WorkflowRunStatus.CANCELLING
        record.history += WorkflowRunEvent(
            sequence = record.nextSequence++,
            name = "tramai.workflow.cancelling",
            stepName = record.currentStep,
            timestamp = now,
        )
        record.status = WorkflowRunStatus.CANCELLED
        record.history += WorkflowRunEvent(
            sequence = record.nextSequence++,
            name = "tramai.workflow.cancelled",
            stepName = record.currentStep,
            timestamp = now,
        )
        record.updatedAt = now
        record.snapshot()
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

    private fun terminal(
        workflowName: String,
        workflowId: String,
        status: WorkflowRunStatus,
        result: Any?,
        error: String?,
    ): WorkflowRunRecord = synchronized(monitor) {
        val record = runs[RunKey(workflowName, workflowId)]
            ?: throw WorkflowRunNotFoundException(workflowName, workflowId)
        val now = Instant.now()
        record.status = status
        record.currentStep = null
        record.result = result
        record.error = error
        record.updatedAt = now
        record.snapshot()
    }
}

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
