package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties
import java.util.UUID
/**
 * Collision-free lease path strategy with minimal layout disruption.
 *
 * A lease file is live coordination state — changing its path also changes
 * the filesystem lock namespace — so unlike the checkpoint strategy this
 * does NOT re-encode every segment. Segments already in the legacy-safe
 * raw domain (`[A-Za-z0-9_-]`) keep their existing path unchanged; any
 * other segment is encoded as `~` + URL-safe Base64 (no padding). `~` is
 * outside the legacy-safe domain, so a canonical path can never collide
 * with a legacy sanitized path, and every distinct identity maps to a
 * distinct file — storage layout cannot redefine who owns a workflow.
 */
class CollisionFreeWorkflowLeasePathStrategy(
    private val fileName: String,
) : WorkflowCheckpointPathStrategy {
    override fun resolve(
        rootDirectory: Path,
        workflowName: String,
        workflowId: String,
    ): Path = rootDirectory
        .resolve(encodeSegment(workflowName))
        .resolve(encodeSegment(workflowId))
        .resolve(fileName)

    /** The pre-collision-free (lossy sanitized) path this key would have used. */
    fun legacyLeasePath(
        rootDirectory: Path,
        workflowName: String,
        workflowId: String,
    ): Path = rootDirectory
        .resolve(sanitizePathSegment(workflowName))
        .resolve(sanitizePathSegment(workflowId))
        .resolve(fileName)

    private fun encodeSegment(input: String): String =
        if (input.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            input
        } else {
            "~" + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(input.toByteArray(StandardCharsets.UTF_8))
        }
}

/**
 * Plain file-backed lease store for local and single-filesystem deployments that still need active ownership.
 */
class FileWorkflowLeaseStore private constructor(
    private val rootDirectory: Path,
    private val pathStrategy: WorkflowCheckpointPathStrategy =
        CollisionFreeWorkflowLeasePathStrategy("lease.properties"),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val atomicWriter: AtomicFileWriter = realAtomicFileWriter,
) : WorkflowLeaseStore, WorkflowLeaseCheckpointFence {
    var persistenceFailureDiagnosticObserver: PersistenceFailureDiagnosticObserver =
        NoOpPersistenceFailureDiagnosticObserver
        internal set

    constructor(
        rootDirectory: Path,
        pathStrategy: WorkflowCheckpointPathStrategy =
            CollisionFreeWorkflowLeasePathStrategy("lease.properties"),
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
        val leasePath = effectiveLeasePath(workflowName, workflowId)
        if (!Files.exists(leasePath)) {
            return@persistenceBoundary null
        }
        withFileLockCancellable(leasePath) {
            val existing = readLeaseIfPresent(leasePath)
            when {
                existing == null -> null
                // A legacy sanitized path may hold another key's lease after
                // the collision-free strategy was introduced; only accept it
                // when it identifies the requested key.
                !identityMatches(existing, workflowName, workflowId) -> null
                isExpired(existing) -> {
                    deleteLeaseIfPresent(leasePath)
                    null
                }
                else -> existing
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
        validateLeaseIdentityInput(workflowName, workflowId, ownerId, leaseDurationMillis)
        return persistenceBoundary(
            PersistenceResourceKind.LEASE, PersistenceOperation.CLAIM, persistenceFailureDiagnosticObserver,
        ) {
            val canonical = leasePath(workflowName, workflowId)
            val target = effectiveLeasePath(workflowName, workflowId)
            withFileLockCancellable(target) {
                val existing = readLeaseIfPresent(target)?.takeIf {
                    identityMatches(it, workflowName, workflowId)
                }
                if (existing != null && !isExpired(existing)) {
                    throw safePersistenceFailure(PersistenceResourceKind.LEASE, PersistenceOperation.CLAIM, PersistenceFailureCode.CONFLICT)
                }
                if (existing != null && target != canonical) {
                    // An expired legacy lease: do not extend it in place —
                    // operate on the legacy path until release or expiry, then
                    // the next claim uses the canonical collision-free path.
                    deleteLeaseIfPresent(target)
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
                atomicWriter.write(canonical, encodeLease(lease))
                lease
            }
        }
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
        ) {
            val leasePath = effectiveLeasePath(lease.workflowName, lease.workflowId)
            withFileLockCancellable(leasePath) {
                val existing = readLeaseIfPresent(leasePath)?.takeIf {
                    identityMatches(it, lease.workflowName, lease.workflowId)
                }
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
    }
    override suspend fun release(lease: WorkflowLease) {
        validateLeaseToken(lease)
        persistenceBoundary(PersistenceResourceKind.LEASE, PersistenceOperation.RELEASE, persistenceFailureDiagnosticObserver) {
            val leasePath = effectiveLeasePath(lease.workflowName, lease.workflowId)
            withFileLockCancellable(leasePath) {
                val existing = readLeaseIfPresent(leasePath)?.takeIf {
                    identityMatches(it, lease.workflowName, lease.workflowId)
                } ?: return@withFileLockCancellable
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
    ): WorkflowCheckpoint {
        validateFenceIdentity(expectedLease, checkpoint.workflowName, checkpoint.workflowId)
        return persistenceBoundary(
            PersistenceResourceKind.LEASE, PersistenceOperation.SAVE, persistenceFailureDiagnosticObserver,
        ) {
            val leasePath = effectiveLeasePath(expectedLease.workflowName, expectedLease.workflowId)
            withFileLockCancellableSuspending(leasePath) {
                val current = readLeaseIfPresent(leasePath)
                    ?.takeIf { identityMatches(it, expectedLease.workflowName, expectedLease.workflowId) }
                    ?.takeUnless(::isExpired)
                validateExpectedLease(expectedLease, current)
                checkpointStore.save(checkpoint, expectedRevision)
            }
        }
    }

    override suspend fun deleteCheckpointIfLeaseOwner(
        checkpointStore: WorkflowCheckpointStore,
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
        expectedLease: WorkflowLease,
    ) {
        validateFenceIdentity(expectedLease, workflowName, workflowId)
        persistenceBoundary(PersistenceResourceKind.LEASE, PersistenceOperation.DELETE, persistenceFailureDiagnosticObserver) {
            val leasePath = effectiveLeasePath(expectedLease.workflowName, expectedLease.workflowId)
            withFileLockCancellableSuspending(leasePath) {
                val current = readLeaseIfPresent(leasePath)
                    ?.takeIf { identityMatches(it, expectedLease.workflowName, expectedLease.workflowId) }
                    ?.takeUnless(::isExpired)
                validateExpectedLease(expectedLease, current)
                checkpointStore.delete(workflowName, workflowId, expectedRevision)
            }
        }
    }

    private fun leasePath(
        workflowName: String,
        workflowId: String,
    ): Path = pathStrategy.resolve(rootDirectory, workflowName, workflowId)

    /**
     * The path currently holding this key's lease: the canonical
     * collision-free path when present, otherwise the legacy sanitized path
     * (when the strategy supports it and the file exists), otherwise the
     * canonical path. Callers must still verify the decoded record's
     * identity — a legacy path may hold a colliding key's lease.
     */
    private fun effectiveLeasePath(
        workflowName: String,
        workflowId: String,
    ): Path {
        val canonical = leasePath(workflowName, workflowId)
        if (Files.exists(canonical)) return canonical
        val legacy = (pathStrategy as? CollisionFreeWorkflowLeasePathStrategy)
            ?.legacyLeasePath(rootDirectory, workflowName, workflowId)
            ?: return canonical
        return if (Files.exists(legacy)) legacy else canonical
    }

    private fun identityMatches(
        lease: WorkflowLease,
        workflowName: String,
        workflowId: String,
    ): Boolean = lease.workflowName == workflowName && lease.workflowId == workflowId

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
