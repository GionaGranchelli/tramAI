package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the active-execution registry and the workflow execution path.
 *
 * This is the only component answering "is workflow X currently running on
 * this worker?". It owns the [ActiveExecution] registry and the execution
 * failure map, launches execution jobs in the worker root scope (injected by
 * the lifecycle controller — no scope is created here), drives binding
 * resolution plus recovery dispatch, and finalizes leases through
 * [LeaseCoordinator].
 *
 * The execution machinery ([ExecutionTracker], [LeaseFencedCheckpointStore],
 * [WorkerExecutionObserver]) lives in this file as private implementation
 * detail; [ExecutionTracker] is internal because [WorkflowRecoveryCoordinator]
 * drives its recovery transitions.
 */
internal class WorkflowExecutionSupervisor(
    private val config: WorkerConfig,
    private val leaseStore: WorkflowLeaseStore,
    private val checkpointStore: WorkflowCheckpointStore,
    private val stepAttemptStore: StepAttemptRecordStore,
    private val workflowBindings: WorkflowBindingRegistry,
    private val observability: TramaiWorkerObserver,
    private val leaseCoordinator: LeaseCoordinator,
    private val recoveryCoordinator: WorkflowRecoveryCoordinator,
    private val leaseRenewalLoop: LeaseRenewalLoop,
    private val shuttingDownGracefully: () -> Boolean,
) {
    private val activeExecutions = ConcurrentHashMap<String, ActiveExecution>()
    private val executionFailures = ConcurrentHashMap<String, Throwable>()
    private var workerScope: CoroutineScope? = null

    /** Injected by the lifecycle controller; the worker root scope is owned there. */
    fun attachScope(scope: CoroutineScope) {
        workerScope = scope
    }

    fun isRunning(workflowId: String): Boolean = activeExecutions.containsKey(workflowId)

    fun activeExecutionCount(): Int = activeExecutions.size

    fun activeExecutionsSnapshot(): List<ActiveExecution> = activeExecutions.values.toList()

    fun latestFailure(workflowId: String): Throwable? = executionFailures[workflowId]

    /**
     * Registers an execution for [checkpoint] under [lease]. Preserves the
     * putIfAbsent race handling: if two paths attempt to register the same
     * workflow, the loser releases the lease it acquired.
     */
    fun launch(
        checkpoint: WorkflowCheckpoint,
        lease: WorkflowLease,
    ) {
        val scope = workerScope ?: return
        val handle = ActiveExecution(
            workflowName = checkpoint.workflowName,
            workflowId = checkpoint.workflowId,
            lease = AtomicReference(lease),
        )
        val previous = activeExecutions.putIfAbsent(checkpoint.workflowId, handle)
        if (previous != null) {
            scope.launch {
                releaseLease(handle)
            }
            return
        }
        handle.executionJob = scope.launch {
            try {
                executeClaimedWorkflow(checkpoint, handle)
            } finally {
                handle.tracker?.close()
                handle.renewalJob?.cancel()
                activeExecutions.remove(checkpoint.workflowId, handle)
            }
        }
        handle.renewalJob = scope.launch {
            leaseRenewalLoop.renew(handle)
        }
    }

    private suspend fun executeClaimedWorkflow(
        checkpoint: WorkflowCheckpoint,
        handle: ActiveExecution,
    ) {
        val definitionVersion = checkpoint.metadata[WORKFLOW_DEFINITION_VERSION_METADATA_KEY]
            ?: run {
                // Absent definition metadata means no worker can ever route this
                // checkpoint. Unlike an unbound version (which another worker may
                // implement), this must surface as a visible failure instead of a
                // silent skip: release the lease and record latestFailure so the
                // stranded checkpoint is diagnosable.
                val error = missingDefinitionMetadataException(
                    workflowName = checkpoint.workflowName,
                    workflowId = checkpoint.workflowId,
                    missingKey = WORKFLOW_DEFINITION_VERSION_METADATA_KEY,
                )
                executionFailures[checkpoint.workflowId] = error
                releaseLease(handle)
                throw error
            }
        val binding = workflowBindings.resolve(checkpoint.workflowName, definitionVersion) ?: run {
            releaseLease(handle)
            return
        }
        val typedWorkflow = binding.erased.workflow
        val context = WorkflowContext(workflowId = checkpoint.workflowId)
        val tracker = ExecutionTracker(
            workerId = config.workerId,
            workflow = typedWorkflow,
            binding = binding.erased,
            context = context,
            stepAttemptStore = stepAttemptStore,
            observability = observability,
            leaseProvider = { handle.lease.get() },
        )
        handle.tracker = tracker
        tracker.prepareForCheckpoint(checkpoint)
        handle.lastRevision.set(checkpoint.revision)

        val fencedCheckpointStore = LeaseFencedCheckpointStore(
            delegate = checkpointStore,
            leaseStore = leaseStore,
            leaseProvider = { handle.lease.get() },
            tracker = tracker,
            revisionSink = { handle.lastRevision.set(it) },
        )

        val unknownAttempt = tracker.recoverAttemptIfNeeded(checkpoint)
        if (unknownAttempt != null) {
            observability.onLeaseExpired(checkpoint.workflowId, unknownAttempt.workerId)
            observability.onWorkTakenOver(
                workflowId = checkpoint.workflowId,
                previousWorkerId = unknownAttempt.workerId,
                newWorkerId = config.workerId,
            )
            try {
                when (unknownAttempt.resolutionAction) {
                    StepAttemptResolutionAction.RETRY_APPROVED -> recoveryCoordinator.consumeRetryApproval(
                        checkpoint = checkpoint,
                        expectedLease = handle.lease.get(),
                        tracker = tracker,
                        fencedCheckpointStore = fencedCheckpointStore,
                        attempt = unknownAttempt,
                    )
                    StepAttemptResolutionAction.WORKFLOW_FAILED -> throw WorkflowRecoveryStateException(
                        "Attempt '${unknownAttempt.attemptId}' is resolved as WORKFLOW_FAILED and cannot be executed",
                    )
                    null -> recoveryCoordinator.recoverUnknownAttempt(checkpoint, tracker, fencedCheckpointStore, unknownAttempt)
                }
            } catch (error: Throwable) {
                // The recovery persistence paths throw NonReplayableStepStateUnknownException
                // (or a lease conflict when the fence rejects a stale worker). Record the
                // failure so latestFailure() reflects it, and release the lease so the
                // checkpoint can be reclaimed once an operator resolves it. The unknown
                // attempt record is deliberately left at UNKNOWN as audit evidence.
                error.rethrowIfCancellation()
                executionFailures[checkpoint.workflowId] = error
                releaseLease(handle)
                throw error
            }
        }

        val persistence = WorkflowPersistence(
            checkpointStore = fencedCheckpointStore,
            stateCodec = binding.erased.stateCodec,
            delayWakeupScheduler = binding.erased.delayWakeupScheduler,
            deleteCheckpointOnCompletion = binding.erased.deleteCheckpointOnCompletion,
        )
        val observer = WorkerExecutionObserver(
            workflowName = checkpoint.workflowName,
            tracker = tracker,
        )

        try {
            typedWorkflow.resume(
                context = context,
                observer = observer,
                persistence = persistence,
            )
            executionFailures.remove(checkpoint.workflowId)
            releaseLease(handle)
        } catch (suspended: WorkflowSuspendedException) {
            executionFailures.remove(checkpoint.workflowId)
            tracker.cancelActiveAttempt("Workflow suspended before the current step reached a durable checkpoint")
            releaseLease(handle)
        } catch (error: CancellationException) {
            if (shuttingDownGracefully()) {
                withContext(NonCancellable) {
                    runCleanupPreservingCancellation(error) {
                        tracker.cancelActiveAttempt(
                            "Worker shutdown cancelled the running step",
                        )
                    }
                    runCleanupPreservingCancellation(error) {
                        observability.onWorkflowAbandoned(
                            workflowId = checkpoint.workflowId,
                            workerId = config.workerId,
                            lastStep = checkpoint.lastCompletedStepName,
                            timeoutMillis = config.drainTimeoutMillis,
                        )
                    }
                    runCleanupPreservingCancellation(error) {
                        releaseLease(handle)
                    }
                    executionFailures.remove(checkpoint.workflowId)
                }
            }
            throw error
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            executionFailures[checkpoint.workflowId] = error
            tracker.failActiveAttempt(error)
            releaseLease(handle)
            throw error
        }
    }

    private suspend fun releaseLease(handle: ActiveExecution) {
        val lease = handle.lease.get() ?: return
        if (leaseCoordinator.release(lease)) {
            handle.lease.compareAndSet(lease, null)
        }
    }
}

/**
 * One claimed workflow execution, alive from lease claim to release.
 */
internal data class ActiveExecution(
    val workflowName: String,
    val workflowId: String,
    val lease: AtomicReference<WorkflowLease?>,
    val lastRevision: AtomicReference<Long?> = AtomicReference(null),
    var tracker: ExecutionTracker? = null,
    var executionJob: Job? = null,
    var renewalJob: Job? = null,
)

private class WorkerExecutionObserver(
    private val workflowName: String,
    private val tracker: ExecutionTracker,
) : WorkflowObserver by NoOpWorkflowObserver {
    override fun onStepStarted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        if (this.workflowName == workflowName) {
            tracker.enqueueStartAttempt(stepName)
        }
    }

    override fun onStepFailed(
        workflowName: String,
        stepName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        if (this.workflowName == workflowName) {
            tracker.enqueueFailedAttempt(stepName, error)
        }
    }
}

/**
 * Serialized attempt-state machine for one execution.
 *
 * Moves step attempts through STARTED/COMPLETED/FAILED/CANCELLED against the
 * step-attempt store, with an observer-transition tail that preserves ordering
 * between workflow observer events and persistence-driven completions.
 */
internal class ExecutionTracker(
    private val workerId: String,
    private val workflow: Workflow<Any?, Any?>,
    private val binding: ErasedWorkflowBinding,
    private val context: WorkflowContext,
    private val stepAttemptStore: StepAttemptRecordStore,
    private val observability: TramaiWorkerObserver,
    private val leaseProvider: () -> WorkflowLease?,
) {
    private val monitor = Any()
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val completedObserverTransition = CompletableDeferred<Result<Unit>>().also {
        it.complete(Result.success(Unit))
    }
    private var observerTransitionTail: Deferred<Result<Unit>> = completedObserverTransition
    private val trackedStepNames = workflow.topLevelStepNames()
    private var inputFingerprint: String? = null
    private var preparedStepName: String? = null
    private var preparedReplayDescriptor: WorkflowStepReplayDescriptor? = null
    private var activeAttempt: StepAttemptRecord? = null

    suspend fun prepareForCheckpoint(checkpoint: WorkflowCheckpoint) {
        val replayDescriptor = binding.replayDescriptor(checkpoint, context)
        synchronized(monitor) {
            inputFingerprint = sha256Hex(checkpoint.statePayload)
            preparedStepName = workflow.stepNameAt(checkpoint.nextStepIndex)
            preparedReplayDescriptor = replayDescriptor
        }
    }

    fun currentStepReplayDescriptor(): WorkflowStepReplayDescriptor? = synchronized(monitor) { preparedReplayDescriptor }

    suspend fun recoverAttemptIfNeeded(checkpoint: WorkflowCheckpoint): StepAttemptRecord? {
        val nextStepName = workflow.stepNameAt(checkpoint.nextStepIndex) ?: return null
        val latest = stepAttemptStore.latestStepAttempt(checkpoint.workflowId, nextStepName) ?: return null
        if (latest.status == StepAttemptStatus.UNKNOWN) {
            return latest
        }
        if (latest.status == StepAttemptStatus.STARTED) {
            val unknown = latest.copy(
                status = StepAttemptStatus.UNKNOWN,
                completedAt = System.currentTimeMillis(),
                outputSummary = latest.outputSummary ?: "Lease expired before the step reached a durable checkpoint",
            )
            stepAttemptStore.updateStepAttempt(unknown)
            observability.onUnknownAttempt(
                runId = unknown.runId,
                stepName = unknown.stepName,
                priorWorkerId = unknown.workerId,
                attemptTime = unknown.startedAt,
            )
            return unknown
        }
        return null
    }

    suspend fun startAttempt(stepName: String) {
        if (!trackedStepNames.contains(stepName)) {
            return
        }
        val descriptor: WorkflowStepReplayDescriptor
        val fingerprint: String?
        synchronized(monitor) {
            if (preparedStepName != stepName) {
                return
            }
            descriptor = preparedReplayDescriptor ?: WorkflowStepReplayDescriptor(ReplayPolicy.NON_REPLAYABLE)
            fingerprint = inputFingerprint
        }
        val attempt = StepAttemptRecord(
            runId = context.workflowId,
            stepName = stepName,
            attemptId = UUID.randomUUID().toString(),
            workerId = workerId,
            leaseToken = leaseProvider()?.leaseId ?: "unknown",
            status = StepAttemptStatus.STARTED,
            startedAt = System.currentTimeMillis(),
            idempotencyKey = descriptor.idempotencyKey,
            replayPolicy = descriptor.replayPolicy,
            inputFingerprint = fingerprint,
        )
        stepAttemptStore.recordStepAttempt(attempt)
        synchronized(monitor) {
            activeAttempt = attempt
        }
        observability.onStepAttemptStarted(context.workflowId, stepName, attempt.attemptId, workerId)
    }

    suspend fun consumeRetryApproval(attempt: StepAttemptRecord) {
        if (leaseProvider() == null) {
            throw StaleWorkflowLeaseException(
                "Workflow '${workflow.name}' and workflowId='${context.workflowId}' has no active lease for retry-approval consumption",
            )
        }
        val consumed = attempt.copy(
            status = StepAttemptStatus.FAILED,
            completedAt = attempt.completedAt ?: System.currentTimeMillis(),
        )
        // Atomic CAS: the approval is consumed exactly once. A concurrent writer (e.g. a
        // stale-approval void) between the recovery read and here must not be overwritten;
        // on failure the caller fails closed and the next worker re-evaluates.
        if (!stepAttemptStore.compareAndSetStepAttempt(expected = attempt, updated = consumed)) {
            throw WorkflowRecoveryStateException(
                "Workflow '${workflow.name}' and workflowId='${context.workflowId}': attempt '${attempt.attemptId}' changed during retry-approval consumption",
            )
        }
    }

    fun enqueueStartAttempt(stepName: String) {
        enqueueObserverTransition {
            startAttempt(stepName)
        }
    }

    suspend fun completeAttempt(
        persistedCheckpoint: WorkflowCheckpoint,
    ) {
        awaitObserverTransitions()
        val attempt = synchronized(monitor) {
            activeAttempt?.takeIf { it.stepName == persistedCheckpoint.lastCompletedStepName }
        } ?: run {
            prepareForCheckpoint(persistedCheckpoint)
            return
        }
        val completed = attempt.copy(
            status = StepAttemptStatus.COMPLETED,
            completedAt = System.currentTimeMillis(),
            outputSummary = "Checkpoint revision ${persistedCheckpoint.revision}",
        )
        stepAttemptStore.updateStepAttempt(completed)
        synchronized(monitor) {
            activeAttempt = null
        }
        observability.onStepAttemptCompleted(completed.runId, completed.stepName, completed.attemptId, workerId)
        prepareForCheckpoint(persistedCheckpoint)
    }

    suspend fun failAttempt(
        stepName: String,
        error: Throwable,
    ) {
        val attempt = synchronized(monitor) {
            activeAttempt?.takeIf { it.stepName == stepName }
        } ?: return
        // The persisted outputSummary must not carry raw persistence internals
        // (paths, SQL, payloads). Sanitize persistence-family failures; user
        // step-execution errors are not persistence internals and keep their
        // real message so the durable record stays diagnostically useful.
        val observableFailure = when {
            !error.isPersistenceFamilyFailure() -> error
            else -> safeWorkerObservableFailure(PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.SAVE, error)
        }
        val failed = attempt.copy(
            status = StepAttemptStatus.FAILED,
            completedAt = System.currentTimeMillis(),
            outputSummary = summarize(observableFailure),
        )
        stepAttemptStore.updateStepAttempt(failed)
        synchronized(monitor) {
            activeAttempt = null
        }
        observability.onStepAttemptFailed(
            failed.runId,
            failed.stepName,
            failed.attemptId,
            workerId,
            observableFailure,
        )
    }

    suspend fun failActiveAttempt(error: Throwable) {
        awaitObserverTransitions()
        val attempt = synchronized(monitor) { activeAttempt } ?: return
        failAttempt(attempt.stepName, error)
    }

    suspend fun cancelActiveAttempt(summary: String) {
        awaitObserverTransitions()
        val attempt = synchronized(monitor) { activeAttempt } ?: return
        val cancelled = attempt.copy(
            status = StepAttemptStatus.CANCELLED,
            completedAt = System.currentTimeMillis(),
            outputSummary = summary,
        )
        stepAttemptStore.updateStepAttempt(cancelled)
        synchronized(monitor) {
            activeAttempt = null
        }
    }

    fun enqueueFailedAttempt(
        stepName: String,
        error: Throwable,
    ) {
        enqueueObserverTransition {
            failAttempt(stepName, error)
        }
    }

    fun close() {
        observerScope.coroutineContext[Job]?.cancel()
    }

    private fun enqueueObserverTransition(
        block: suspend ExecutionTracker.() -> Unit,
    ) {
        synchronized(monitor) {
            val previous = observerTransitionTail
            observerTransitionTail = observerScope.async {
                previous.await().getOrThrow()
                try {
                    this@ExecutionTracker.block()
                    Result.success(Unit)
                } catch (error: Throwable) {
                    error.rethrowIfCancellation()
                    Result.failure(error)
                }
            }
        }
    }

    private suspend fun awaitObserverTransitions() {
        val tail = synchronized(monitor) { observerTransitionTail }
        tail.await().getOrThrow()
    }

    private fun summarize(error: Throwable): String =
        buildString {
            append(error::class.simpleName ?: error::class.java.simpleName)
            val message = error.message?.take(240)
            if (!message.isNullOrBlank()) {
                append(": ")
                append(message)
            }
        }
}

private class LeaseFencedCheckpointStore(
    private val delegate: WorkflowCheckpointStore,
    private val leaseStore: WorkflowLeaseStore,
    private val leaseProvider: () -> WorkflowLease?,
    private val tracker: ExecutionTracker,
    private val revisionSink: (Long?) -> Unit,
) : WorkflowCheckpointStore by delegate {
    private val leaseFence = leaseStore as? WorkflowLeaseCheckpointFence
        ?: throw IllegalArgumentException(
            "TramaiWorker requires a WorkflowLeaseStore that can atomically fence checkpoint mutations",
        )

    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint {
        val expectedLease = expectedLease(checkpoint.workflowName, checkpoint.workflowId)
        val persisted = leaseFence.saveCheckpointIfLeaseOwner(
            checkpointStore = delegate,
            checkpoint = checkpoint,
            expectedRevision = expectedRevision,
            expectedLease = expectedLease,
        )
        revisionSink(persisted.revision)
        tracker.completeAttempt(persisted)
        return persisted
    }

    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
    ) {
        val expectedLease = expectedLease(workflowName, workflowId)
        leaseFence.deleteCheckpointIfLeaseOwner(
            checkpointStore = delegate,
            workflowName = workflowName,
            workflowId = workflowId,
            expectedRevision = expectedRevision,
            expectedLease = expectedLease,
        )
    }

    override suspend fun requireRecovery(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        record: WorkflowRecoveryRecord,
    ): WorkflowCheckpoint {
        val expectedLease = expectedLease(workflowName, workflowId)
        val current = delegate.load(workflowName, workflowId)
            ?: throw WorkflowCheckpointConflictException("Cannot require recovery for '$workflowName'/'$workflowId': checkpoint does not exist")
        return leaseFence.saveCheckpointIfLeaseOwner(
            checkpointStore = delegate,
            checkpoint = current.copy(recoveryState = WorkflowRecoveryState.Required(record)),
            expectedRevision = expectedRevision,
            expectedLease = expectedLease,
        ).also { revisionSink(it.revision) }
    }

    private fun expectedLease(
        workflowName: String,
        workflowId: String,
    ): WorkflowLease = leaseProvider()
        ?: throw StaleWorkflowLeaseException(
            "Workflow '$workflowName' and workflowId='$workflowId' has no active lease for checkpoint mutation",
        )
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private suspend fun runCleanupPreservingCancellation(
    cancellation: CancellationException,
    cleanup: suspend () -> Unit,
) {
    try {
        cleanup()
    } catch (cleanupCancellation: CancellationException) {
        if (cleanupCancellation !== cancellation) {
            cancellation.addSuppressed(cleanupCancellation)
        }
    } catch (cleanupError: Exception) {
        // Required as the first statement by the cancellation scanner.
        cleanupError.rethrowIfCancellation()
        cancellation.addSuppressed(cleanupError)
    }
}
