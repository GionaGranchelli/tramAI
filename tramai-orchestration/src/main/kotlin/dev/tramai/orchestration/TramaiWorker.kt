package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface TramaiWorkerObserver {
    fun onWorkerStarted(workerId: String) = Unit

    fun onWorkerStopped(workerId: String) = Unit

    fun onLeaseAcquired(workflowId: String, workerId: String) = Unit

    fun onLeaseReleased(workflowId: String, workerId: String) = Unit

    fun onLeaseExpired(workflowId: String, workerId: String) = Unit

    fun onLeaseRenewalFailed(
        workflowId: String,
        workerId: String,
        error: Throwable,
    ) = Unit

    fun onLeaseReleaseFailed(
        workflowId: String,
        workerId: String,
        error: Throwable,
    ) = Unit

    fun onPollFailed(
        workerId: String,
        error: Throwable,
    ) = Unit

    fun onWorkTakenOver(
        workflowId: String,
        previousWorkerId: String,
        newWorkerId: String,
    ) = Unit

    fun onUnknownAttempt(
        runId: String,
        stepName: String,
        priorWorkerId: String,
        attemptTime: Long,
    ) = Unit

    fun onStepAttemptStarted(
        runId: String,
        stepName: String,
        attemptId: String,
        workerId: String,
    ) = Unit

    fun onStepAttemptCompleted(
        runId: String,
        stepName: String,
        attemptId: String,
        workerId: String,
    ) = Unit

    fun onStepAttemptFailed(
        runId: String,
        stepName: String,
        attemptId: String,
        workerId: String,
        error: Throwable,
    ) = Unit

    fun onShutdownStarted(workerId: String) = Unit

    fun onDrainProgress(workerId: String, done: Int, pending: Int) = Unit

    fun onShutdownComplete(workerId: String) = Unit

    fun onWorkerHeartbeat(workerId: String, uptimeMillis: Long, claimedCount: Int) = Unit

    fun onLeaseRenewed(workflowId: String, workerId: String, newExpiry: Long) = Unit

    fun onLeaseContested(workflowId: String, claimantWorkerId: String, currentWorkerId: String) = Unit

    fun onWorkflowAbandoned(workflowId: String, workerId: String, lastStep: String?, timeoutMillis: Long) = Unit
}

object NoOpTramaiWorkerObserver : TramaiWorkerObserver

class StaleWorkflowLeaseException(
    message: String,
) : RuntimeException(message)

internal data class WorkerWorkflowBinding<S, R>(
    val workflow: Workflow<S, R>,
    val stateCodec: WorkflowStateCodec<S>,
    val delayWakeupScheduler: WorkflowDelayWakeupScheduler? = null,
    val deleteCheckpointOnCompletion: Boolean = true,
) {
    suspend fun replayDescriptor(
        checkpoint: WorkflowCheckpoint,
        context: WorkflowContext,
    ): WorkflowStepReplayDescriptor? = workflow.replayDescriptorAt(
        stepIndex = checkpoint.nextStepIndex,
        state = stateCodec.decode(checkpoint.statePayload),
        context = context,
    )
}

private object WorkerWorkflowBindings {
    private val bindings = ConcurrentHashMap<String, WorkerWorkflowBinding<*, *>>()

    fun <S, R> remember(
        workflow: Workflow<S, R>,
        stateCodec: WorkflowStateCodec<S>,
        delayWakeupScheduler: WorkflowDelayWakeupScheduler?,
        deleteCheckpointOnCompletion: Boolean,
    ) {
        bindings[workflow.name] = WorkerWorkflowBinding(
            workflow = workflow,
            stateCodec = stateCodec,
            delayWakeupScheduler = delayWakeupScheduler,
            deleteCheckpointOnCompletion = deleteCheckpointOnCompletion,
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun <S, R> bindingFor(workflow: Workflow<S, R>): WorkerWorkflowBinding<S, R>? =
        bindings[workflow.name] as WorkerWorkflowBinding<S, R>?
}

fun <S, R> Workflow<S, R>.registerWorkerBinding(
    stateCodec: WorkflowStateCodec<S>,
    delayWakeupScheduler: WorkflowDelayWakeupScheduler? = null,
    deleteCheckpointOnCompletion: Boolean = true,
): Workflow<S, R> = also {
    WorkerWorkflowBindings.remember(
        workflow = this,
        stateCodec = stateCodec,
        delayWakeupScheduler = delayWakeupScheduler,
        deleteCheckpointOnCompletion = deleteCheckpointOnCompletion,
    )
}

internal fun <S, R> rememberWorkerWorkflowBinding(
    workflow: Workflow<S, R>,
    persistence: WorkflowPersistence<S>,
) {
    WorkerWorkflowBindings.remember(
        workflow = workflow,
        stateCodec = persistence.stateCodec,
        delayWakeupScheduler = persistence.delayWakeupScheduler,
        deleteCheckpointOnCompletion = persistence.deleteCheckpointOnCompletion,
    )
}

class TramaiWorker(
    private val config: WorkerConfig,
    private val leaseStore: WorkflowLeaseStore,
    private val checkpointStore: WorkflowCheckpointStore,
    private val checkpointCatalog: WorkflowCheckpointCatalog,
    private val stepAttemptStore: StepAttemptRecordStore,
    private val workflowRegistry: Map<String, Workflow<*, *>>,
    private val observability: TramaiWorkerObserver = NoOpTramaiWorkerObserver,
    private val partitionStrategy: PartitionAssignmentStrategy = ModHashPartitionStrategy(),
) : AutoCloseable {
    private val workerRegistryStore = leaseStore as? WorkerRegistryStore
    private val activeExecutions = ConcurrentHashMap<String, ActiveExecution>()
    private val executionFailures = ConcurrentHashMap<String, Throwable>()
    private val shutdownStarted = AtomicBoolean(false)
    private var workerScope: CoroutineScope? = null
    private var workerJob: Job? = null
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null
    @Volatile
    private var shutdownHook: Thread? = null

    @Volatile
    private var acceptingWork: Boolean = false

    @Volatile
    private var shuttingDownGracefully: Boolean = false

    private var startedAt: Long = 0L

    constructor(
        config: WorkerConfig,
        leaseStore: WorkflowLeaseStore,
        checkpointStore: WorkflowCheckpointStore,
        workflowRegistry: Map<String, Workflow<*, *>>,
        observability: TramaiWorkerObserver = NoOpTramaiWorkerObserver,
        partitionStrategy: PartitionAssignmentStrategy = ModHashPartitionStrategy(),
    ) : this(
        config = config,
        leaseStore = leaseStore,
        checkpointStore = checkpointStore,
        checkpointCatalog = checkpointStore as? WorkflowCheckpointCatalog
            ?: throw IllegalArgumentException(
                "TramaiWorker requires a WorkflowCheckpointCatalog when checkpointStore does not implement it directly",
            ),
        stepAttemptStore = checkpointStore as? StepAttemptRecordStore
            ?: throw IllegalArgumentException(
                "TramaiWorker requires a StepAttemptRecordStore when checkpointStore does not implement it directly",
            ),
        workflowRegistry = workflowRegistry,
        observability = observability,
        partitionStrategy = partitionStrategy,
    )

    suspend fun start() {
        if (workerJob != null) {
            return
        }
        shutdownStarted.set(false)
        shuttingDownGracefully = false
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(supervisor + Dispatchers.Default)
        workerScope = scope
        workerJob = supervisor
        startedAt = System.currentTimeMillis()
        registerWorker()
        observability.onWorkerStarted(config.workerId)
        val hook = Thread {
            runBlocking(Dispatchers.IO) {
                shutdown()
            }
        }
        Runtime.getRuntime().addShutdownHook(hook)
        shutdownHook = hook
        acceptingWork = true
        heartbeatJob = scope.launch {
            heartbeatLoop()
        }
        pollJob = scope.launch {
            pollLoop()
        }
    }

    fun crash(cause: CancellationException = CancellationException("Worker '${config.workerId}' crashed")) {
        workerJob?.cancel(cause)
    }

    suspend fun shutdown() {
        val supervisor = workerJob ?: return
        if (!shutdownStarted.compareAndSet(false, true)) {
            return
        }
        shuttingDownGracefully = true
        acceptingWork = false
        observability.onShutdownStarted(config.workerId)
        pollJob?.cancelAndJoin()
        val executions = activeExecutions.values.toList()
        val drainStartedAt = System.currentTimeMillis()
        val drainTimeoutMillis = config.drainTimeoutMillis
        val drained = withTimeoutOrNull(drainTimeoutMillis) {
            executions.mapNotNull { it.executionJob }.joinAll()
            true
        } ?: false
        if (!drained) {
            executions.forEach { execution ->
                execution.executionJob?.cancel(CancellationException("Worker drain timeout exceeded"))
            }
            val residualTimeoutMillis = (drainTimeoutMillis - (System.currentTimeMillis() - drainStartedAt)).coerceAtLeast(1L)
            withTimeoutOrNull(residualTimeoutMillis) {
                executions.mapNotNull { it.executionJob }.joinAll()
            }
        }
        val executionCount = executions.size - activeExecutions.size
        val executionsLeft = activeExecutions.size
        observability.onDrainProgress(config.workerId, done = executionCount, pending = executionsLeft)
        shutdownHook?.let { hook ->
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (_: IllegalStateException) {
                // JVM is already shutting down - removal is not allowed during shutdown
            }
            shutdownHook = null
        }
        heartbeatJob?.cancelAndJoin()
        withTimeoutOrNull(config.drainTimeoutMillis) {
            runCatching { workerRegistryStore?.unregisterWorker(config.workerId) }
        }
        observability.onShutdownComplete(config.workerId)
        observability.onWorkerStopped(config.workerId)
        supervisor.cancel()
        workerJob = null
        workerScope = null
        pollJob = null
        heartbeatJob = null
    }

    override fun close() {
        runBlocking {
            shutdown()
        }
    }

    fun latestFailure(workflowId: String): Throwable? = executionFailures[workflowId]

    private suspend fun registerWorker() {
        workerRegistryStore?.registerWorker(
            workerId = config.workerId,
            poolName = config.poolName,
            version = workerVersion(),
            capabilityLabels = config.capabilityLabels,
            host = workerHost(),
        )
    }

    private suspend fun heartbeatLoop() {
        val interval = maxOf(1L, config.pollIntervalMillis / 2)
        while (currentCoroutineContext().isActive) {
            val uptime = System.currentTimeMillis() - startedAt
            workerRegistryStore?.updateHeartbeat(config.workerId)
            observability.onWorkerHeartbeat(config.workerId, uptime, activeExecutions.size)
            delay(interval)
        }
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive && acceptingWork) {
            try {
                val checkpoints = checkpointCatalog.listCheckpoints()
                    .sortedWith(compareBy<WorkflowCheckpoint>({ it.workflowName }, { it.workflowId }))
                checkpoints.forEach { checkpoint -> processCheckpoint(checkpoint) }
                delay(config.pollIntervalMillis)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                observability.onPollFailed(config.workerId, error)
                delay(maxOf(100L, config.pollIntervalMillis))
            }
        }
    }

    private suspend fun processCheckpoint(checkpoint: WorkflowCheckpoint) {
        if (!acceptingWork || activeExecutions.containsKey(checkpoint.workflowId)) return
        if (!ownsPartition(checkpoint.workflowId)) return
        if (leaseStore.currentLease(checkpoint.workflowName, checkpoint.workflowId) != null) return
        if (checkpoint.recoveryState is WorkflowRecoveryState.Required) {
            // Blocked workflow awaiting operator resolution — skip silently. The
            // unknown-attempt event is already emitted once when the attempt is first
            // detected (recoverAttemptIfNeeded), not on every poll cycle.
            return
        }

        val lease = try {
            leaseStore.claim(
                workflowName = checkpoint.workflowName,
                workflowId = checkpoint.workflowId,
                ownerId = config.workerId,
                checkpointRevision = checkpoint.revision,
                leaseDurationMillis = config.leaseDurationMillis,
            )
        } catch (_: WorkflowLeaseConflictException) {
            val currentLease = leaseStore.currentLease(checkpoint.workflowName, checkpoint.workflowId)
            if (currentLease != null) {
                observability.onLeaseContested(checkpoint.workflowId, config.workerId, currentLease.ownerId)
            }
            null
        }
        if (lease != null) {
            observability.onLeaseAcquired(checkpoint.workflowId, config.workerId)
            launchExecution(checkpoint, lease)
        }
    }

    private fun launchExecution(
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
            renewLeaseLoop(handle)
        }
    }

    private suspend fun executeClaimedWorkflow(
        checkpoint: WorkflowCheckpoint,
        handle: ActiveExecution,
    ) {
        val workflow = workflowRegistry[checkpoint.workflowName] ?: run {
            releaseLease(handle)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val typedWorkflow = workflow as Workflow<Any?, Any?>
        val binding = WorkerWorkflowBindings.bindingFor(typedWorkflow)
            ?: throw IllegalStateException(
                "Workflow '${typedWorkflow.name}' is missing a worker binding. Register it with registerWorkerBinding() or run it once with WorkflowPersistence before using TramaiWorker.",
            )
        val context = WorkflowContext(workflowId = checkpoint.workflowId)
        val tracker = ExecutionTracker(
            workerId = config.workerId,
            workflow = typedWorkflow,
            binding = binding,
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
                    StepAttemptResolutionAction.RETRY_APPROVED -> consumeRetryApproval(
                        checkpoint = checkpoint,
                        handle = handle,
                        tracker = tracker,
                        fencedCheckpointStore = fencedCheckpointStore,
                        attempt = unknownAttempt,
                    )
                    StepAttemptResolutionAction.WORKFLOW_FAILED -> throw WorkflowRecoveryStateException(
                        "Attempt '${unknownAttempt.attemptId}' is resolved as WORKFLOW_FAILED and cannot be executed",
                    )
                    null -> recoverUnknownAttempt(checkpoint, tracker, fencedCheckpointStore, unknownAttempt)
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
            stateCodec = binding.stateCodec,
            delayWakeupScheduler = binding.delayWakeupScheduler,
            deleteCheckpointOnCompletion = binding.deleteCheckpointOnCompletion,
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
            if (shuttingDownGracefully) {
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

    private suspend fun recoverUnknownAttempt(
        checkpoint: WorkflowCheckpoint,
        tracker: ExecutionTracker,
        fencedCheckpointStore: WorkflowCheckpointStore,
        unknownAttempt: StepAttemptRecord,
    ) {
        when (unknownAttempt.replayPolicy) {
                ReplayPolicy.NON_REPLAYABLE -> {
                    fencedCheckpointStore.requireRecovery(
                        workflowName = checkpoint.workflowName,
                        workflowId = checkpoint.workflowId,
                        expectedRevision = checkpoint.revision,
                        record = WorkflowRecoveryRecord(
                            reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                            stepName = unknownAttempt.stepName,
                            attemptId = unknownAttempt.attemptId,
                            priorWorkerId = unknownAttempt.workerId,
                            detectedAtEpochMillis = unknownAttempt.startedAt,
                            idempotencyKey = unknownAttempt.idempotencyKey,
                        ),
                    )
                    throw NonReplayableStepStateUnknownException(
                        runId = unknownAttempt.runId,
                        stepName = unknownAttempt.stepName,
                        priorWorkerId = unknownAttempt.workerId,
                        attemptTime = unknownAttempt.startedAt,
                    )
                }

                ReplayPolicy.EXTERNALLY_IDEMPOTENT -> {
                    val storedKey = unknownAttempt.idempotencyKey
                    if (storedKey.isNullOrBlank()) {
                        fencedCheckpointStore.requireRecovery(
                            workflowName = checkpoint.workflowName,
                            workflowId = checkpoint.workflowId,
                            expectedRevision = checkpoint.revision,
                            record = WorkflowRecoveryRecord(
                                reason = WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING,
                                stepName = unknownAttempt.stepName,
                                attemptId = unknownAttempt.attemptId,
                                priorWorkerId = unknownAttempt.workerId,
                                detectedAtEpochMillis = unknownAttempt.startedAt,
                            ),
                        )
                        throw NonReplayableStepStateUnknownException(
                            runId = unknownAttempt.runId,
                            stepName = unknownAttempt.stepName,
                            priorWorkerId = unknownAttempt.workerId,
                            attemptTime = unknownAttempt.startedAt,
                            recoveryInstructions = "The prior attempt requires a stable idempotency key for replay, but no key was recorded. Investigate the external system before resuming.",
                        )
                    }
                    val currentKey = tracker.currentStepReplayDescriptor()?.idempotencyKey
                    if (currentKey != storedKey) {
                        fencedCheckpointStore.requireRecovery(
                            workflowName = checkpoint.workflowName,
                            workflowId = checkpoint.workflowId,
                            expectedRevision = checkpoint.revision,
                            record = WorkflowRecoveryRecord(
                                reason = WorkflowRecoveryReason.IDEMPOTENCY_KEY_MISMATCH,
                                stepName = unknownAttempt.stepName,
                                attemptId = unknownAttempt.attemptId,
                                priorWorkerId = unknownAttempt.workerId,
                                detectedAtEpochMillis = unknownAttempt.startedAt,
                                idempotencyKey = storedKey,
                            ),
                        )
                        throw NonReplayableStepStateUnknownException(
                            runId = unknownAttempt.runId,
                            stepName = unknownAttempt.stepName,
                            priorWorkerId = unknownAttempt.workerId,
                            attemptTime = unknownAttempt.startedAt,
                            recoveryInstructions = "The idempotency key computed for the current workflow definition (${currentKey ?: "<none>"}) differs from the key recorded by the prior attempt ($storedKey). Resolve the mismatch before resuming.",
                        )
                    }
                }

                ReplayPolicy.PURE,
                ReplayPolicy.IDEMPOTENT,
                -> Unit
        }
    }

    private suspend fun consumeRetryApproval(
        checkpoint: WorkflowCheckpoint,
        handle: ActiveExecution,
        tracker: ExecutionTracker,
        fencedCheckpointStore: WorkflowCheckpointStore,
        attempt: StepAttemptRecord,
    ) {
        val expectedLease = handle.lease.get()
            ?: throw StaleWorkflowLeaseException(
                "Workflow '${checkpoint.workflowName}' and workflowId='${checkpoint.workflowId}' has no active lease for retry-approval consumption",
            )
        val currentLease = leaseStore.currentLease(checkpoint.workflowName, checkpoint.workflowId)
        if (currentLease?.leaseId != expectedLease.leaseId || currentLease.ownerId != expectedLease.ownerId) {
            throw StaleWorkflowLeaseException(
                "Workflow '${checkpoint.workflowName}' and workflowId='${checkpoint.workflowId}' lease was lost before retry-approval consumption",
            )
        }
        when (attempt.replayPolicy) {
            ReplayPolicy.NON_REPLAYABLE -> tracker.consumeRetryApproval(attempt)
            ReplayPolicy.EXTERNALLY_IDEMPOTENT -> {
                val approvedKey = attempt.approvedIdempotencyKey
                val currentKey = tracker.currentStepReplayDescriptor()?.idempotencyKey
                if (approvedKey.isNullOrBlank() || currentKey != approvedKey) {
                    // Void the stale approval so the operator can issue a fresh key-bound
                    // approval matching the current definition. The attempt stays UNKNOWN
                    // (never consumed, never authorized execution); the resolution reason
                    // and timestamp remain as the audit trail of the rejected approval.
                    stepAttemptStore.updateStepAttempt(
                        attempt.copy(
                            resolutionAction = null,
                            approvedIdempotencyKey = null,
                        ),
                    )
                    fencedCheckpointStore.requireRecovery(
                        workflowName = checkpoint.workflowName,
                        workflowId = checkpoint.workflowId,
                        expectedRevision = checkpoint.revision,
                        record = WorkflowRecoveryRecord(
                            reason = WorkflowRecoveryReason.IDEMPOTENCY_KEY_MISMATCH,
                            stepName = attempt.stepName,
                            attemptId = attempt.attemptId,
                            priorWorkerId = attempt.workerId,
                            detectedAtEpochMillis = attempt.startedAt,
                            idempotencyKey = approvedKey,
                            instructions = "The workflow definition changed after operator approval: approved key " +
                                "${approvedKey ?: "<none>"}, current key ${currentKey ?: "<none>"}. The stale approval " +
                                "was voided; issue a new key-bound retryStep approval with a key matching the current " +
                                "definition, or use failWorkflow.",
                        ),
                    )
                    throw NonReplayableStepStateUnknownException(
                        runId = attempt.runId,
                        stepName = attempt.stepName,
                        priorWorkerId = attempt.workerId,
                        attemptTime = attempt.startedAt,
                        recoveryInstructions = "The operator-approved idempotency key (${approvedKey ?: "<none>"}) " +
                            "differs from the current workflow definition (${currentKey ?: "<none>"}). The stale " +
                            "approval was voided; obtain a new key-bound approval matching the current definition, " +
                            "or use failWorkflow.",
                    )
                }
                tracker.consumeRetryApproval(attempt)
            }
            ReplayPolicy.PURE,
            ReplayPolicy.IDEMPOTENT,
            -> throw WorkflowRecoveryStateException(
                "Retry approval on attempt '${attempt.attemptId}' has unsupported replay policy ${attempt.replayPolicy}",
            )
        }
    }

    private suspend fun renewLeaseLoop(handle: ActiveExecution) {
        val interval = maxOf(1L, config.leaseDurationMillis / 2)
        var nextDelayMillis = interval
        while (currentCoroutineContext().isActive) {
            delay(nextDelayMillis)
            val currentLease = handle.lease.get() ?: return
            val renewed = try {
                leaseStore.renew(
                    lease = currentLease,
                    checkpointRevision = handle.lastRevision.get(),
                    leaseDurationMillis = config.leaseDurationMillis,
                )
            } catch (_: WorkflowLeaseConflictException) {
                observability.onLeaseExpired(handle.workflowId, config.workerId)
                handle.lease.set(null)
                handle.executionJob?.cancel(CancellationException("Workflow lease for '${handle.workflowId}' was lost"))
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                observability.onLeaseRenewalFailed(handle.workflowId, config.workerId, error)
                nextDelayMillis = maxOf(50L, interval / 2)
                continue
            }
            handle.lease.set(renewed)
            observability.onLeaseRenewed(handle.workflowId, config.workerId, renewed.expiresAtEpochMillis)
            nextDelayMillis = interval
        }
    }

    private suspend fun releaseLease(handle: ActiveExecution) {
        val lease = handle.lease.get() ?: return
        try {
            leaseStore.release(lease)
            handle.lease.compareAndSet(lease, null)
            observability.onLeaseReleased(handle.workflowId, config.workerId)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            observability.onLeaseReleaseFailed(handle.workflowId, config.workerId, error)
        }
    }

    private suspend fun ownsPartition(workflowId: String): Boolean {
        if (!config.partitionEnabled) {
            return true
        }
        val activeWorkers = workerRegistryStore?.listActiveWorkers()
            ?.filter { it.poolName == config.poolName }
            ?.sortedBy { it.workerId }
            ?.map { it.workerId }
            .orEmpty()
        return partitionStrategy.ownsPartition(workflowId, config.workerId, activeWorkers)
    }

    private fun workerHost(): String = runCatching {
        InetAddress.getLocalHost().hostName
    }.getOrDefault("unknown")

    private fun workerVersion(): String = TramaiWorker::class.java.`package`?.implementationVersion ?: "dev"
}

private data class ActiveExecution(
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

private class ExecutionTracker(
    private val workerId: String,
    private val workflow: Workflow<Any?, Any?>,
    private val binding: WorkerWorkflowBinding<Any?, Any?>,
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
        stepAttemptStore.updateStepAttempt(
            attempt.copy(
                status = StepAttemptStatus.FAILED,
                completedAt = attempt.completedAt ?: System.currentTimeMillis(),
            ),
        )
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
        val failed = attempt.copy(
            status = StepAttemptStatus.FAILED,
            completedAt = System.currentTimeMillis(),
            outputSummary = summarize(error),
        )
        stepAttemptStore.updateStepAttempt(failed)
        synchronized(monitor) {
            activeAttempt = null
        }
        observability.onStepAttemptFailed(failed.runId, failed.stepName, failed.attemptId, workerId, error)
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
