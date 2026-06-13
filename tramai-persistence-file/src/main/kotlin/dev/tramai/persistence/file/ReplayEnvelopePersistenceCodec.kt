package dev.tramai.persistence.file

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.ToolCall
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal object ReplayEnvelopePersistenceCodec {

    private const val REDACTED_APPROVAL_CONTINUATION_ARGUMENTS =
        "__redacted_approval_continuation_args__"

    fun snapshotForPersistence(
        metadata: SuspendedInvocationMetadata,
        envelope: SensitiveReplayEnvelope,
    ): List<Message> {
        val messages = envelope.revealForResume().messages
        validateEnvelope(metadata, messages)
        return messages
    }

    fun restoreFromPersistence(
        metadata: SuspendedInvocationMetadata,
        messages: List<Message>,
    ): SensitiveReplayEnvelope {
        validateEnvelope(metadata, messages)
        return SensitiveReplayEnvelope.of(messages)
    }

    private fun validateEnvelope(
        metadata: SuspendedInvocationMetadata,
        messages: List<Message>,
    ) {
        require(metadata.historySize >= 0) { "suspended-replay-envelope-history-size-negative" }
        require(messages.size > metadata.historySize) { "suspended-replay-envelope-history-size-mismatch" }
        val allSlots = messages.flatMapIndexed { messageIndex, message ->
            message.toolCalls.orEmpty().mapIndexed { toolCallIndex, call ->
                ReplayToolCallSlot(messageIndex, toolCallIndex, call)
            }
        }
        val matchingSlots = allSlots.filter { it.call.id == metadata.toolCallId }
        require(matchingSlots.size == 1) { "suspended-replay-envelope-tool-call-id-mismatch" }

        val selectedSlot = matchingSlots.single()
        require(metadata.toolCallIndex >= 0) { "suspended-replay-envelope-tool-call-index-out-of-bounds" }
        require(selectedSlot.toolCallIndex == metadata.toolCallIndex) {
            "suspended-replay-envelope-tool-call-index-mismatch"
        }
        require(selectedSlot.call.name == metadata.toolName) {
            "suspended-replay-envelope-tool-call-name-mismatch"
        }

        // The redacted slot must be in the latest assistant message with tool calls
        val latestAssistantIdx = messages.indexOfLast {
            it.role == dev.tramai.core.model.MessageRole.ASSISTANT && it.toolCalls != null
        }
        require(latestAssistantIdx >= 0) { "suspended-replay-envelope-assistant-batch-not-found" }
        require(selectedSlot.messageIndex == latestAssistantIdx) {
            "suspended-replay-envelope-tool-call-slot-mismatch"
        }

        val sentinelSlots = allSlots.filter {
            it.call.argumentsJson == REDACTED_APPROVAL_CONTINUATION_ARGUMENTS
        }
        require(sentinelSlots.size == 1) {
            "suspended-replay-envelope-redaction-count-mismatch"
        }
        val sentinelSlot = sentinelSlots.single()
        require(
            sentinelSlot.messageIndex == selectedSlot.messageIndex &&
                sentinelSlot.toolCallIndex == selectedSlot.toolCallIndex,
        ) {
            "suspended-replay-envelope-redaction-count-mismatch"
        }

        val digest = computeReplayEnvelopeDigest(metadata.operationReference, messages)
        require(digest == metadata.replayEnvelopeDigest) {
            "suspended-replay-envelope-digest-mismatch"
        }
    }

    private fun computeReplayEnvelopeDigest(
        operationReference: ResumeOperationReference,
        messages: List<Message>,
    ): Sha256Digest {
        val canonical = buildString {
            appendField("service_interface", operationReference.serviceInterface)
            append("method=").append(operationReference.methodName).append('\n')
            append("jvm_descriptor=").append(operationReference.jvmMethodDescriptor).append('\n')
            append("digest=").append(operationReference.resumeDefinitionDigest.value).append('\n')
            append(encodeCanonicalMessages(messages))
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return Sha256Digest.of("sha256:$hex")
    }

    private fun encodeCanonicalMessages(messages: List<Message>): String = buildString {
        messages.forEachIndexed { index, message ->
            if (index > 0) append("\n---\n")
            append("role=").append(message.role.name).append('\n')
            appendField("content", message.content)
            append("parts_count=").append(message.contentParts?.size ?: 0).append('\n')
            message.contentParts.orEmpty().forEachIndexed { partIndex, part ->
                append("part_index=").append(partIndex).append('\n')
                when (part) {
                    is ContentPart.TextPart -> {
                        append("part_type=text\n")
                        appendField("text", part.text)
                    }
                    is ContentPart.ImagePart -> {
                        append("part_type=image\n")
                        appendField("mime", part.mimeType)
                        appendField("data_b64", Base64.getEncoder().encodeToString(part.data))
                    }
                    is ContentPart.ImageUrlContent -> {
                        append("part_type=image_url\n")
                        appendField("url", part.url)
                        appendField("mime", part.mimeType)
                    }
                }
            }
            if (message.toolCallId != null) {
                appendField("tool_call_id", message.toolCallId)
            }
            message.toolCalls?.let { toolCalls ->
                append("tool_calls_count=").append(toolCalls.size).append('\n')
                toolCalls.forEachIndexed { toolIndex, toolCall ->
                    append("tool_call_index=").append(toolIndex).append('\n')
                    appendField("tool_call_id", toolCall.id)
                    appendField("tool_call_name", toolCall.name)
                    appendField("tool_call_args", toolCall.argumentsJson)
                }
            }
        }
    }

    private fun StringBuilder.appendField(name: String, value: String?) {
        if (value == null) {
            append(name).append("_null\n")
            return
        }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        append(name).append("_len=").append(bytes.size).append('\n')
        append(value).append('\n')
    }

    private data class ReplayToolCallSlot(
        val messageIndex: Int,
        val toolCallIndex: Int,
        val call: ToolCall,
    )
}
