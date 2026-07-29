package dev.tramai.orchestration
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
/**
 * Plain file-backed lease store for local and single-filesystem deployments that still need active ownership.
 */
class FileWorkflowLeaseStore private constructor(
    private val rootDirectory: Path,
    private val pathStrategy: WorkflowCheckpointPathStrategy =
        DefaultWorkflowCheckpointPathStrategy("lease.properties"),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val atomicWriter: AtomicFileWriter = realAtomicFileWriter,
) : WorkflowLeaseStore, WorkflowLeaseCheckpointFence {

    constructor(
        rootDirectory: Path,
        pathStrategy: WorkflowCheckpointPathStrategy =
            DefaultWorkflowCheckpointPathStrategy("lease.properties"),
        clockMillis: () -> Long = System::currentTimeMillis,
    ) : this(rootDirectory, pathStrategy, clockMillis, realAtomicFileWriter)

    internal companion object {
        fun forTest(
            rootDirectory: Path,
            atomicWriter: AtomicFileWriter,
            clockMillis: () -> Long = System::currentTimeMillis,
        ) = FileWorkflowLeaseStore(
            rootDirectory,
            DefaultWorkflowCheckpointPathStrategy("lease.properties"),
            clockMillis,
            atomicWriter,
        )
    }

    override suspend fun currentLease(
        workflowName: String,
        workflowId: String,
    ): WorkflowLease? {
        val leasePath = leasePath(workflowName, workflowId)
        if (!Files.exists(leasePath)) {
            return null
        }
        return withFileLockCancellable(leasePath) {
            val existing = readLeaseIfPresent(leasePath)
            if (existing == null) {
                null
            } else if (isExpired(existing)) {
                Files.deleteIfExists(leasePath)
                null
            } else {
                existing
            }
        }
    }
    override suspend fun claim(
        workflowName: String,
        workflowId: String,
        ownerId: String,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease {
        val leasePath = leasePath(workflowName, workflowId)
        return withFileLockCancellable(leasePath) {
            val existing = readLeaseIfPresent(leasePath)
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
            atomicWriter.write(leasePath, encodeLease(lease))
            lease
        }
    }
    override suspend fun renew(
        lease: WorkflowLease,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease {
        val leasePath = leasePath(lease.workflowName, lease.workflowId)
        return withFileLockCancellable(leasePath) {
            val existing = readLeaseIfPresent(leasePath)
                ?: throw WorkflowLeaseConflictException(
                    "Workflow '${lease.workflowName}' and workflowId='${lease.workflowId}' has no active lease to renew",
                )
            if (isExpired(existing)) {
                Files.deleteIfExists(leasePath)
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
            atomicWriter.write(leasePath, encodeLease(renewed))
            renewed
        }
    }
    override suspend fun release(lease: WorkflowLease) {
        val leasePath = leasePath(lease.workflowName, lease.workflowId)
        withFileLockCancellable(leasePath) {
            val existing = readLeaseIfPresent(leasePath) ?: return@withFileLockCancellable
            if (isExpired(existing)) {
                Files.deleteIfExists(leasePath)
                return@withFileLockCancellable
            }
            if (existing.leaseId != lease.leaseId || existing.ownerId != lease.ownerId) {
                throw WorkflowLeaseConflictException(
                    "Workflow '${lease.workflowName}' and workflowId='${lease.workflowId}' is leased by owner '${existing.ownerId}', not '${lease.ownerId}'",
                )
            }
            Files.deleteIfExists(leasePath)
        }
    }

    override suspend fun saveCheckpointIfLeaseOwner(
        checkpointStore: WorkflowCheckpointStore,
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
        expectedLease: WorkflowLease,
    ): WorkflowCheckpoint {
        val leasePath = leasePath(expectedLease.workflowName, expectedLease.workflowId)
        return withFileLockCancellableSuspending(leasePath) {
            val current = readLeaseIfPresent(leasePath)?.takeUnless(::isExpired)
            validateExpectedLease(expectedLease, current)
            checkpointStore.save(checkpoint, expectedRevision)
        }
    }

    override suspend fun deleteCheckpointIfLeaseOwner(
        checkpointStore: WorkflowCheckpointStore,
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
        expectedLease: WorkflowLease,
    ) {
        val leasePath = leasePath(expectedLease.workflowName, expectedLease.workflowId)
        withFileLockCancellableSuspending(leasePath) {
            val current = readLeaseIfPresent(leasePath)?.takeUnless(::isExpired)
            validateExpectedLease(expectedLease, current)
            checkpointStore.delete(workflowName, workflowId, expectedRevision)
        }
    }

    private fun leasePath(
        workflowName: String,
        workflowId: String,
    ): Path = pathStrategy.resolve(rootDirectory, workflowName, workflowId)
    private fun readLeaseIfPresent(path: Path): WorkflowLease? = if (Files.exists(path)) {
        decodeLease(Files.readString(path))
    } else {
        null
    }
    private fun isExpired(lease: WorkflowLease): Boolean = clockMillis() >= lease.expiresAtEpochMillis

    private fun validateExpectedLease(
        expectedLease: WorkflowLease,
        current: WorkflowLease?,
    ) {
        if (current == null) {
            throw StaleWorkflowLeaseException(
                "Workflow '${expectedLease.workflowName}' and workflowId='${expectedLease.workflowId}' lease '${expectedLease.leaseId}' is no longer active",
            )
        }
        if (current.leaseId != expectedLease.leaseId || current.ownerId != expectedLease.ownerId) {
            throw StaleWorkflowLeaseException(
                "Workflow '${expectedLease.workflowName}' and workflowId='${expectedLease.workflowId}' is now fenced by lease '${current.leaseId}' owned by '${current.ownerId}'",
            )
        }
    }
}
internal fun encodeLease(lease: WorkflowLease): String {
    val properties = Properties()
    properties["workflowName"] = lease.workflowName
    properties["workflowId"] = lease.workflowId
    properties["leaseId"] = lease.leaseId
    properties["ownerId"] = lease.ownerId
    properties["checkpointRevision"] = lease.checkpointRevision?.toString().orEmpty()
    properties["acquiredAtEpochMillis"] = lease.acquiredAtEpochMillis.toString()
    properties["expiresAtEpochMillis"] = lease.expiresAtEpochMillis.toString()
    return StringWriter().also { writer ->
        properties.store(writer, "Tramai workflow lease")
    }.toString()
}
internal fun decodeLease(content: String): WorkflowLease {
    val properties = Properties().apply {
        load(content.reader())
    }
    return WorkflowLease(
        workflowName = properties.requireProperty("workflowName"),
        workflowId = properties.requireProperty("workflowId"),
        leaseId = properties.requireProperty("leaseId"),
        ownerId = properties.requireProperty("ownerId"),
        checkpointRevision = properties.getProperty("checkpointRevision").orEmpty().ifBlank { null }?.toLong(),
        acquiredAtEpochMillis = properties.requireProperty("acquiredAtEpochMillis").toLong(),
        expiresAtEpochMillis = properties.requireProperty("expiresAtEpochMillis").toLong(),
    )
}
