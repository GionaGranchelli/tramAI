package dev.tramai.orchestration
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
/**
 * Markdown-backed checkpoint store for audit-friendly local persistence.
 */
class MarkdownWorkflowCheckpointStore(
    private val rootDirectory: Path,
    private val pathStrategy: WorkflowCheckpointPathStrategy = CollisionFreeWorkflowCheckpointPathStrategy("checkpoint.md"),
) : WorkflowCheckpointStore {
    var persistenceFailureDiagnosticObserver: PersistenceFailureDiagnosticObserver =
        NoOpPersistenceFailureDiagnosticObserver
        internal set

    constructor(
        rootDirectory: Path,
        pathStrategy: WorkflowCheckpointPathStrategy,
        observer: PersistenceFailureDiagnosticObserver,
    ) : this(rootDirectory, pathStrategy) {
        persistenceFailureDiagnosticObserver = observer
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
                    val decoded = decodeMarkdownCheckpoint(Files.readString(checkpointPath))
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
                decodeMarkdownCheckpoint(Files.readString(target))
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
                expectedGeneration = checkpoint.checkpointGeneration,
            )
            val persisted = checkpoint.copy(
                revision = (effectiveExisting?.revision ?: 0) + 1,
                checkpointGeneration = effectiveExisting?.checkpointGeneration ?: newCheckpointGeneration(),
            )
            writeStringAtomically(canonical, encodeMarkdownCheckpoint(persisted))
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
        expectedGeneration: String?,
    ) = persistenceBoundary(
        PersistenceResourceKind.CHECKPOINT, PersistenceOperation.DELETE, persistenceFailureDiagnosticObserver,
        classify = ::classifyCheckpointFailure,
    ) {
        val canonical = checkpointPath(workflowName, workflowId)
        val target = effectiveCheckpointPath(workflowName, workflowId)
        withFileLockCancellable(target) {
            val existing = if (Files.exists(target)) {
                decodeMarkdownCheckpoint(Files.readString(target))
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
                expectedGeneration = expectedGeneration,
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
internal fun encodeMarkdownCheckpoint(checkpoint: WorkflowCheckpoint): String {
    val metadataLines = checkpoint.metadata.entries
        .sortedBy { it.key }
        .joinToString("\n") { entry ->
            "metadata.${base64Encode(entry.key)}: ${base64Encode(entry.value)}"
        }
    val fence = markdownFence(checkpoint.statePayload)
    return buildString {
        appendLine("---")
        appendLine("workflowName: ${base64Encode(checkpoint.workflowName)}")
        appendLine("workflowId: ${base64Encode(checkpoint.workflowId)}")
        appendLine("nextStepIndex: ${checkpoint.nextStepIndex}")
        appendLine("stepExecutions: ${checkpoint.stepExecutions}")
        appendLine("lastCompletedStepName: ${base64Encode(checkpoint.lastCompletedStepName.orEmpty())}")
        appendLine("revision: ${checkpoint.revision}")
        appendLine("savedAtEpochMillis: ${checkpoint.savedAtEpochMillis}")
        appendLine("recoveryState: ${encodeRecoveryState(checkpoint.recoveryState)?.let(::base64Encode).orEmpty()}")
        appendLine("checkpointGeneration: ${checkpoint.checkpointGeneration.orEmpty()}")
        if (metadataLines.isNotBlank()) {
            appendLine(metadataLines)
        }
        appendLine("---")
        appendLine("# Tramai Workflow Checkpoint")
        appendLine()
        appendLine("## State Payload")
        appendLine()
        appendLine("$fence text")
        appendLine(checkpoint.statePayload)
        appendLine(fence)
    }
}
internal fun decodeMarkdownCheckpoint(content: String): WorkflowCheckpoint = try {
    val lines = content.lines()
    if (lines.firstOrNull() != "---") throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", content)
    val closingIndex = lines.drop(1).indexOfFirst { it == "---" }
        .takeIf { it >= 0 }
        ?.plus(1)
        ?: -1
    if (closingIndex <= 0) throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", content)
    val frontMatter = lines.subList(1, closingIndex)
        .filter { it.isNotBlank() }
        .associate { line ->
            val separatorIndex = line.indexOf(": ")
            if (separatorIndex <= 0) throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", line)
            line.substring(0, separatorIndex) to line.substring(separatorIndex + 2)
        }
    val payloadHeaderIndex = lines.indexOfFirst { it == "## State Payload" }
    if (payloadHeaderIndex < 0) throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", content)
    val fenceLineIndex = ((payloadHeaderIndex + 1) until lines.size)
        .firstOrNull { lines[it].startsWith("```") }
        ?: -1
    if (fenceLineIndex <= payloadHeaderIndex) throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", content)
    val fence = lines[fenceLineIndex].substringBefore(" ")
    val closingFenceIndex = ((fenceLineIndex + 1) until lines.size)
        .firstOrNull { lines[it] == fence }
        ?: -1
    if (closingFenceIndex <= fenceLineIndex) throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", content)
    val payload = lines.subList(fenceLineIndex + 1, closingFenceIndex).joinToString("\n")
    val metadata = frontMatter.entries
        .filter { it.key.startsWith("metadata.") }
        .associate { entry ->
            base64Decode(entry.key.removePrefix("metadata.")) to base64Decode(entry.value)
        }
    WorkflowCheckpoint(
        workflowName = base64Decode(frontMatter.requireValue("workflowName")),
        workflowId = base64Decode(frontMatter.requireValue("workflowId")),
        nextStepIndex = frontMatter.requireValue("nextStepIndex").toInt(),
        stepExecutions = frontMatter.requireValue("stepExecutions").toInt(),
        lastCompletedStepName = base64Decode(frontMatter.requireValue("lastCompletedStepName")).ifBlank { null },
        statePayload = payload,
        revision = frontMatter.requireValue("revision").toLong(),
        metadata = metadata,
        savedAtEpochMillis = frontMatter.requireValue("savedAtEpochMillis").toLong(),
        recoveryState = decodeRecoveryState(
            frontMatter["recoveryState"]?.takeIf { it.isNotBlank() }?.let(::base64Decode),
        ),
        checkpointGeneration = frontMatter["checkpointGeneration"]?.ifBlank { null },
    )
} catch (error: CorruptCheckpointException) {
    throw error
} catch (error: Throwable) {
    throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", content)
}
private fun markdownFence(content: String): String {
    val longestFence = Regex("`+")
        .findAll(content)
        .maxOfOrNull { it.value.length }
        ?: 0
    return "`".repeat(max(3, longestFence + 1))
}
private fun <K> Map<K, String>.requireValue(key: K): String = get(key)
    ?: throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", null)
