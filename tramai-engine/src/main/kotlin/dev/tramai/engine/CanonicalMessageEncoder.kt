package dev.tramai.engine

import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.ToolCall
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Deterministic canonical message encoding shared across digest helpers.
 * Length-prefixes all string fields using UTF-8 byte length for consistency.
 * Separates messages with `\n---\n`.
 */
internal object CanonicalMessageEncoder {
    fun encode(messages: List<Message>): String = buildString {
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
}

/**
 * Deterministic field encoding shared across all digest helpers.
 *
 * Encodes a nullable string field as:
 * - `<name>_null\n` when null
 * - `<name>_len=<UTF-8 byte count>\n<value>\n` when non-null
 *
 * Using UTF-8 byte length ensures consistency across platforms.
 */
internal fun StringBuilder.appendField(name: String, value: String?) {
    if (value == null) {
        append(name).append("_null\n")
        return
    }
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    append(name).append("_len=").append(bytes.size).append('\n')
    append(value).append('\n')
}
