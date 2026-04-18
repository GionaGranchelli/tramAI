package io.aurora.core.model

/**
 * Role associated with a chat message exchanged with a provider.
 */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

/**
 * Single chat message sent to, or returned from, a provider.
 */
data class Message(
    /** Semantic role of the message. */
    val role: MessageRole,
    /** Plain-text content for the message. */
    val content: String,
    /** Optional tool-call identifier when [role] is [MessageRole.TOOL]. */
    val toolCallId: String? = null,
    /** Optional model-initiated tool calls when [role] is [MessageRole.ASSISTANT]. */
    val toolCalls: List<ToolCall>? = null,
)
