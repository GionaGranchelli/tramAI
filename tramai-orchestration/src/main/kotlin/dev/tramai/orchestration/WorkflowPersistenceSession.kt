package dev.tramai.orchestration

import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
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
    initialGeneration: String?,
) {
    private var currentRevision: Long? = initialRevision
    private var currentGeneration: String? = initialGeneration

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
                checkpointGeneration = currentGeneration,
            ),
            expectedRevision = currentRevision,
        )
        currentRevision = persisted.revision
        currentGeneration = persisted.checkpointGeneration
        observer.emitWorkflowEvent(
            workflowName = workflowName,
            context = context,
            event = RuntimeEvent.of(RuntimeEvents.WORKFLOW_CHECKPOINT_SAVED) {
                set(RuntimeAttributes.WORKFLOW_ID_BARE, persisted.workflowId)
                set(RuntimeAttributes.NEXT_STEP_INDEX, persisted.nextStepIndex.toLong())
                set(RuntimeAttributes.STEP_EXECUTIONS, persisted.stepExecutions.toLong())
                set(RuntimeAttributes.REVISION, persisted.revision)
                set(RuntimeAttributes.HAS_LAST_COMPLETED_STEP, persisted.lastCompletedStepName != null)
                set(RuntimeAttributes.DEFINITION_VERSION, workflowDefinitionCompatibility.version)
                set(RuntimeAttributes.DEFINITION_DIGEST_ALGORITHM, workflowDefinitionCompatibility.digestAlgorithm)
            },
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
                expectedGeneration = currentGeneration,
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
        observer.emitWorkflowEvent(
            workflowName = workflowName,
            context = context,
            event = leaseAttributes(RuntimeEvents.WORKFLOW_LEASE_RENEWED, renewedLease),
        )
    }

    private suspend fun releaseLeaseIfPresent() {
        val currentLease = lease ?: return
        val store = persistence.leaseStore ?: return
        store.release(currentLease)
        observer.emitWorkflowEvent(
            workflowName = workflowName,
            context = context,
            event = leaseAttributes(RuntimeEvents.WORKFLOW_LEASE_RELEASED, currentLease),
        )
        lease = null
    }

    /** Catalogue-validated lease event carrying the lease's attribute payload. */
    private fun leaseAttributes(
        definition: dev.tramai.core.observation.event.RuntimeEventDefinition,
        lease: WorkflowLease,
    ): RuntimeEvent = RuntimeEvent.of(definition) {
        set(RuntimeAttributes.WORKFLOW_ID_BARE, lease.workflowId)
        set(RuntimeAttributes.LEASE_ID, lease.leaseId)
        set(RuntimeAttributes.OWNER_ID, lease.ownerId)
        set(RuntimeAttributes.CHECKPOINT_REVISION, lease.checkpointRevision ?: 0L)
        set(RuntimeAttributes.ACQUIRED_AT_EPOCH_MILLIS, lease.acquiredAtEpochMillis)
        set(RuntimeAttributes.EXPIRES_AT_EPOCH_MILLIS, lease.expiresAtEpochMillis)
    }
}

internal suspend fun <S> WorkflowPersistence<S>.session(
    workflowName: String,
    context: WorkflowContext,
    observer: WorkflowObserver,
    workflowDefinitionCompatibility: WorkflowDefinitionCompatibility,
    initialRevision: Long? = null,
    initialGeneration: String? = null,
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
    initialGeneration = initialGeneration,
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
            observer.emitWorkflowEvent(
                workflowName = workflowName,
                context = context,
                event = RuntimeEvent.of(RuntimeEvents.WORKFLOW_LEASE_CLAIMED) {
                    set(RuntimeAttributes.WORKFLOW_ID_BARE, lease.workflowId)
                    set(RuntimeAttributes.LEASE_ID, lease.leaseId)
                    set(RuntimeAttributes.OWNER_ID, lease.ownerId)
                    set(RuntimeAttributes.CHECKPOINT_REVISION, lease.checkpointRevision ?: 0L)
                    set(RuntimeAttributes.ACQUIRED_AT_EPOCH_MILLIS, lease.acquiredAtEpochMillis)
                    set(RuntimeAttributes.EXPIRES_AT_EPOCH_MILLIS, lease.expiresAtEpochMillis)
                },
            )
        }
    } catch (error: WorkflowLeaseConflictException) {
        observer.emitWorkflowEvent(
            workflowName = workflowName,
            context = context,
            event = RuntimeEvent.of(RuntimeEvents.WORKFLOW_LEASE_CONFLICT) {
                set(RuntimeAttributes.WORKFLOW_ID_BARE, workflowId)
                set(RuntimeAttributes.OWNER_ID, policy.ownerId)
                set(RuntimeAttributes.CHECKPOINT_REVISION, checkpointRevision ?: 0L)
                set(RuntimeAttributes.ERROR_TYPE, error::class.simpleName ?: "Throwable")
            },
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
