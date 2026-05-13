package dev.tramai.core.model

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
 *
 * Messages can carry either plain-text [content] alone, or a list of [ContentPart]
 * items supporting mixed media (text + images). When [contentParts] is non-null, providers
 * that support vision content will render those parts directly.
 */
data class Message(
    /** Semantic role of the message. */
    val role: MessageRole,
    /** Plain-text content for the message. */
    val content: String,
    /** Optional multi-part content items (text, images, etc.). When set, overrides
     * the flat [content] field for providers that support rich content. */
    val contentParts: List<ContentPart>? = null,
    /** Optional tool-call identifier when [role] is [MessageRole.TOOL]. */
    val toolCallId: String? = null,
    /** Optional model-initiated tool calls when [role] is [MessageRole.ASSISTANT]. */
    val toolCalls: List<ToolCall>? = null,
) {
    init {
        require(content.isBlank() || contentParts == null) {
            "Use content OR contentParts, not both"
        }
    }

    companion object {
        /**
         * Creates a simple user text message.
         */
        fun text(content: String) = Message(role = MessageRole.USER, content = content)
    }

    /**
     * Returns true when at least one part is an [ContentPart.ImagePart].
     */
    fun hasImage(): Boolean = contentParts?.any { it is ContentPart.ImagePart } == true
}
