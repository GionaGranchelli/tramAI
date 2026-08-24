package dev.tramai.orchestration

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
/**
 * Active ownership claim for one workflow execution.
 */
data class WorkflowLease(
    val workflowName: String,
    val workflowId: String,
    val leaseId: String,
    val ownerId: String,
    val checkpointRevision: Long?,
    val acquiredAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)
/**
 * Lease settings used when a workflow needs active ownership in a multi-node environment.
 */
data class WorkflowLeasePolicy(
    val ownerId: String,
    val leaseDurationMillis: Long = 30_000,
) {
    init {
        require(ownerId.isNotBlank()) { "WorkflowLeasePolicy.ownerId must not be blank" }
        require(leaseDurationMillis > 0) { "WorkflowLeasePolicy.leaseDurationMillis must be greater than zero" }
    }
}
/**
 * Optional coordination SPI for workflows that need active ownership rather than revision checks alone.
 */
interface WorkflowLeaseStore {
    suspend fun currentLease(
        workflowName: String,
        workflowId: String,
    ): WorkflowLease?
    suspend fun claim(
        workflowName: String,
        workflowId: String,
        ownerId: String,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease
    suspend fun renew(
        lease: WorkflowLease,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease
    suspend fun release(lease: WorkflowLease)
}

/**
 * Optional coordination SPI that can fence a checkpoint mutation against an active lease atomically.
 */
interface WorkflowLeaseCheckpointFence {
    suspend fun saveCheckpointIfLeaseOwner(
        checkpointStore: WorkflowCheckpointStore,
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
        expectedLease: WorkflowLease,
    ): WorkflowCheckpoint

    suspend fun deleteCheckpointIfLeaseOwner(
        checkpointStore: WorkflowCheckpointStore,
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
        expectedLease: WorkflowLease,
    )
}
/**
 * Raised when a workflow cannot claim or renew active ownership because another executor holds the lease.
 */
class WorkflowLeaseConflictException(
    message: String,
) : RuntimeException(message) {
    var failureCode: PersistenceFailureCode? = null
        internal set
    var safeFactoryTrusted: Boolean = false
        internal set
}

/**
 * Caller-input validation for lease operations, enforced OUTSIDE the
 * persistence boundary: these are caller errors (IllegalArgumentException),
 * not persistence failures. Mirrors the WorkflowLeasePolicy runtime
 * invariants (nonblank ownership, positive duration).
 */
internal fun validateLeaseIdentityInput(
    workflowName: String,
    workflowId: String,
    ownerId: String,
    leaseDurationMillis: Long,
) {
    require(workflowName.isNotBlank()) { "workflowName must not be blank" }
    require(workflowId.isNotBlank()) { "workflowId must not be blank" }
    require(ownerId.isNotBlank()) { "ownerId must not be blank" }
    require(leaseDurationMillis > 0) { "leaseDurationMillis must be greater than zero" }
}

internal fun validateLeaseToken(lease: WorkflowLease) {
    require(lease.leaseId.isNotBlank()) { "leaseId must not be blank" }
}

/**
 * The fence binds the expected lease identity to the checkpoint identity:
 * a lease for workflow A must never authorize a mutation of workflow B.
 * Framework/caller misuse — IllegalArgumentException, before touching
 * storage, with no checkpoint or lease mutation.
 */
internal fun validateFenceIdentity(
    expectedLease: WorkflowLease,
    workflowName: String,
    workflowId: String,
) {
    require(expectedLease.workflowName == workflowName && expectedLease.workflowId == workflowId) {
        "Fenced checkpoint identity ($workflowName, $workflowId) must match the lease identity " +
            "(${expectedLease.workflowName}, ${expectedLease.workflowId})"
    }
}
/**
 * Simple in-memory lease store for tests and lightweight local use.
 */
class InMemoryWorkflowLeaseStore(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : WorkflowLeaseStore, WorkflowLeaseCheckpointFence, WorkerRegistryStore {
    var persistenceFailureDiagnosticObserver: PersistenceFailureDiagnosticObserver =
        NoOpPersistenceFailureDiagnosticObserver
        internal set

    constructor(
        clockMillis: () -> Long,
        observer: PersistenceFailureDiagnosticObserver,
    ) : this(clockMillis) {
        persistenceFailureDiagnosticObserver = observer
    }

    private val leases = linkedMapOf<LeaseKey, WorkflowLease>()
    private val workers = linkedMapOf<String, WorkerRegistryRecord>()
    private val monitor = Any()
    private val leaseMutex = Mutex()

    override suspend fun currentLease(
        workflowName: String,
        workflowId: String,
    ): WorkflowLease? = persistenceBoundary(
        PersistenceResourceKind.LEASE, PersistenceOperation.LOAD, persistenceFailureDiagnosticObserver,
    ) { leaseMutex.withLock {
        synchronized(monitor) {
            activeLease(workflowName, workflowId)
        }
    } }

    override suspend fun claim(
        workflowName: String,
        workflowId: String,
        ownerId: String,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease {
        validateLeaseIdentityInput(workflowName, workflowId, ownerId, leaseDurationMillis)
        return persistenceBoundary(
            PersistenceResourceKind.LEASE, PersistenceOperation.CLAIM, persistenceFailureDiagnosticObserver,
        ) { leaseMutex.withLock {
            synchronized(monitor) {
                val key = LeaseKey(workflowName, workflowId)
                val existing = leases[key]
                if (existing != null && !isExpired(existing)) {
                    throw safePersistenceFailure(PersistenceResourceKind.LEASE, PersistenceOperation.CLAIM, PersistenceFailureCode.CONFLICT)
                }
                val now = clockMillis()
                val lease = WorkflowLease(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    leaseId = UUID.randomUUID().toString(),
                    ownerId = ownerId,
                    checkpointRevision = checkpointRevision,
                    acquiredAtEpochMillis = now,
                    expiresAtEpochMillis = now + leaseDurationMillis,
                )
                leases[key] = lease
                lease
            }
        } }
    }

    override suspend fun renew(
        lease: WorkflowLease,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease {
        validateLeaseToken(lease)
        require(leaseDurationMillis > 0) { "leaseDurationMillis must be greater than zero" }
        return persistenceBoundary(
            PersistenceResourceKind.LEASE, PersistenceOperation.RENEW, persistenceFailureDiagnosticObserver,
        ) { leaseMutex.withLock {
            synchronized(monitor) {
                val key = LeaseKey(lease.workflowName, lease.workflowId)
                val existing = leases[key]
                    ?: throw safePersistenceFailure(PersistenceResourceKind.LEASE, PersistenceOperation.RENEW, PersistenceFailureCode.CONFLICT)
                if (isExpired(existing)) {
                    leases.remove(key)
                    throw safePersistenceFailure(PersistenceResourceKind.LEASE, PersistenceOperation.RENEW, PersistenceFailureCode.CONFLICT)
                }
                if (existing.leaseId != lease.leaseId || existing.ownerId != lease.ownerId) {
                    throw safePersistenceFailure(PersistenceResourceKind.LEASE, PersistenceOperation.RENEW, PersistenceFailureCode.CONFLICT)
                }
                val now = clockMillis()
                val renewed = existing.copy(
                    checkpointRevision = checkpointRevision,
                    expiresAtEpochMillis = now + leaseDurationMillis,
                )
                leases[key] = renewed
                renewed
            }
        } }
    }

    override suspend fun release(lease: WorkflowLease) {
        validateLeaseToken(lease)
        persistenceBoundary(PersistenceResourceKind.LEASE, PersistenceOperation.RELEASE, persistenceFailureDiagnosticObserver) { leaseMutex.withLock {
            synchronized(monitor) {
                val key = LeaseKey(lease.workflowName, lease.workflowId)
                val existing = leases[key] ?: return@persistenceBoundary Unit
                if (isExpired(existing)) {
                    leases.remove(key)
                    return@persistenceBoundary Unit
                }
                if (existing.leaseId != lease.leaseId || existing.ownerId != lease.ownerId) {
                    throw safePersistenceFailure(PersistenceResourceKind.LEASE, PersistenceOperation.RELEASE, PersistenceFailureCode.CONFLICT)
                }
                leases.remove(key)
            }
        } }
    }

    override suspend fun saveCheckpointIfLeaseOwner(
        checkpointStore: WorkflowCheckpointStore,
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
        expectedLease: WorkflowLease,
    ): WorkflowCheckpoint {
        validateFenceIdentity(expectedLease, checkpoint.workflowName, checkpoint.workflowId)
        return persistenceBoundary(
            PersistenceResourceKind.LEASE, PersistenceOperation.SAVE, persistenceFailureDiagnosticObserver,
        ) { leaseMutex.withLock {
            val current = synchronized(monitor) {
                activeLease(expectedLease.workflowName, expectedLease.workflowId)
            }
            validateExpectedLease(expectedLease, current)
            checkpointStore.save(checkpoint, expectedRevision)
        } }
    }

    override suspend fun deleteCheckpointIfLeaseOwner(
        checkpointStore: WorkflowCheckpointStore,
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
        expectedLease: WorkflowLease,
    ) {
        validateFenceIdentity(expectedLease, workflowName, workflowId)
        persistenceBoundary(PersistenceResourceKind.LEASE, PersistenceOperation.DELETE, persistenceFailureDiagnosticObserver) { leaseMutex.withLock {
            val current = synchronized(monitor) {
                activeLease(workflowName, workflowId)
            }
            validateExpectedLease(expectedLease, current)
            checkpointStore.delete(workflowName, workflowId, expectedRevision)
        } }
    }

    override suspend fun registerWorker(
        workerId: String,
        poolName: String,
        version: String,
        capabilityLabels: Set<String>,
        host: String,
    ) {
        persistenceBoundary(PersistenceResourceKind.WORKER_REGISTRY, PersistenceOperation.SAVE, persistenceFailureDiagnosticObserver) { synchronized(monitor) {
            val now = clockMillis()
            val existing = workers[workerId]
            workers[workerId] = WorkerRegistryRecord(
                workerId = workerId,
                poolName = poolName,
                version = version,
                capabilityLabels = capabilityLabels.toSortedSet(),
                host = host,
                registeredAtEpochMillis = existing?.registeredAtEpochMillis ?: now,
                lastHeartbeatEpochMillis = now,
            )
        } }
    }

    override suspend fun updateHeartbeat(workerId: String) {
        // clockMillis() can throw (user-supplied) — sanitize that failure. The
        // existence check and update must stay ONE atomic monitor operation
        // (P2-1: no unsynchronized LinkedHashMap read, no check-then-act race
        // with unregisterWorker); the unknown-worker IllegalArgumentException
        // surfaces from inside the monitor, outside any persistence boundary.
        val now = persistenceBoundary(
            PersistenceResourceKind.WORKER_REGISTRY, PersistenceOperation.SAVE, persistenceFailureDiagnosticObserver,
        ) { clockMillis() }
        synchronized(monitor) {
            val existing = workers[workerId]
                ?: throw IllegalArgumentException("Worker '$workerId' is not registered")
            workers[workerId] = existing.copy(lastHeartbeatEpochMillis = now)
        }
    }

    override suspend fun unregisterWorker(workerId: String) {
        persistenceBoundary(PersistenceResourceKind.WORKER_REGISTRY, PersistenceOperation.DELETE, persistenceFailureDiagnosticObserver) { synchronized(monitor) {
            workers.remove(workerId)
        } }
    }

    override suspend fun listActiveWorkers(): List<WorkerRegistryRecord> = persistenceBoundary(
        PersistenceResourceKind.WORKER_REGISTRY, PersistenceOperation.LIST, persistenceFailureDiagnosticObserver,
    ) { synchronized(monitor) {
        workers.values.sortedBy { it.workerId }
    } }

    override suspend fun listStaleWorkers(staleThresholdMillis: Long): List<WorkerRegistryRecord> {
        // Negative threshold is a caller error, not a persistence failure (P2-3).
        require(staleThresholdMillis >= 0) { "staleThresholdMillis must be zero or greater" }
        return persistenceBoundary(
            PersistenceResourceKind.WORKER_REGISTRY, PersistenceOperation.LIST, persistenceFailureDiagnosticObserver,
        ) { synchronized(monitor) {
            val cutoff = clockMillis() - staleThresholdMillis
            workers.values
                .filter { it.lastHeartbeatEpochMillis <= cutoff }
                .sortedBy { it.workerId }
        } }
    }

    private fun isExpired(lease: WorkflowLease): Boolean = clockMillis() >= lease.expiresAtEpochMillis

    private fun activeLease(
        workflowName: String,
        workflowId: String,
    ): WorkflowLease? {
        val key = LeaseKey(workflowName, workflowId)
        val lease = leases[key] ?: return null
        if (isExpired(lease)) {
            leases.remove(key)
            return null
        }
        return lease
    }

    private fun validateExpectedLease(
        expectedLease: WorkflowLease,
        current: WorkflowLease?,
    ) {
        if (current == null) {
            throw safeStaleWorkflowLeaseFailure()
        }
        if (current.leaseId != expectedLease.leaseId || current.ownerId != expectedLease.ownerId) {
            throw safeStaleWorkflowLeaseFailure()
        }
    }
}
private data class LeaseKey(
    val workflowName: String,
    val workflowId: String,
)
