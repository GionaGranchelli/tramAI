package dev.tramai.orchestration

import java.time.Instant

/**
 * Coordination layer between workflow execution and the persistence SPI for
 * one run. Owns checkpoint saving, completion, abort, revision handling,
 * compatibility metadata, and lease renewal/release. It does not redesign
 * [WorkflowPersistence]: checkpoint format, metadata keys, revision semantics,
 * CAS behaviour, serialization, and store interfaces are unchanged.
 */
internal class WorkflowPersistenceSession<S>(
    private val persistence: WorkflowPersistence<S>,
    private val workflowName: String,
    private val context: WorkflowContext,
    private val observer: WorkflowObserver,
    private val workflowDefinitionCompatibility: WorkflowDefinitionCompatibility,
    private var lease: WorkflowLease?,
    initialRevision: Long?,
) {
    private var currentRevision: Long? = initialRevision

    suspend fun saveCheckpoint(
        state: S,
        nextStepIndex: Int,
        lastCompletedStepName: String?,
        stepExecutions: Int,
        extraMetadata: Map<String, String> = emptyMap(),
    ) {
        val persisted = persistence.checkpointStore.save(
            checkpoint = WorkflowCheckpoint(
                workflowName = workflowName,
                workflowId = context.workflowId,
                nextStepIndex = nextStepIndex,
                stepExecutions = stepExecutions,
                lastCompletedStepName = lastCompletedStepName,
                statePayload = persistence.stateCodec.encode(state),
                metadata = workflowDefinitionCompatibility.toCheckpointMetadata() + extraMetadata,
            ),
            expectedRevision = currentRevision,
        )
        currentRevision = persisted.revision
        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.checkpoint.saved",
            attributes = mapOf(
                "workflow_id" to persisted.workflowId,
                "next_step_index" to persisted.nextStepIndex,
                "step_executions" to persisted.stepExecutions,
                "revision" to persisted.revision,
                "has_last_completed_step" to (persisted.lastCompletedStepName != null),
                "definition_version" to workflowDefinitionCompatibility.version,
                "definition_digest_algorithm" to workflowDefinitionCompatibility.digestAlgorithm,
            ),
            context = context,
        )
        renewLeaseIfPresent()
    }

    suspend fun scheduleDelayWakeup(
        stepName: String,
        resumeAt: Instant,
    ) {
        persistence.delayWakeupScheduler?.scheduleDelayWakeup(
            runId = context.workflowId,
            stepId = stepName,
            resumeAt = resumeAt,
        )
    }

    suspend fun complete(
        workflowName: String,
        context: WorkflowContext,
    ) {
        if (persistence.deleteCheckpointOnCompletion) {
            persistence.checkpointStore.delete(
                workflowName = workflowName,
                workflowId = context.workflowId,
                expectedRevision = currentRevision,
            )
        }
        releaseLeaseIfPresent()
    }

    suspend fun abort() {
        releaseLeaseIfPresent()
    }

    private suspend fun renewLeaseIfPresent() {
        val currentLease = lease ?: return
        val policy = persistence.leasePolicy ?: return
        val store = persistence.leaseStore ?: return
        lease = store.renew(
            lease = currentLease,
            checkpointRevision = currentRevision,
            leaseDurationMillis = policy.leaseDurationMillis,
        )
        val renewedLease = lease ?: return
        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.lease.renewed",
            attributes = leaseAttributes(renewedLease),
            context = context,
        )
    }

    private suspend fun releaseLeaseIfPresent() {
        val currentLease = lease ?: return
        val store = persistence.leaseStore ?: return
        store.release(currentLease)
        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.lease.released",
            attributes = leaseAttributes(currentLease),
            context = context,
        )
        lease = null
    }

    private fun leaseAttributes(lease: WorkflowLease): Map<String, Any?> = mapOf(
        "workflow_id" to lease.workflowId,
        "lease_id" to lease.leaseId,
        "owner_id" to lease.ownerId,
        "checkpoint_revision" to lease.checkpointRevision,
        "acquired_at_epoch_millis" to lease.acquiredAtEpochMillis,
        "expires_at_epoch_millis" to lease.expiresAtEpochMillis,
    )
}

internal suspend fun <S> WorkflowPersistence<S>.session(
    workflowName: String,
    context: WorkflowContext,
    observer: WorkflowObserver,
    workflowDefinitionCompatibility: WorkflowDefinitionCompatibility,
    initialRevision: Long? = null,
): WorkflowPersistenceSession<S> = WorkflowPersistenceSession(
    persistence = this,
    workflowName = workflowName,
    context = context,
    observer = observer,
    workflowDefinitionCompatibility = workflowDefinitionCompatibility,
    lease = acquireLeaseIfConfigured(
        workflowName = workflowName,
        workflowId = context.workflowId,
        observer = observer,
        context = context,
        checkpointRevision = initialRevision,
    ),
    initialRevision = initialRevision,
)

internal suspend fun <S> WorkflowPersistence<S>.acquireLeaseIfConfigured(
    workflowName: String,
    workflowId: String,
    observer: WorkflowObserver,
    context: WorkflowContext,
    checkpointRevision: Long?,
): WorkflowLease? {
    val store = leaseStore ?: return null
    val policy = leasePolicy ?: return null
    return try {
        store.claim(
            workflowName = workflowName,
            workflowId = workflowId,
            ownerId = policy.ownerId,
            checkpointRevision = checkpointRevision,
            leaseDurationMillis = policy.leaseDurationMillis,
        ).also { lease ->
            observer.onWorkflowEvent(
                workflowName = workflowName,
                name = "tramai.workflow.lease.claimed",
                attributes = mapOf(
                    "workflow_id" to lease.workflowId,
                    "lease_id" to lease.leaseId,
                    "owner_id" to lease.ownerId,
                    "checkpoint_revision" to lease.checkpointRevision,
                    "acquired_at_epoch_millis" to lease.acquiredAtEpochMillis,
                    "expires_at_epoch_millis" to lease.expiresAtEpochMillis,
                ),
                context = context,
            )
        }
    } catch (error: WorkflowLeaseConflictException) {
        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.lease.conflict",
            attributes = mapOf(
                "workflow_id" to workflowId,
                "owner_id" to policy.ownerId,
                "checkpoint_revision" to checkpointRevision,
                "error_type" to error::class.simpleName,
            ),
            context = context,
        )
        throw error
    }
}

internal suspend fun <S> WorkflowPersistenceSession<S>.runCatchingAbort(
    error: Throwable,
) {
    runCatching { abort() }
        .onFailure { error.addSuppressed(it) }
}
