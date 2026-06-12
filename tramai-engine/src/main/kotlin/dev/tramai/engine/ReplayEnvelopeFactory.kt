package dev.tramai.engine

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.core.approval.Sha256Digest

/**
 * Sentinel value replacing raw tool-call arguments in the replay envelope.
 * The value must be a fixed, non-null, non-empty string that is not a valid JSON payload.
 * This prevents the persisted envelope from containing executable raw arguments.
 */
internal const val REDACTED_APPROVAL_CONTINUATION_ARGUMENTS = "__redacted_approval_continuation_args__"

/**
 * Factory for creating redacted replay envelopes at suspension time
 * and rehydrating them after claim.
 */
internal object ReplayEnvelopeFactory {

    /**
     * Creates a [SensitiveReplayEnvelope] with the suspended tool's arguments redacted.
     *
     * The returned envelope is safe for persistence (PR #29) because the selected
     * suspended tool-call slot contains only a sentinel — the real arguments
     * come from [ApprovalContinuationStore.claimForExecution] after the continuation
     * is claimed.
     */
    fun createForSuspension(
        messages: List<Message>,
        toolCallId: String,
        toolName: String,
        toolCallIndex: Int,
    ): SensitiveReplayEnvelope {
        val redacted = messages.map { msg ->
            val msgToolCalls = msg.toolCalls
            if (msg.role == MessageRole.ASSISTANT && msgToolCalls != null) {
                val redactedCalls = msgToolCalls.mapIndexed { index, tc ->
                    if (index == toolCallIndex && tc.id == toolCallId && tc.name == toolName) {
                        tc.copy(argumentsJson = REDACTED_APPROVAL_CONTINUATION_ARGUMENTS)
                    } else {
                        tc.deepCopy()
                    }
                }
                msg.copy(toolCalls = redactedCalls)
            } else {
                msg.deepCopy()
            }
        }
        return SensitiveReplayEnvelope.of(redacted)
    }

    /**
     * Rehydrates the selected suspended tool-call slot with the claimed continuation arguments.
     *
     * Must be called AFTER [ApprovalContinuationStore.claimForExecution] succeeds.
     * Validates that exactly one matching slot exists and that it contains the redacted sentinel.
     *
     * @throws IllegalStateException if the slot cannot be found or validated.
     */
    fun rehydrateAfterClaim(
        payload: ReplayPayload,
        metadata: SuspendedInvocationMetadata,
        claimedArgumentsJson: String,
    ): RehydratedReplayPayload {
        val messages = payload.messages.toMutableList()
        
        // Find the matching assistant message with tool calls
        val assistantMsgIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT && it.toolCalls != null }
        require(assistantMsgIndex >= 0) { "No assistant message with tool calls found in replay envelope" }
        
        val assistantMsg = messages[assistantMsgIndex]
        val toolCalls = checkNotNull(assistantMsg.toolCalls) { "Assistant message has null toolCalls" }
        
        // P1-5: Validate tool-call slot
        require(metadata.toolCallIndex in toolCalls.indices) {
            "replay-envelope-tool-call-index-out-of-bounds"
        }
        val selectedCall = toolCalls[metadata.toolCallIndex]
        require(selectedCall.id == metadata.toolCallId) {
            "replay-envelope-tool-call-id-mismatch"
        }
        require(selectedCall.name == metadata.toolName) {
            "replay-envelope-tool-call-name-mismatch"
        }
        require(selectedCall.argumentsJson == REDACTED_APPROVAL_CONTINUATION_ARGUMENTS) {
            "replay-envelope-tool-call-not-redacted"
        }
        
        // Exactly one matching slot
        val matchingCalls = toolCalls.filter { it.id == metadata.toolCallId && it.name == metadata.toolName }
        require(matchingCalls.size == 1) {
            "replay-envelope-duplicate-matching-calls"
        }
        
        // Rehydrate the slot
        val rehydratedCalls = toolCalls.mapIndexed { index, tc ->
            if (index == metadata.toolCallIndex) {
                tc.copy(argumentsJson = claimedArgumentsJson)
            } else {
                tc
            }
        }
        messages[assistantMsgIndex] = assistantMsg.copy(toolCalls = rehydratedCalls)
        
        return RehydratedReplayPayload(messages = messages)
    }
}

/**
 * Rehydrated replay payload with restored claimed tool arguments.
 */
data class RehydratedReplayPayload(
    val messages: List<Message>,
)
