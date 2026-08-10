package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
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
    var persistenceFailureDiagnosticObserver: PersistenceFailureDiagnosticObserver =
        NoOpPersistenceFailureDiagnosticObserver
        internal set

    constructor(
        rootDirectory: Path,
        pathStrategy: WorkflowCheckpointPathStrategy =
            DefaultWorkflowCheckpointPathStrategy("lease.properties"),
        clockMillis: () -> Long = System::currentTimeMillis,
    ) : this(rootDirectory, pathStrategy, clockMillis, realAtomicFileWriter)

    constructor(
        rootDirectory: Path,
        pathStrategy: WorkflowCheckpointPathStrategy,
        clockMillis: () -> Long,
        observer: PersistenceFailureDiagnosticObserver,
    ) : this(rootDirectory, pathStrategy, clockMillis, realAtomicFileWriter) {
        persistenceFailureDiagnosticObserver = observer
    }

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

        fun forTest(
            rootDirectory: Path,
            atomicWriter: AtomicFileWriter,
            clockMillis: () -> Long = System::currentTimeMillis,
            observer: PersistenceFailureDiagnosticObserver,
        ) = FileWorkflowLeaseStore(
            rootDirectory,
            DefaultWorkflowCheckpointPathStrategy("lease.properties"),
            clockMillis,
            atomicWriter,
        ).also { it.persistenceFailureDiagnosticObserver = observer }
    }

    override suspend fun currentLease(
        workflowName: String,
        workflowId: String,
    ): WorkflowLease? = persistenceBoundary(
        PersistenceResourceKind.LEASE, PersistenceOperation.LOAD, persistenceFailureDiagnosticObserver,
    ) {
        val leasePath = leasePath(workflowName, workflowId)
        if (!Files.exists(leasePath)) {
            return@persistenceBoundary null
        }
        withFileLockCancellable(leasePath) {
            val existing = readLeaseIfPresent(leasePath)
            if (existing == null) {
                null
            } else if (isExpired(existing)) {
                deleteLeaseIfPresent(leasePath)
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
    ): WorkflowLease = persistenceBoundary(
        PersistenceResourceKind.LEASE, PersistenceOperation.CLAIM, persistenceFailureDiagnosticObserver,
    ) {
        val leasePath = leasePath(workflowName, workflowId)
        withFileLockCancellable(leasePath) {
            val existing = readLeaseIfPresent(leasePath)
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
            atomicWriter.write(leasePath, encodeLease(lease))
            lease
        }
    }
    override suspend fun renew(
        lease: WorkflowLease,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease = persistenceBoundary(
        PersistenceResourceKind.LEASE, PersistenceOperation.RENEW, persistenceFailureDiagnosticObserver,
    ) {
        val leasePath = leasePath(lease.workflowName, lease.workflowId)
        withFileLockCancellable(leasePath) {
            val existing = readLeaseIfPresent(leasePath)
                ?: throw safePersistenceFailure(PersistenceResourceKind.LEASE, PersistenceOperation.RENEW, PersistenceFailureCode.CONFLICT)
            if (isExpired(existing)) {
                deleteLeaseIfPresent(leasePath)
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
            atomicWriter.write(leasePath, encodeLease(renewed))
            renewed
        }
    }
    override suspend fun release(lease: WorkflowLease) {
        persistenceBoundary(PersistenceResourceKind.LEASE, PersistenceOperation.RELEASE, persistenceFailureDiagnosticObserver) {
            val leasePath = leasePath(lease.workflowName, lease.workflowId)
            withFileLockCancellable(leasePath) {
                val existing = readLeaseIfPresent(leasePath) ?: return@withFileLockCancellable
                if (isExpired(existing)) {
                    deleteLeaseIfPresent(leasePath)
                    return@withFileLockCancellable
                }
                if (existing.leaseId != lease.leaseId || existing.ownerId != lease.ownerId) {
                    throw safePersistenceFailure(PersistenceResourceKind.LEASE, PersistenceOperation.RELEASE, PersistenceFailureCode.CONFLICT)
                }
                deleteLeaseIfPresent(leasePath)
            }
        }
    }

    override suspend fun saveCheckpointIfLeaseOwner(
        checkpointStore: WorkflowCheckpointStore,
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
        expectedLease: WorkflowLease,
    ): WorkflowCheckpoint = persistenceBoundary(
        PersistenceResourceKind.LEASE, PersistenceOperation.SAVE, persistenceFailureDiagnosticObserver,
    ) {
        val leasePath = leasePath(expectedLease.workflowName, expectedLease.workflowId)
        withFileLockCancellableSuspending(leasePath) {
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
        persistenceBoundary(PersistenceResourceKind.LEASE, PersistenceOperation.DELETE, persistenceFailureDiagnosticObserver) {
            val leasePath = leasePath(expectedLease.workflowName, expectedLease.workflowId)
            withFileLockCancellableSuspending(leasePath) {
                val current = readLeaseIfPresent(leasePath)?.takeUnless(::isExpired)
                validateExpectedLease(expectedLease, current)
                checkpointStore.delete(workflowName, workflowId, expectedRevision)
            }
        }
    }

    private fun leasePath(
        workflowName: String,
        workflowId: String,
    ): Path = pathStrategy.resolve(rootDirectory, workflowName, workflowId)
    private fun readLeaseIfPresent(path: Path): WorkflowLease? = try {
        if (Files.exists(path)) {
            decodeLease(Files.readString(path))
        } else {
            null
        }
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        throw LeaseReadPhaseFailure(error)
    }

    private fun deleteLeaseIfPresent(path: Path): Boolean = try {
        Files.deleteIfExists(path)
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        throw LeaseDeletePhaseFailure(error)
    }
    private fun isExpired(lease: WorkflowLease): Boolean = clockMillis() >= lease.expiresAtEpochMillis

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
