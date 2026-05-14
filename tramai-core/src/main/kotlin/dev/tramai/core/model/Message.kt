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
    fun hasImage(): Boolean = contentParts?.any { it is ContentPart.ImagePart || it is ContentPart.ImageUrlContent } == true

    /**
     * Estimates the number of tokens consumed by images in this message.
     * Uses the OpenAI formula: 170 tokens per 224x224 tile.
     * Without image dimensions, estimates 1 tile (170 tokens).
     */
    fun estimateImageTokens(imageDetail: ImageDetail = ImageDetail.AUTO): Int {
        val imageParts = contentParts?.filterIsInstance<ContentPart.ImagePart>() ?: emptyList()
        val urlParts = contentParts?.filterIsInstance<ContentPart.ImageUrlContent>() ?: emptyList()
        val totalImages = imageParts.size + urlParts.size
        if (totalImages == 0) return 0
        return when (imageDetail) {
            ImageDetail.LOW -> totalImages * 85  // fixed low-res cost
            ImageDetail.HIGH -> totalImages * 170  // 1 tile estimate
            ImageDetail.AUTO -> totalImages * 170  // default to 1 tile
        }
    }
}
