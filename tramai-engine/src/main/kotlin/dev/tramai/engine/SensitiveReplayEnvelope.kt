package dev.tramai.engine

import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.ToolCall

/**
 * Opaque, non-serializable envelope containing replayable message history.
 *
 * Historical message-level ToolCall values may exist for provider continuity.
 * The selected suspended ToolCall arguments are replaced by a sentinel
 * until rehydrated from claimed continuation arguments after claim.
 *
 * The envelope never stores executable runtime objects such as:
 * OperationDefinition, ResolvedTool, Method, callbacks, providers, or registries.
 *
 * [toString] returns [REDACTED].
 * Only accessible via [revealForResume] inside a trusted code path after claim.
 * Defensive deep copies prevent mutation after creation.
 */
class SensitiveReplayEnvelope private constructor(
    private val messages: List<Message>,
) {
    /**
     * Reveals a safe deep copy of the enclosed messages for the resume code path.
     *
     * Must only be called after [ApprovalContinuationStore.claimForExecution] succeeds.
     */
    fun revealForResume(): ReplayPayload =
        ReplayPayload(messages = messages.deepCopy())

    override fun toString(): String = "[REDACTED]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SensitiveReplayEnvelope) return false
        return messages == other.messages
    }

    override fun hashCode(): Int = messages.hashCode()

    companion object {
        /**
         * Wraps messages in a defensive deep copy.
         */
        fun of(messages: List<Message>): SensitiveReplayEnvelope {
            val defensiveCopy = messages.deepCopy()
            return SensitiveReplayEnvelope(defensiveCopy)
        }
    }
}

/**
 * Safe payload returned by [SensitiveReplayEnvelope.revealForResume].
 *
 * Contains only the messages needed for the provider loop after resume.
 */
data class ReplayPayload(
    val messages: List<Message>,
)

/**
 * Deterministic deep copy for [Message] and its transitive types.
 */
internal fun List<Message>.deepCopy(): List<Message> = map { it.deepCopy() }

internal fun Message.deepCopy(): Message = copy(
    content = content,
    contentParts = contentParts?.map { it.deepCopy() },
    toolCalls = toolCalls?.map { it.deepCopy() },
)

internal fun ContentPart.deepCopy(): ContentPart = when (this) {
    is ContentPart.TextPart -> copy(text = text)
    is ContentPart.ImagePart -> ContentPart.ImagePart(mimeType = mimeType, data = data.copyOf())
    is ContentPart.ImageUrlContent -> copy(url = url, mimeType = mimeType)
}

internal fun ToolCall.deepCopy(): ToolCall = copy(
    id = id,
    name = name,
    argumentsJson = argumentsJson,
)
