package dev.tramai.testing.persistence.memory

import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall

/**
 * Epic 8.1h: fixtures for the shared
 * [dev.tramai.core.memory.ChatMemoryStore] compatibility contract.
 * Deterministic only — explicit identities and clock-controlled
 * timestamps. No sleeps, no real clock.
 *
 * Message construction respects the core invariant: `content` OR
 * `contentParts`, never both.
 */
object ChatMemoryStoreFixtures {

    const val T0: Long = 1_800_000_000_000L

    const val CONVERSATION: String = "conversation-1"

    fun text(content: String, role: MessageRole = MessageRole.USER): Message =
        Message(role = role, content = content)

    fun system(content: String): Message = Message(role = MessageRole.SYSTEM, content = content)

    fun user(content: String): Message = Message(role = MessageRole.USER, content = content)

    fun assistant(content: String): Message = Message(role = MessageRole.ASSISTANT, content = content)

    fun tool(toolCallId: String, content: String): Message =
        Message(role = MessageRole.TOOL, content = content, toolCallId = toolCallId)

    /** Rich message carrying content parts (content must stay blank). */
    fun rich(parts: List<ContentPart>): Message =
        Message(role = MessageRole.USER, content = "", contentParts = parts)

    /** Assistant message carrying tool calls (content must stay blank). */
    fun assistantToolCalls(calls: List<ToolCall>): Message =
        Message(role = MessageRole.ASSISTANT, content = "", toolCalls = calls)

    fun toolCall(id: String, name: String, argumentsJson: String): ToolCall =
        ToolCall(id = id, name = name, argumentsJson = argumentsJson)

    /** The full-fidelity discriminator: URL image with a MIME type hint. */
    fun urlImageMessage(
        url: String = "https://example.test/image.webp",
        mimeType: String? = "image/webp",
    ): Message = rich(listOf(ContentPart.ImageUrlContent(url = url, mimeType = mimeType)))
}
