package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

interface TramaiWorkerObserver {
    fun onWorkerStarted(workerId: String) = Unit

    fun onWorkerStopped(workerId: String) = Unit

    fun onLeaseAcquired(workflowId: String, workerId: String) = Unit

    fun onLeaseReleased(workflowId: String, workerId: String) = Unit

    fun onLeaseExpired(workflowId: String, workerId: String) = Unit

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
    private val workflowRegistry: Map<String, Workflow<*, *>>,
    private val observability: TramaiWorkerObserver = NoOpTramaiWorkerObserver,
) : AutoCloseable {
    private val checkpointCatalog = checkpointStore as? WorkflowCheckpointCatalog
        ?: throw IllegalArgumentException(
            "TramaiWorker requires a WorkflowCheckpointStore that also implements WorkflowCheckpointCatalog",
        )
    private val stepAttemptStore = checkpointStore as? StepAttemptRecordStore
        ?: throw IllegalArgumentException(
            "TramaiWorker requires a WorkflowCheckpointStore that also implements StepAttemptRecordStore",
        )
    private val workerRegistryStore = leaseStore as? WorkerRegistryStore
    private val activeExecutions = ConcurrentHashMap<String, ActiveExecution>()
    private val executionFailures = ConcurrentHashMap<String, Throwable>()
    private var workerScope: CoroutineScope? = null
    private var workerJob: Job? = null
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile
    private var acceptingWork: Boolean = false

    @Volatile
    private var shuttingDownGracefully: Boolean = false

    suspend fun start() {
        if (workerJob != null) {
            return
        }
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(supervisor + Dispatchers.Default)
        workerScope = scope
        workerJob = supervisor
        registerWorker()
        observability.onWorkerStarted(config.workerId)
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
        if (workerJob == null || shuttingDownGracefully) {
            return
        }
        shuttingDownGracefully = true
        acceptingWork = false
        pollJob?.cancelAndJoin()
        val executions = activeExecutions.values.toList()
        val drained = withTimeoutOrNull(config.drainTimeoutMillis) {
            executions.mapNotNull { it.executionJob }.joinAll()
            true
        } ?: false
        if (!drained) {
            executions.forEach { execution ->
                execution.executionJob?.cancel(CancellationException("Worker drain timeout exceeded"))
            }
            executions.mapNotNull { it.executionJob }.joinAll()
        }
        heartbeatJob?.cancelAndJoin()
        workerRegistryStore?.unregisterWorker(config.workerId)
        observability.onWorkerStopped(config.workerId)
        workerJob?.cancel()
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
            workerRegistryStore?.updateHeartbeat(config.workerId)
            delay(interval)
        }
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive && acceptingWork) {
            val checkpoints = checkpointCatalog.listCheckpoints()
                .sortedWith(compareBy<WorkflowCheckpoint>({ it.workflowName }, { it.workflowId }))
            checkpoints.forEach { checkpoint ->
                if (!acceptingWork || activeExecutions.containsKey(checkpoint.workflowId)) {
                    return@forEach
                }
                if (!ownsPartition(checkpoint.workflowId)) {
                    return@forEach
                }
                if (leaseStore.currentLease(checkpoint.workflowName, checkpoint.workflowId) != null) {
                    return@forEach
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
                    null
                }
                if (lease != null) {
                    observability.onLeaseAcquired(checkpoint.workflowId, config.workerId)
                    launchExecution(checkpoint, lease)
                }
            }
            delay(config.pollIntervalMillis)
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

        val unknownAttempt = tracker.recoverAttemptIfNeeded(checkpoint)
        if (unknownAttempt != null) {
            observability.onLeaseExpired(checkpoint.workflowId, unknownAttempt.workerId)
            observability.onWorkTakenOver(
                workflowId = checkpoint.workflowId,
                previousWorkerId = unknownAttempt.workerId,
                newWorkerId = config.workerId,
            )
            when (unknownAttempt.replayPolicy) {
                ReplayPolicy.NON_REPLAYABLE -> {
                    throw NonReplayableStepStateUnknownException(
                        runId = unknownAttempt.runId,
                        stepName = unknownAttempt.stepName,
                        priorWorkerId = unknownAttempt.workerId,
                        attemptTime = unknownAttempt.startedAt,
                    )
                }

                ReplayPolicy.EXTERNALLY_IDEMPOTENT -> {
                    if (unknownAttempt.idempotencyKey.isNullOrBlank()) {
                        throw NonReplayableStepStateUnknownException(
                            runId = unknownAttempt.runId,
                            stepName = unknownAttempt.stepName,
                            priorWorkerId = unknownAttempt.workerId,
                            attemptTime = unknownAttempt.startedAt,
                            recoveryInstructions = "The prior attempt requires a stable idempotency key for replay, but no key was recorded. Investigate the external system before resuming.",
                        )
                    }
                }

                ReplayPolicy.PURE,
                ReplayPolicy.IDEMPOTENT,
                -> Unit
            }
        }

        val fencedCheckpointStore = LeaseFencedCheckpointStore(
            delegate = checkpointStore,
            leaseStore = leaseStore,
            leaseProvider = { handle.lease.get() },
            tracker = tracker,
            revisionSink = { handle.lastRevision.set(it) },
        )
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
                tracker.cancelActiveAttempt("Worker shutdown cancelled the running step")
                releaseLease(handle)
                executionFailures.remove(checkpoint.workflowId)
            }
            throw error
        } catch (error: Throwable) {
            executionFailures[checkpoint.workflowId] = error
            tracker.failActiveAttempt(error)
            releaseLease(handle)
            throw error
        }
    }

    private suspend fun renewLeaseLoop(handle: ActiveExecution) {
        val interval = maxOf(1L, config.leaseDurationMillis / 2)
        while (currentCoroutineContext().isActive) {
            delay(interval)
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
            }
            handle.lease.set(renewed)
        }
    }

    private suspend fun releaseLease(handle: ActiveExecution) {
        val lease = handle.lease.getAndSet(null) ?: return
        runCatching {
            leaseStore.release(lease)
        }
        observability.onLeaseReleased(handle.workflowId, config.workerId)
    }

    private suspend fun ownsPartition(workflowId: String): Boolean {
        if (!config.partitionEnabled) {
            return true
        }
        val workerIndex = workerPartitionIndex()
        val partition = (stableHash(workflowId) % config.workerCount.toLong()).toInt()
        return partition == workerIndex
    }

    private suspend fun workerPartitionIndex(): Int {
        config.workerId.substringAfterLast('-').toIntOrNull()?.let { numericSuffix ->
            return numericSuffix.mod(config.workerCount)
        }
        val activeWorkers = workerRegistryStore?.listActiveWorkers()
            ?.filter { it.poolName == config.poolName }
            ?.sortedBy { it.workerId }
            .orEmpty()
        val index = activeWorkers.indexOfFirst { it.workerId == config.workerId }
        return if (index >= 0) {
            index.mod(config.workerCount)
        } else {
            0
        }
    }

    private fun workerHost(): String = runCatching {
        InetAddress.getLocalHost().hostName
    }.getOrDefault("unknown")

    private fun workerVersion(): String = TramaiWorker::class.java.`package`?.implementationVersion ?: "dev"

    private fun stableHash(workflowId: String): Long {
        val bytes = MessageDigest.getInstance("SHA-256").digest(workflowId.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(bytes.copyOfRange(0, Long.SIZE_BYTES)).long and Long.MAX_VALUE
    }
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
            runBlocking {
                tracker.startAttempt(stepName)
            }
        }
    }

    override fun onStepFailed(
        workflowName: String,
        stepName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        if (this.workflowName == workflowName) {
            runBlocking {
                tracker.failAttempt(stepName, error)
            }
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

    suspend fun completeAttempt(
        persistedCheckpoint: WorkflowCheckpoint,
    ) {
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
        val attempt = synchronized(monitor) { activeAttempt } ?: return
        failAttempt(attempt.stepName, error)
    }

    suspend fun cancelActiveAttempt(summary: String) {
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
) : WorkflowCheckpointStore {
    override suspend fun load(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint? = delegate.load(workflowName, workflowId)

    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint {
        validateLease(checkpoint.workflowName, checkpoint.workflowId)
        val persisted = delegate.save(checkpoint, expectedRevision)
        revisionSink(persisted.revision)
        tracker.completeAttempt(persisted)
        return persisted
    }

    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
    ) {
        validateLease(workflowName, workflowId)
        delegate.delete(workflowName, workflowId, expectedRevision)
    }

    private suspend fun validateLease(
        workflowName: String,
        workflowId: String,
    ) {
        val expected = leaseProvider()
            ?: throw StaleWorkflowLeaseException(
                "Workflow '$workflowName' and workflowId='$workflowId' has no active lease for checkpoint mutation",
            )
        val current = leaseStore.currentLease(workflowName, workflowId)
            ?: throw StaleWorkflowLeaseException(
                "Workflow '$workflowName' and workflowId='$workflowId' lease '${expected.leaseId}' is no longer active",
            )
        if (current.leaseId != expected.leaseId || current.ownerId != expected.ownerId) {
            throw StaleWorkflowLeaseException(
                "Workflow '$workflowName' and workflowId='$workflowId' is now fenced by lease '${current.leaseId}' owned by '${current.ownerId}'",
            )
        }
    }
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
