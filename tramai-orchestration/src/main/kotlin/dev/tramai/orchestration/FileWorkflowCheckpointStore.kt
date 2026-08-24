package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel
import java.nio.channels.FileLockInterruptionException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import java.io.StringWriter
/**
 * Plain file-backed checkpoint store using a simple properties-based envelope.
 */
class FileWorkflowCheckpointStore private constructor(
    private val rootDirectory: Path,
    private val pathStrategy: WorkflowCheckpointPathStrategy,
    private val atomicWriter: AtomicFileWriter,
) : WorkflowCheckpointStore, WorkflowCheckpointCatalog {
    var persistenceFailureDiagnosticObserver: PersistenceFailureDiagnosticObserver =
        NoOpPersistenceFailureDiagnosticObserver
        internal set

    constructor(
        rootDirectory: Path,
        pathStrategy: WorkflowCheckpointPathStrategy =
            CollisionFreeWorkflowCheckpointPathStrategy("checkpoint.properties"),
    ) : this(rootDirectory, pathStrategy, realAtomicFileWriter)

    constructor(
        rootDirectory: Path,
        pathStrategy: WorkflowCheckpointPathStrategy,
        observer: PersistenceFailureDiagnosticObserver,
    ) : this(rootDirectory, pathStrategy, realAtomicFileWriter) {
        persistenceFailureDiagnosticObserver = observer
    }

    internal companion object {
        fun forTest(
            rootDirectory: Path,
            atomicWriter: AtomicFileWriter,
        ) = FileWorkflowCheckpointStore(
            rootDirectory,
            DefaultWorkflowCheckpointPathStrategy("checkpoint.properties"),
            atomicWriter,
        )

        fun forTest(
            rootDirectory: Path,
            atomicWriter: AtomicFileWriter,
            observer: PersistenceFailureDiagnosticObserver,
        ) = FileWorkflowCheckpointStore(
            rootDirectory,
            DefaultWorkflowCheckpointPathStrategy("checkpoint.properties"),
            atomicWriter,
        ).also { it.persistenceFailureDiagnosticObserver = observer }
    }

    override suspend fun load(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint? = persistenceBoundary(
        PersistenceResourceKind.CHECKPOINT, PersistenceOperation.LOAD, persistenceFailureDiagnosticObserver,
        classify = ::classifyCheckpointFailure,
    ) {
        val checkpointPath = effectiveCheckpointPath(workflowName, workflowId)
        if (!Files.exists(checkpointPath)) {
            null
        } else {
            withFileLockCancellable(checkpointPath) {
                if (!Files.exists(checkpointPath)) {
                    null
                } else {
                    val decoded = decodeCheckpoint(Files.readString(checkpointPath))
                    // A legacy sanitized path may hold another key's record
                    // after the collision-free strategy was introduced; only
                    // accept it when it identifies the requested key.
                    if (decoded.workflowName == workflowName && decoded.workflowId == workflowId) {
                        decoded
                    } else {
                        null
                    }
                }
            }
        }
    }
    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint = persistenceBoundary(
        PersistenceResourceKind.CHECKPOINT, PersistenceOperation.SAVE, persistenceFailureDiagnosticObserver,
        classify = ::classifyCheckpointFailure,
    ) {
        val workflowName = checkpoint.workflowName
        val workflowId = checkpoint.workflowId
        val canonical = checkpointPath(workflowName, workflowId)
        val target = effectiveCheckpointPath(workflowName, workflowId)
        withFileLockCancellable(target) {
            val existing = if (Files.exists(target)) {
                decodeCheckpoint(Files.readString(target))
            } else {
                null
            }
            val identityMatch = existing == null ||
                (existing.workflowName == workflowName && existing.workflowId == workflowId)
            if (existing != null && !identityMatch) {
                // The file at the target path belongs to a different logical
                // key (legacy collision); never overwrite it.
                throw safePersistenceFailure(
                    PersistenceResourceKind.CHECKPOINT,
                    PersistenceOperation.SAVE,
                    PersistenceFailureCode.CONFLICT,
                )
            }
            val effectiveExisting = if (identityMatch) existing else null
            validateExpectedRevision(
                workflowName = workflowName,
                workflowId = workflowId,
                existing = effectiveExisting,
                expectedRevision = expectedRevision,
            )
            val persisted = checkpoint.copy(
                revision = (effectiveExisting?.revision ?: 0) + 1,
            )
            atomicWriter.write(canonical, encodeCheckpoint(persisted))
            if (target != canonical && existing != null) {
                // Migrate the legacy-path record to the canonical path.
                Files.deleteIfExists(target)
            }
            persisted
        }
    }
    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
    ) = persistenceBoundary(
        PersistenceResourceKind.CHECKPOINT, PersistenceOperation.DELETE, persistenceFailureDiagnosticObserver,
        classify = ::classifyCheckpointFailure,
    ) {
        val canonical = checkpointPath(workflowName, workflowId)
        val target = effectiveCheckpointPath(workflowName, workflowId)
        withFileLockCancellable(target) {
            val existing = if (Files.exists(target)) {
                decodeCheckpoint(Files.readString(target))
            } else {
                null
            }
            val identityMatch = existing == null ||
                (existing.workflowName == workflowName && existing.workflowId == workflowId)
            val effectiveExisting = if (identityMatch) existing else null
            validateDeleteExpectedRevision(
                workflowName = workflowName,
                workflowId = workflowId,
                existing = effectiveExisting,
                expectedRevision = expectedRevision,
            )
            if (identityMatch && existing != null) {
                Files.deleteIfExists(target)
                if (target != canonical) {
                    // No stale canonical file should exist; clean up defensively.
                    Files.deleteIfExists(canonical)
                }
            }
            Unit
        }
    }

    /**
     * Reads every checkpoint file into memory before sorting.
     *
     * Large deployments should prefer a paged or indexed catalog implementation to avoid heap pressure
     * during worker scans.
     */
    override suspend fun listCheckpoints(): List<WorkflowCheckpoint> = persistenceBoundary(
        PersistenceResourceKind.CHECKPOINT, PersistenceOperation.LIST, persistenceFailureDiagnosticObserver,
        classify = ::classifyCheckpointFailure,
    ) {
        if (!Files.exists(rootDirectory)) {
            emptyList()
        } else {
            Files.walk(rootDirectory).use { paths ->
                paths
                .filter(Files::isRegularFile)
                .filter { !it.fileName.toString().endsWith(".lock") }
                .map(Files::readString)
                .map(::decodeCheckpoint)
                .toList()
                .sortedWith(compareBy<WorkflowCheckpoint>({ it.workflowName }, { it.workflowId }))
            }
        }
    }
    private fun checkpointPath(
        workflowName: String,
        workflowId: String,
    ): Path = pathStrategy.resolve(rootDirectory, workflowName, workflowId)

    /**
     * The path currently holding this checkpoint: the canonical collision-free
     * path when present, otherwise the legacy sanitized path (when the
     * strategy supports it and the legacy file exists), otherwise the
     * canonical path. Callers must still verify the decoded record's identity
     * — a legacy path may hold a colliding key's record.
     */
    private fun effectiveCheckpointPath(
        workflowName: String,
        workflowId: String,
    ): Path {
        val canonical = checkpointPath(workflowName, workflowId)
        if (Files.exists(canonical)) return canonical
        val legacy = (pathStrategy as? CollisionFreeWorkflowCheckpointPathStrategy)
            ?.legacyCheckpointPath(rootDirectory, workflowName, workflowId)
            ?: return canonical
        return if (Files.exists(legacy)) legacy else canonical
    }
}
/**
 * Strategy used by file-based checkpoint stores to choose one file path per checkpoint.
 */
fun interface WorkflowCheckpointPathStrategy {
    fun resolve(
        rootDirectory: Path,
        workflowName: String,
        workflowId: String,
    ): Path
}
class DefaultWorkflowCheckpointPathStrategy(
    private val fileName: String,
) : WorkflowCheckpointPathStrategy {
    override fun resolve(
        rootDirectory: Path,
        workflowName: String,
        workflowId: String,
    ): Path = rootDirectory
        .resolve(sanitizePathSegment(workflowName))
        .resolve(sanitizePathSegment(workflowId))
        .resolve(fileName)
}

/**
 * Collision-free checkpoint path strategy.
 *
 * [DefaultWorkflowCheckpointPathStrategy] maps every non-`[A-Za-z0-9_-]`
 * character to `_`, so logically distinct keys collapse onto one file
 * (`"order/a"` and `"order?a"` both become `"order_a"`). This strategy
 * encodes each identity segment with URL-safe Base64 (no padding), which is
 * reversible and injective: every distinct (workflowName, workflowId) maps
 * to a distinct path, matching the identity domain of the in-memory and JDBC
 * stores.
 *
 * [legacyCheckpointPath] resolves the historical sanitized path for the same
 * key so stores can read checkpoints persisted before this strategy was
 * introduced (verify the decoded record's identity before accepting it, and
 * migrate on the first legitimate update).
 */
class CollisionFreeWorkflowCheckpointPathStrategy(
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

    /** The pre-collision-free path this key would have used. */
    fun legacyCheckpointPath(
        rootDirectory: Path,
        workflowName: String,
        workflowId: String,
    ): Path = rootDirectory
        .resolve(sanitizePathSegment(workflowName))
        .resolve(sanitizePathSegment(workflowId))
        .resolve(fileName)

    private fun encodeSegment(input: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(input.toByteArray(StandardCharsets.UTF_8))
}
internal fun encodeCheckpoint(checkpoint: WorkflowCheckpoint): String {
    val properties = Properties()
    properties["workflowName"] = checkpoint.workflowName
    properties["workflowId"] = checkpoint.workflowId
    properties["nextStepIndex"] = checkpoint.nextStepIndex.toString()
    properties["stepExecutions"] = checkpoint.stepExecutions.toString()
    properties["lastCompletedStepName"] = checkpoint.lastCompletedStepName.orEmpty()
    properties["statePayloadBase64"] = base64Encode(checkpoint.statePayload)
    properties["revision"] = checkpoint.revision.toString()
    properties["savedAtEpochMillis"] = checkpoint.savedAtEpochMillis.toString()
    properties["recoveryState"] = encodeRecoveryState(checkpoint.recoveryState)?.let(::base64Encode) ?: ""
    checkpoint.metadata.forEach { (key, value) ->
        properties["metadata.${base64Encode(key)}"] = base64Encode(value)
    }
    return StringWriter().also { writer ->
        properties.store(writer, "Tramai workflow checkpoint")
    }.toString()
}
internal fun decodeCheckpoint(content: String): WorkflowCheckpoint = try {
    val properties = Properties().apply { load(content.reader()) }
    val metadata = properties.stringPropertyNames()
        .filter { it.startsWith("metadata.") }
        .associate { propertyName ->
            val encodedKey = propertyName.removePrefix("metadata.")
            base64Decode(encodedKey) to base64Decode(properties.getProperty(propertyName))
        }
    WorkflowCheckpoint(
        workflowName = properties.requireProperty("workflowName"),
        workflowId = properties.requireProperty("workflowId"),
        nextStepIndex = properties.requireProperty("nextStepIndex").toInt(),
        stepExecutions = properties.requireProperty("stepExecutions").toInt(),
        lastCompletedStepName = properties.getProperty("lastCompletedStepName").orEmpty().ifBlank { null },
        statePayload = base64Decode(properties.requireProperty("statePayloadBase64")),
        revision = properties.getProperty("revision")?.toLong() ?: 0,
        metadata = metadata,
        savedAtEpochMillis = properties.getProperty("savedAtEpochMillis")?.toLong() ?: System.currentTimeMillis(),
        recoveryState = decodeRecoveryState(properties.getProperty("recoveryState")?.takeIf { it.isNotBlank() }?.let(::base64Decode)),
    )
} catch (error: CorruptCheckpointException) {
    throw error
} catch (error: Throwable) {
    throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", content)
}

// --- Per-path Mutex registry with reference counting ---

internal data class PathLockEntry(
    val mutex: Mutex = Mutex(),
    val users: AtomicInteger = AtomicInteger(0),
)

private val pathLocks = java.util.concurrent.ConcurrentHashMap<Path, PathLockEntry>()

private fun retainPathLock(path: Path): Pair<Path, PathLockEntry> {
    val key = path.toAbsolutePath().normalize()
    val entry = pathLocks.compute(key) { _, existing ->
        (existing ?: PathLockEntry()).also {
            it.users.incrementAndGet()
        }
    }!!
    return key to entry
}

private fun releasePathLock(key: Path, expected: PathLockEntry) {
    pathLocks.computeIfPresent(key) { _, current ->
        check(current === expected) { "PathLockEntry mismatch during release" }
        if (current.users.decrementAndGet() == 0) null else current
    }
}

internal suspend inline fun <T> withFileLockCancellable(
    targetPath: Path,
    crossinline block: () -> T,
): T {
    val (key, entry) = retainPathLock(targetPath)
    try {
        return entry.mutex.withLock {
            runInterruptible(Dispatchers.IO) {
                val lockPath = targetPath.resolveSibling("${targetPath.fileName}.lock")
                ensureOwnerOnlyDirectory(lockPath.parent)
                ensureOwnerOnlyFile(lockPath)
                FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    // Narrow the FileLockInterruptionException mapping to lock ACQUISITION
                    // only: the protected block may throw that exception on its own, which
                    // must not be converted into cancellation.
                    val lock = try {
                        channel.lock()
                    } catch (error: FileLockInterruptionException) {
                        throw CancellationException("File lock wait interrupted by cancellation", error)
                    }
                    lock.use {
                        block()
                    }
                }
            }
        }
    } finally {
        releasePathLock(key, entry)
    }
}

internal suspend fun <T> withFileLockCancellableSuspending(
    targetPath: Path,
    block: suspend () -> T,
): T {
    val (key, entry) = retainPathLock(targetPath)
    try {
        return entry.mutex.withLock {
            withContext(Dispatchers.IO) {
                val lockPath = targetPath.resolveSibling("${targetPath.fileName}.lock")
                ensureOwnerOnlyDirectory(lockPath.parent)
                ensureOwnerOnlyFile(lockPath)
                FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    val lock = try {
                        runInterruptible {
                            channel.lock()
                        }
                    } catch (error: FileLockInterruptionException) {
                        throw CancellationException("File lock wait interrupted by cancellation", error)
                    }
                    lock.use {
                        block()
                    }
                }
            }
        }
    } finally {
        releasePathLock(key, entry)
    }
}

/**
 * File writer with an injectable hook that fires after the temporary file
 * is written but before the atomic move. Tests use this to block and cancel
 * during the atomic-write window while still exercising the real
 * [writeStringAtomically] implementation.
 */
internal class AtomicFileWriter(
    private val beforeMove: (tempFile: Path) -> Unit = {},
) {
    fun write(path: Path, content: String) {
        writeStringAtomically(path, content, beforeMove)
    }
}

internal val realAtomicFileWriter = AtomicFileWriter()

internal fun pathLockRegistrySize(): Int = pathLocks.size

internal fun writeStringAtomically(
    path: Path,
    content: String,
    beforeMove: (Path) -> Unit = {},
) {
    ensureOwnerOnlyDirectory(path.parent)
    val tempFile = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
    var primaryFailure: Exception? = null
    try {
        applyOwnerOnlyFilePermissions(tempFile)
        Files.writeString(
            tempFile,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        beforeMove(tempFile)
        try {
            Files.move(
                tempFile,
                path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempFile,
                path,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        applyOwnerOnlyFilePermissions(path)
    } catch (failure: Exception) {
        primaryFailure = failure
        throw failure
    } finally {
        try {
            Files.deleteIfExists(tempFile)
        } catch (cleanupFailure: Exception) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure)
            } else {
                throw cleanupFailure
            }
        }
    }
}
internal fun validateExpectedRevision(
    workflowName: String,
    workflowId: String,
    existing: WorkflowCheckpoint?,
    expectedRevision: Long?,
) {
    if (expectedRevision == null && existing != null) {
        throw safePersistenceFailure(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.SAVE, PersistenceFailureCode.CONFLICT)
    }
    if (expectedRevision != null && existing == null) {
        throw safePersistenceFailure(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.SAVE, PersistenceFailureCode.CONFLICT)
    }
    if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
        throw safePersistenceFailure(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.SAVE, PersistenceFailureCode.CONFLICT)
    }
}
internal fun validateDeleteExpectedRevision(
    workflowName: String,
    workflowId: String,
    existing: WorkflowCheckpoint?,
    expectedRevision: Long?,
) {
    if (expectedRevision != null && existing == null) {
        throw safePersistenceFailure(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.DELETE, PersistenceFailureCode.CONFLICT)
    }
    if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
        throw safePersistenceFailure(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.DELETE, PersistenceFailureCode.CONFLICT)
    }
}
internal fun sanitizePathSegment(input: String): String = input.map { character ->
    when {
        character.isLetterOrDigit() || character == '-' || character == '_' -> character
        else -> '_'
    }
}.joinToString("")
internal fun base64Encode(value: String): String = Base64.getEncoder()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
internal fun base64Decode(value: String): String = String(
    Base64.getDecoder().decode(value),
    StandardCharsets.UTF_8,
)

internal fun classifyCheckpointFailure(error: Throwable): PersistenceFailureCode? =
    if (error is CorruptCheckpointException) PersistenceFailureCode.CORRUPTED_DATA else null

internal fun ensureOwnerOnlyDirectory(path: Path) {
    Files.createDirectories(path)
    applyOwnerOnlyDirectoryPermissions(path)
}

internal fun ensureOwnerOnlyFile(path: Path) {
    if (!Files.exists(path)) {
        Files.createFile(path)
    }
    applyOwnerOnlyFilePermissions(path)
}

private fun applyOwnerOnlyDirectoryPermissions(path: Path) {
    try {
        Files.setPosixFilePermissions(path, ownerOnlyDirectoryPermissions)
    } catch (_: UnsupportedOperationException) {
        // POSIX file permissions are unavailable on this filesystem.
    }
}

private fun applyOwnerOnlyFilePermissions(path: Path) {
    try {
        Files.setPosixFilePermissions(path, ownerOnlyFilePermissions)
    } catch (_: UnsupportedOperationException) {
        // POSIX file permissions are unavailable on this filesystem.
    }
}

private val ownerOnlyDirectoryPermissions = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
)

private val ownerOnlyFilePermissions = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
)
internal fun Properties.requireProperty(name: String): String = getProperty(name)
    ?: error("Missing checkpoint property '$name'")
