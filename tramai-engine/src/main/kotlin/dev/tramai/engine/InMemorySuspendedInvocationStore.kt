package dev.tramai.engine

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [SuspendedInvocationStore].
 *
 * Stores both safe metadata and replay envelope in a single [ConcurrentHashMap] keyed by [approvalId].
 * Does NOT persist beyond the JVM lifecycle (process-local, per the SPI contract:
 * durability is implementation-specific).
 *
 * Enforces the shared contract validations (matching the file and JDBC stores):
 * - ID fields must be non-blank, free of control characters, ≤ 256 chars, and
 *   without surrounding whitespace on every operation.
 * - [create] rejects a replay envelope that does not bind to the metadata
 *   (no assistant tool-call batch, toolCallIndex out of bounds, toolCallId /
 *   toolName mismatch) and a [SuspendedInvocationMetadata.replayEnvelopeDigest]
 *   that does not match the canonical digest of the envelope messages.
 */
internal class InMemorySuspendedInvocationStore : SuspendedInvocationStore {

    private data class StoredSuspendedInvocation(
        val metadata: SuspendedInvocationMetadata,
        val replayEnvelope: SensitiveReplayEnvelope,
    )

    private val invocations = ConcurrentHashMap<String, StoredSuspendedInvocation>()

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        validateCreateInput(metadata, replayEnvelope)
        val existing = invocations.putIfAbsent(
            metadata.approvalId,
            StoredSuspendedInvocation(metadata = metadata, replayEnvelope = replayEnvelope),
        )
        require(existing == null) {
            "Suspended invocation with approvalId '${metadata.approvalId}' already exists"
        }
    }

    override suspend fun get(approvalId: String): SuspendedInvocationMetadata? {
        validateIdField(approvalId, "approvalId")
        return invocations[approvalId]?.metadata
    }

    override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? {
        validateIdField(approvalId, "approvalId")
        return invocations[approvalId]?.replayEnvelope
    }

    override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
        validateIdField(approvalId, "approvalId")
        return invocations.remove(approvalId)?.metadata
    }

    private fun validateCreateInput(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        validateIdField(metadata.approvalId, "approvalId")
        validateIdField(metadata.toolCallId, "toolCallId")
        validateIdField(metadata.toolName, "toolName")
        validateIdField(metadata.correlationId, "correlationId")
        metadata.conversationId?.let { validateIdField(it, "conversationId") }

        val messages = replayEnvelope.revealForResume().messages
        validateEnvelopeBinding(metadata, messages)
        val canonical = ReplayEnvelopeDigestHelper.compute(metadata.operationReference, messages)
        require(canonical == metadata.replayEnvelopeDigest) {
            "replay-envelope-digest-mismatch: canonical=$canonical, provided=${metadata.replayEnvelopeDigest}"
        }
    }

    private fun validateEnvelopeBinding(
        metadata: SuspendedInvocationMetadata,
        messages: List<Message>,
    ) {
        val assistantMsg = messages.lastOrNull { it.role == MessageRole.ASSISTANT && !it.toolCalls.isNullOrEmpty() }
            ?: throw IllegalArgumentException("replay-envelope-no-assistant-tool-calls")
        val toolCalls = checkNotNull(assistantMsg.toolCalls)
        require(metadata.toolCallIndex in toolCalls.indices) {
            "replay-envelope-tool-call-index-out-of-bounds"
        }
        val selectedCall = toolCalls[metadata.toolCallIndex]
        require(selectedCall.id == metadata.toolCallId) { "replay-envelope-tool-call-id-mismatch" }
        require(selectedCall.name == metadata.toolName) { "replay-envelope-tool-call-name-mismatch" }
    }

    private fun validateIdField(value: String, fieldName: String) {
        require(value.isNotBlank()) { "$fieldName must not be blank" }
        require(value.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
        require(value.length <= 256) { "$fieldName exceeds maximum length of 256" }
        require(value == value.trim()) { "$fieldName must not contain surrounding whitespace" }
    }
}

fun inMemorySuspendedInvocationStore(): SuspendedInvocationStore =
    InMemorySuspendedInvocationStore()
