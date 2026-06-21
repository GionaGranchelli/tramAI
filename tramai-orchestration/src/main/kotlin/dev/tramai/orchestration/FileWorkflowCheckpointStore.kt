package dev.tramai.orchestration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.Properties
import java.io.StringWriter
/**
 * Plain file-backed checkpoint store using a simple properties-based envelope.
 */
class FileWorkflowCheckpointStore(
    private val rootDirectory: Path,
    private val pathStrategy: WorkflowCheckpointPathStrategy = DefaultWorkflowCheckpointPathStrategy("checkpoint.properties"),
) : WorkflowCheckpointStore, WorkflowCheckpointCatalog {
    override suspend fun load(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint? {
        val checkpointPath = checkpointPath(workflowName, workflowId)
        if (!Files.exists(checkpointPath)) {
            return null
        }
        return withFileLock(checkpointPath) {
            if (!Files.exists(checkpointPath)) {
                null
            } else {
                decodeCheckpoint(Files.readString(checkpointPath))
            }
        }
    }
    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint {
        val checkpointPath = checkpointPath(checkpoint.workflowName, checkpoint.workflowId)
        return withFileLock(checkpointPath) {
            val existing = if (Files.exists(checkpointPath)) {
                decodeCheckpoint(Files.readString(checkpointPath))
            } else {
                null
            }
            validateExpectedRevision(
                workflowName = checkpoint.workflowName,
                workflowId = checkpoint.workflowId,
                existing = existing,
                expectedRevision = expectedRevision,
            )
            val persisted = checkpoint.copy(
                revision = (existing?.revision ?: 0) + 1,
            )
            writeStringAtomically(checkpointPath, encodeCheckpoint(persisted))
            persisted
        }
    }
    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
    ) {
        val checkpointPath = checkpointPath(workflowName, workflowId)
        withFileLock(checkpointPath) {
            val existing = if (Files.exists(checkpointPath)) {
                decodeCheckpoint(Files.readString(checkpointPath))
            } else {
                null
            }
            validateDeleteExpectedRevision(
                workflowName = workflowName,
                workflowId = workflowId,
                existing = existing,
                expectedRevision = expectedRevision,
            )
            Files.deleteIfExists(checkpointPath)
        }
    }

    /**
     * Reads every checkpoint file into memory before sorting.
     *
     * Large deployments should prefer a paged or indexed catalog implementation to avoid heap pressure
     * during worker scans.
     */
    override suspend fun listCheckpoints(): List<WorkflowCheckpoint> {
        if (!Files.exists(rootDirectory)) {
            return emptyList()
        }
        Files.walk(rootDirectory).use { paths ->
            return paths
                .filter(Files::isRegularFile)
                .filter { !it.fileName.toString().endsWith(".lock") }
                .map(Files::readString)
                .map(::decodeCheckpoint)
                .toList()
                .sortedWith(compareBy<WorkflowCheckpoint>({ it.workflowName }, { it.workflowId }))
        }
    }
    private fun checkpointPath(
        workflowName: String,
        workflowId: String,
    ): Path = pathStrategy.resolve(rootDirectory, workflowName, workflowId)
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
    checkpoint.metadata.forEach { (key, value) ->
        properties["metadata.${base64Encode(key)}"] = base64Encode(value)
    }
    return StringWriter().also { writer ->
        properties.store(writer, "Tramai workflow checkpoint")
    }.toString()
}
internal fun decodeCheckpoint(content: String): WorkflowCheckpoint {
    val properties = Properties().apply {
        load(content.reader())
    }
    val metadata = properties.stringPropertyNames()
        .filter { it.startsWith("metadata.") }
        .associate { propertyName ->
            val encodedKey = propertyName.removePrefix("metadata.")
            base64Decode(encodedKey) to base64Decode(properties.getProperty(propertyName))
        }
    return WorkflowCheckpoint(
        workflowName = properties.requireProperty("workflowName"),
        workflowId = properties.requireProperty("workflowId"),
        nextStepIndex = properties.requireProperty("nextStepIndex").toInt(),
        stepExecutions = properties.requireProperty("stepExecutions").toInt(),
        lastCompletedStepName = properties.getProperty("lastCompletedStepName").orEmpty().ifBlank { null },
        statePayload = base64Decode(properties.requireProperty("statePayloadBase64")),
        revision = properties.getProperty("revision")?.toLong() ?: 0,
        metadata = metadata,
        savedAtEpochMillis = properties.getProperty("savedAtEpochMillis")?.toLong() ?: System.currentTimeMillis(),
    )
}
internal inline fun <T> withFileLock(
    checkpointPath: Path,
    block: () -> T,
): T {
    val lockPath = checkpointPath.resolveSibling("${checkpointPath.fileName}.lock")
    ensureOwnerOnlyDirectory(lockPath.parent)
    ensureOwnerOnlyFile(lockPath)
    FileChannel.open(
        lockPath,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
    ).use { channel ->
        channel.lock().use {
            return block()
        }
    }
}

internal suspend inline fun <T> withFileLockSuspending(
    checkpointPath: Path,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    crossinline block: suspend () -> T,
): T = withContext(ioDispatcher) {
    val lockPath = checkpointPath.resolveSibling("${checkpointPath.fileName}.lock")
    ensureOwnerOnlyDirectory(lockPath.parent)
    ensureOwnerOnlyFile(lockPath)
    FileChannel.open(
        lockPath,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
    ).use { channel ->
        channel.lock().use {
            block()
        }
    }
}
internal fun writeStringAtomically(
    path: Path,
    content: String,
) {
    ensureOwnerOnlyDirectory(path.parent)
    val tempFile = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
    applyOwnerOnlyFilePermissions(tempFile)
    Files.writeString(
        tempFile,
        content,
        StandardCharsets.UTF_8,
        StandardOpenOption.TRUNCATE_EXISTING,
    )
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
}
internal fun validateExpectedRevision(
    workflowName: String,
    workflowId: String,
    existing: WorkflowCheckpoint?,
    expectedRevision: Long?,
) {
    if (expectedRevision == null && existing != null) {
        throw WorkflowCheckpointConflictException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' already exists at revision ${existing.revision}",
        )
    }
    if (expectedRevision != null && existing == null) {
        throw WorkflowCheckpointConflictException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' does not exist for expected revision $expectedRevision",
        )
    }
    if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
        throw WorkflowCheckpointConflictException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' is at revision ${existing.revision}, not expected revision $expectedRevision",
        )
    }
}
internal fun validateDeleteExpectedRevision(
    workflowName: String,
    workflowId: String,
    existing: WorkflowCheckpoint?,
    expectedRevision: Long?,
) {
    if (expectedRevision != null && existing == null) {
        throw WorkflowCheckpointConflictException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' does not exist for expected revision $expectedRevision",
        )
    }
    if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
        throw WorkflowCheckpointConflictException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' is at revision ${existing.revision}, not expected revision $expectedRevision",
        )
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
    }
}

private fun applyOwnerOnlyFilePermissions(path: Path) {
    try {
        Files.setPosixFilePermissions(path, ownerOnlyFilePermissions)
    } catch (_: UnsupportedOperationException) {
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
