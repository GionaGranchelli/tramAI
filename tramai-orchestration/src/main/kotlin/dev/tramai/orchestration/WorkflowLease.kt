package dev.tramai.orchestration
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
 * Raised when a workflow cannot claim or renew active ownership because another executor holds the lease.
 */
class WorkflowLeaseConflictException(
    message: String,
) : RuntimeException(message)
/**
 * Simple in-memory lease store for tests and lightweight local use.
 */
class InMemoryWorkflowLeaseStore(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : WorkflowLeaseStore, WorkerRegistryStore {
    private val leases = linkedMapOf<LeaseKey, WorkflowLease>()
    private val workers = linkedMapOf<String, WorkerRegistryRecord>()
    private val monitor = Any()
    override suspend fun currentLease(
        workflowName: String,
        workflowId: String,
    ): WorkflowLease? = synchronized(monitor) {
        val key = LeaseKey(workflowName, workflowId)
        val lease = leases[key]
        if (lease == null) {
            null
        } else if (isExpired(lease)) {
            leases.remove(key)
            null
        } else {
            lease
        }
    }
    override suspend fun claim(
        workflowName: String,
        workflowId: String,
        ownerId: String,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease = synchronized(monitor) {
        val key = LeaseKey(workflowName, workflowId)
        val existing = leases[key]
        if (existing != null && !isExpired(existing)) {
            throw WorkflowLeaseConflictException(
                "Workflow '$workflowName' and workflowId='$workflowId' is already leased by owner '${existing.ownerId}' until ${existing.expiresAtEpochMillis}",
            )
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
    override suspend fun renew(
        lease: WorkflowLease,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease = synchronized(monitor) {
        val key = LeaseKey(lease.workflowName, lease.workflowId)
        val existing = leases[key]
            ?: throw WorkflowLeaseConflictException(
                "Workflow '${lease.workflowName}' and workflowId='${lease.workflowId}' has no active lease to renew",
            )
        if (isExpired(existing)) {
            leases.remove(key)
            throw WorkflowLeaseConflictException(
                "Workflow '${lease.workflowName}' and workflowId='${lease.workflowId}' lease has expired before renewal",
            )
        }
        if (existing.leaseId != lease.leaseId || existing.ownerId != lease.ownerId) {
            throw WorkflowLeaseConflictException(
                "Workflow '${lease.workflowName}' and workflowId='${lease.workflowId}' is leased by owner '${existing.ownerId}', not '${lease.ownerId}'",
            )
        }
        val now = clockMillis()
        val renewed = existing.copy(
            checkpointRevision = checkpointRevision,
            expiresAtEpochMillis = now + leaseDurationMillis,
        )
        leases[key] = renewed
        renewed
    }
    override suspend fun release(lease: WorkflowLease) {
        synchronized(monitor) {
            val key = LeaseKey(lease.workflowName, lease.workflowId)
            val existing = leases[key] ?: return
            if (isExpired(existing)) {
                leases.remove(key)
                return
            }
            if (existing.leaseId != lease.leaseId || existing.ownerId != lease.ownerId) {
                throw WorkflowLeaseConflictException(
                    "Workflow '${lease.workflowName}' and workflowId='${lease.workflowId}' is leased by owner '${existing.ownerId}', not '${lease.ownerId}'",
                )
            }
            leases.remove(key)
        }
    }

    override suspend fun registerWorker(
        workerId: String,
        poolName: String,
        version: String,
        capabilityLabels: Set<String>,
        host: String,
    ) {
        synchronized(monitor) {
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
        }
    }

    override suspend fun updateHeartbeat(workerId: String) {
        synchronized(monitor) {
            val existing = workers[workerId]
                ?: throw IllegalArgumentException("Worker '$workerId' is not registered")
            workers[workerId] = existing.copy(lastHeartbeatEpochMillis = clockMillis())
        }
    }

    override suspend fun unregisterWorker(workerId: String) {
        synchronized(monitor) {
            workers.remove(workerId)
        }
    }

    override suspend fun listActiveWorkers(): List<WorkerRegistryRecord> = synchronized(monitor) {
        workers.values.sortedBy { it.workerId }
    }

    override suspend fun listStaleWorkers(staleThresholdMillis: Long): List<WorkerRegistryRecord> = synchronized(monitor) {
        require(staleThresholdMillis >= 0) {
            "staleThresholdMillis must be zero or greater"
        }
        val cutoff = clockMillis() - staleThresholdMillis
        workers.values
            .filter { it.lastHeartbeatEpochMillis <= cutoff }
            .sortedBy { it.workerId }
    }

    private fun isExpired(lease: WorkflowLease): Boolean = clockMillis() >= lease.expiresAtEpochMillis
}
private data class LeaseKey(
    val workflowName: String,
    val workflowId: String,
)
