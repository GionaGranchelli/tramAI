package dev.tramai.engine

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall

/**
 * Shared replay-envelope invariant validation (Epic 8.1c).
 *
 * Mirrors the semantics [ReplayEnvelopeFactory.prepareForSuspension]
 * guarantees at suspension time and that the file store re-validates before
 * persistence: the selected suspended tool call must be globally unique,
 * sit in the latest assistant tool-call batch at the metadata index, carry
 * the redaction sentinel in its arguments (exactly one sentinel, at the
 * selected slot), and the envelope must be consistent with the metadata
 * history size. Raw selected tool arguments never belong in a replay
 * envelope — they live behind the ApprovalContinuationStore boundary.
 *
 * Internal to the engine module; the JDBC store mirrors these checks inline
 * (cross-module), and the shared TCK pins the behavior so the copies cannot
 * drift.
 */
internal object ReplayEnvelopeValidator {

    fun validate(metadata: SuspendedInvocationMetadata, messages: List<Message>) {
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

        val latestAssistantIdx = messages.indexOfLast {
            it.role == MessageRole.ASSISTANT && !it.toolCalls.isNullOrEmpty()
        }
        require(latestAssistantIdx >= 0) { "suspended-replay-envelope-assistant-batch-not-found" }
        require(selectedSlot.messageIndex == latestAssistantIdx) {
            "suspended-replay-envelope-tool-call-slot-mismatch"
        }

        val sentinelSlots = allSlots.filter {
            it.call.argumentsJson == REDACTED_APPROVAL_CONTINUATION_ARGUMENTS
        }
        require(sentinelSlots.size == 1) { "suspended-replay-envelope-redaction-count-mismatch" }
        val sentinelSlot = sentinelSlots.single()
        require(
            sentinelSlot.messageIndex == selectedSlot.messageIndex &&
                sentinelSlot.toolCallIndex == selectedSlot.toolCallIndex,
        ) {
            "suspended-replay-envelope-redaction-count-mismatch"
        }
    }

    private data class ReplayToolCallSlot(
        val messageIndex: Int,
        val toolCallIndex: Int,
        val call: ToolCall,
    )
}
