package dev.tramai.memory.store

import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import java.util.Base64

/**
 * Shared serialization helpers used by [JdbcChatMemoryStore] and [RedisChatMemoryStore].
 *
 * These types are `internal` so they are visible within the module but not exposed
 * in the public API.
 */

internal data class StoredMessage(
    val role: String = MessageRole.USER.name,
    val content: String = "",
    val contentParts: List<StoredContentPart>? = null,
    val toolCallId: String? = null,
    val toolCalls: List<StoredToolCall>? = null,
)

internal data class StoredContentPart(
    val type: String = "text",
    val text: String? = null,
    val mimeType: String? = null,
    val data: String? = null,
)

internal data class StoredToolCall(
    val id: String = "",
    val name: String = "",
    val argumentsJson: String = "",
)

internal fun toStoredContentPart(part: ContentPart): StoredContentPart = when (part) {
    is ContentPart.TextPart -> StoredContentPart(
        type = "text",
        text = part.text,
    )
    is ContentPart.ImagePart -> StoredContentPart(
        type = "image",
        mimeType = part.mimeType,
        data = Base64.getEncoder().encodeToString(part.data),
    )
    is ContentPart.ImageUrlContent -> StoredContentPart(
        type = "image_url",
        text = part.url,
    )
}

internal fun toContentPart(part: StoredContentPart): ContentPart = when (part.type) {
    "text" -> ContentPart.TextPart(part.text ?: "")
    "image" -> {
        val mimeType = requireNotNull(part.mimeType) { "Stored image content is missing mimeType" }
        val data = requireNotNull(part.data) { "Stored image content is missing data" }
        ContentPart.ImagePart(
            mimeType = mimeType,
            data = Base64.getDecoder().decode(data),
        )
    }
    "image_url" -> ContentPart.ImageUrlContent(part.text ?: "")
    else -> throw IllegalArgumentException("Unsupported stored content part type '${part.type}'")
}

internal fun toStoredToolCall(toolCall: ToolCall): StoredToolCall = StoredToolCall(
    id = toolCall.id,
    name = toolCall.name,
    argumentsJson = toolCall.argumentsJson,
)

internal fun toToolCall(toolCall: StoredToolCall): ToolCall = ToolCall(
    id = toolCall.id,
    name = toolCall.name,
    argumentsJson = toolCall.argumentsJson,
)
