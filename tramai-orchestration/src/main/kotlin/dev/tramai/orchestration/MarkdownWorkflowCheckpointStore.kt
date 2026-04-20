@file:OptIn(ExperimentalTramAIOrchestration::class)

package dev.tramai.orchestration

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max

/**
 * Markdown-backed checkpoint store for audit-friendly local persistence.
 */
@ExperimentalTramAIOrchestration
class MarkdownWorkflowCheckpointStore(
    private val rootDirectory: Path,
    private val pathStrategy: WorkflowCheckpointPathStrategy = DefaultWorkflowCheckpointPathStrategy("checkpoint.md"),
) : WorkflowCheckpointStore {
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
                decodeMarkdownCheckpoint(Files.readString(checkpointPath))
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
                decodeMarkdownCheckpoint(Files.readString(checkpointPath))
            } else {
                null
            }
            validateExpectedRevision(
                workflowName = checkpoint.workflowName,
                workflowId = checkpoint.workflowId,
                existing = existing,
                expectedRevision = expectedRevision,
            )

            val persisted = checkpoint.copy(revision = (existing?.revision ?: 0) + 1)
            writeStringAtomically(checkpointPath, encodeMarkdownCheckpoint(persisted))
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
                decodeMarkdownCheckpoint(Files.readString(checkpointPath))
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

    private fun checkpointPath(
        workflowName: String,
        workflowId: String,
    ): Path = pathStrategy.resolve(rootDirectory, workflowName, workflowId)
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

internal fun decodeMarkdownCheckpoint(content: String): WorkflowCheckpoint {
    val lines = content.lines()
    require(lines.firstOrNull() == "---") { "Markdown checkpoint is missing opening front matter" }

    val closingIndex = lines.drop(1).indexOfFirst { it == "---" }
        .takeIf { it >= 0 }
        ?.plus(1)
        ?: -1
    require(closingIndex > 0) { "Markdown checkpoint is missing closing front matter" }

    val frontMatter = lines.subList(1, closingIndex)
        .filter { it.isNotBlank() }
        .associate { line ->
            val separatorIndex = line.indexOf(": ")
            require(separatorIndex > 0) { "Malformed markdown checkpoint front matter line '$line'" }
            line.substring(0, separatorIndex) to line.substring(separatorIndex + 2)
        }

    val payloadHeaderIndex = lines.indexOfFirst { it == "## State Payload" }
    require(payloadHeaderIndex >= 0) { "Markdown checkpoint is missing state payload heading" }

    val fenceLineIndex = ((payloadHeaderIndex + 1) until lines.size)
        .firstOrNull { lines[it].startsWith("```") }
        ?: -1
    require(fenceLineIndex > payloadHeaderIndex) { "Markdown checkpoint is missing opening payload fence" }

    val fence = lines[fenceLineIndex].substringBefore(" ")
    val closingFenceIndex = ((fenceLineIndex + 1) until lines.size)
        .firstOrNull { lines[it] == fence }
        ?: -1
    require(closingFenceIndex > fenceLineIndex) { "Markdown checkpoint is missing closing payload fence" }

    val payload = lines.subList(fenceLineIndex + 1, closingFenceIndex).joinToString("\n")
    val metadata = frontMatter.entries
        .filter { it.key.startsWith("metadata.") }
        .associate { entry ->
            base64Decode(entry.key.removePrefix("metadata.")) to base64Decode(entry.value)
        }

    return WorkflowCheckpoint(
        workflowName = base64Decode(frontMatter.requireValue("workflowName")),
        workflowId = base64Decode(frontMatter.requireValue("workflowId")),
        nextStepIndex = frontMatter.requireValue("nextStepIndex").toInt(),
        stepExecutions = frontMatter.requireValue("stepExecutions").toInt(),
        lastCompletedStepName = base64Decode(frontMatter.requireValue("lastCompletedStepName")).ifBlank { null },
        statePayload = payload,
        revision = frontMatter.requireValue("revision").toLong(),
        metadata = metadata,
        savedAtEpochMillis = frontMatter.requireValue("savedAtEpochMillis").toLong(),
    )
}

private fun markdownFence(content: String): String {
    val longestFence = Regex("`+")
        .findAll(content)
        .maxOfOrNull { it.value.length }
        ?: 0
    return "`".repeat(max(3, longestFence + 1))
}

private fun <K> Map<K, String>.requireValue(key: K): String = get(key)
    ?: error("Missing markdown checkpoint field '$key'")
