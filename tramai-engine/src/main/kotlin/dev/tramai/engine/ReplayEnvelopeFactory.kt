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
 * Result of [ReplayEnvelopeFactory.prepareForSuspension].
 *
 * @property envelope The redacted [SensitiveReplayEnvelope] safe for persistence.
 * @property digest Digest of the exact redacted snapshot for tamper detection.
 */
internal data class PreparedReplayEnvelope(
    val envelope: SensitiveReplayEnvelope,
    val digest: Sha256Digest,
)

/**
 * Factory for creating redacted replay envelopes at suspension time
 * and rehydrating them after claim.
 */
internal object ReplayEnvelopeFactory {

    /**
     * Creates a [PreparedReplayEnvelope] with the suspended tool's arguments redacted.
     *
     * The returned envelope is safe for persistence because the selected
     * suspended tool-call slot contains only a sentinel — the real arguments
     * come from [dev.tramai.core.approval.ApprovalContinuationStore.claimForExecution]
     * after the continuation is claimed.
     *
     * Fail-closed validation:
     * - Selects latest assistant message with tool calls
     * - Validates toolCallIndex is in bounds
     * - Validates selected call ID matches expected ID
     * - Validates selected call name matches expected name
     * - Requires EXACTLY ONE matching slot across the entire envelope
     * - Redacts exactly that one slot
     * - After redaction, requires exactly one sentinel present
     * - Computes digest from the EXACT redacted snapshot
     *
     * All error messages are fixed codes (no interpolated values).
     */
    fun prepareForSuspension(
        operationReference: ResumeOperationReference,
        messages: List<Message>,
        toolCallId: String,
        toolName: String,
        toolCallIndex: Int,
    ): PreparedReplayEnvelope {
        // Select latest assistant message with tool calls (fail closed if none)
        val assistantMsgIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT && it.toolCalls != null }
        require(assistantMsgIndex >= 0) { "replay-envelope-assistant-batch-not-found" }

        val assistantMsg = messages[assistantMsgIndex]
        val toolCalls = checkNotNull(assistantMsg.toolCalls) { "replay-envelope-assistant-batch-not-found" }

        // Validate toolCallIndex is in bounds
        require(toolCallIndex in toolCalls.indices) { "replay-envelope-tool-call-index-out-of-bounds" }

        // Validate selected call ID matches expected ID
        val selectedCall = toolCalls[toolCallIndex]
        require(selectedCall.id == toolCallId) { "replay-envelope-tool-call-id-mismatch" }

        // Validate selected call name matches expected name
        require(selectedCall.name == toolName) { "replay-envelope-tool-call-name-mismatch" }

        // Require EXACTLY ONE matching slot across the entire envelope
        val matchingCalls = toolCalls.filter { it.id == toolCallId && it.name == toolName }
        require(matchingCalls.size == 1) { "replay-envelope-duplicate-matching-calls" }

        // Redact exactly that one slot
        val redacted = redactSlot(messages, assistantMsgIndex, toolCallIndex)

        // After redaction, require exactly one sentinel present
        val sentinelCount = countSentinelOccurrences(redacted)
        require(sentinelCount == 1) { "replay-envelope-redaction-count-mismatch" }

        // Compute digest from the EXACT redacted snapshot
        val envelope = SensitiveReplayEnvelope.of(redacted)
        val digest = ReplayEnvelopeDigestHelper.compute(operationReference, redacted)

        return PreparedReplayEnvelope(envelope = envelope, digest = digest)
    }

    /**
     * Rehydrates the selected suspended tool-call slot with the claimed continuation arguments.
     *
     * Must be called AFTER [dev.tramai.core.approval.ApprovalContinuationStore.claimForExecution] succeeds.
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
        require(assistantMsgIndex >= 0) { "replay-envelope-assistant-batch-not-found" }

        val assistantMsg = messages[assistantMsgIndex]
        val toolCalls = checkNotNull(assistantMsg.toolCalls) { "replay-envelope-assistant-batch-not-found" }

        // Validate tool-call slot
        require(metadata.toolCallIndex in toolCalls.indices) { "replay-envelope-tool-call-index-out-of-bounds" }
        val selectedCall = toolCalls[metadata.toolCallIndex]
        require(selectedCall.id == metadata.toolCallId) { "replay-envelope-tool-call-id-mismatch" }
        require(selectedCall.name == metadata.toolName) { "replay-envelope-tool-call-name-mismatch" }
        require(selectedCall.argumentsJson == REDACTED_APPROVAL_CONTINUATION_ARGUMENTS) { "replay-envelope-tool-call-not-redacted" }

        // Exactly one matching slot
        val matchingCalls = toolCalls.filter { it.id == metadata.toolCallId && it.name == metadata.toolName }
        require(matchingCalls.size == 1) { "replay-envelope-duplicate-matching-calls" }

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

    /**
     * Redacts the tool call at [toolCallIndex] in the assistant message at [assistantMsgIndex].
     */
    private fun redactSlot(
        messages: List<Message>,
        assistantMsgIndex: Int,
        toolCallIndex: Int,
    ): List<Message> {
        return messages.mapIndexed { index, msg ->
            if (index == assistantMsgIndex) {
                val msgToolCalls = msg.toolCalls
                if (msgToolCalls != null) {
                    val redactedCalls = msgToolCalls.mapIndexed { tcIndex, tc ->
                        if (tcIndex == toolCallIndex) {
                            tc.copy(argumentsJson = REDACTED_APPROVAL_CONTINUATION_ARGUMENTS)
                        } else {
                            tc.deepCopy()
                        }
                    }
                    msg.copy(toolCalls = redactedCalls)
                } else {
                    msg.deepCopy()
                }
            } else {
                msg.deepCopy()
            }
        }
    }

    /**
     * Counts occurrences of the redacted sentinel across all tool calls in all messages.
     */
    private fun countSentinelOccurrences(messages: List<Message>): Int {
        return messages.sumOf { msg ->
            msg.toolCalls?.count { it.argumentsJson == REDACTED_APPROVAL_CONTINUATION_ARGUMENTS } ?: 0
        }
    }
}

/**
 * Rehydrated replay payload with restored claimed tool arguments.
 */
data class RehydratedReplayPayload(
    val messages: List<Message>,
)
