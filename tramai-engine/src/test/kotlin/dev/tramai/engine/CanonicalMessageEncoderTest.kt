package dev.tramai.engine

import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Direct contract tests for [CanonicalMessageEncoder].
 *
 * The encoder feeds request digests and replay-envelope fingerprints, so its
 * output shape is part of the anti-tamper contract: any change to the emitted
 * canonical text changes every downstream digest. These tests pin the exact
 * encoding for every supported field kind.
 */
class CanonicalMessageEncoderTest {
    @Test
    fun `encodes a plain text message with role and content length`() {
        val encoded = CanonicalMessageEncoder.encode(listOf(Message.text("hi")))

        assertThat(encoded).isEqualTo(
            """
            role=USER
            content_len=2
            hi
            parts_count=0
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun `separates consecutive messages with the canonical delimiter`() {
        val encoded =
            CanonicalMessageEncoder.encode(
                listOf(
                    Message(role = MessageRole.SYSTEM, content = "sys"),
                    Message(role = MessageRole.USER, content = "u"),
                ),
            )

        assertThat(encoded).contains("\n---\n")
        assertThat(encoded.lines().first()).isEqualTo("role=SYSTEM")
        assertThat(encoded.lines()).contains("role=USER")
    }

    @Test
    fun `encodes a text content part with part index and type`() {
        val message =
            Message(
                role = MessageRole.USER,
                content = "",
                contentParts = listOf(ContentPart.TextPart("part-text")),
            )

        val encoded = CanonicalMessageEncoder.encode(listOf(message))

        assertThat(encoded).contains("parts_count=1")
        assertThat(encoded).contains("part_index=0")
        assertThat(encoded).contains("part_type=text")
        assertThat(encoded).contains("text_len=9")
        assertThat(encoded).contains("part-text")
    }

    @Test
    fun `encodes an inline image part as base64 with mime type`() {
        val message =
            Message(
                role = MessageRole.USER,
                content = "",
                contentParts = listOf(ContentPart.ImagePart("image/png", byteArrayOf(1, 2, 3))),
            )

        val encoded = CanonicalMessageEncoder.encode(listOf(message))

        assertThat(encoded).contains("part_type=image")
        assertThat(encoded).contains("mime_len=9")
        assertThat(encoded).contains("image/png")
        assertThat(encoded).contains("data_b64_len=4")
        assertThat(encoded).contains("AQID") // base64 of [1, 2, 3]
    }

    @Test
    fun `encodes multiple content parts with distinct indices`() {
        val message =
            Message(
                role = MessageRole.USER,
                content = "",
                contentParts =
                    listOf(
                        ContentPart.TextPart("first"),
                        ContentPart.ImageUrlContent("https://example.com/b.png"),
                    ),
            )

        val encoded = CanonicalMessageEncoder.encode(listOf(message))

        assertThat(encoded).contains("parts_count=2")
        assertThat(encoded).contains("part_index=0")
        assertThat(encoded).contains("part_type=text")
        assertThat(encoded).contains("part_index=1")
        assertThat(encoded).contains("part_type=image_url")
    }

    @Test
    fun `encodes an image URL part with mime hint`() {
        val message =
            Message(
                role = MessageRole.USER,
                content = "",
                contentParts = listOf(ContentPart.ImageUrlContent("https://example.com/a.png", "image/png")),
            )

        val encoded = CanonicalMessageEncoder.encode(listOf(message))

        assertThat(encoded).contains("part_type=image_url")
        assertThat(encoded).contains("url_len=25")
        assertThat(encoded).contains("https://example.com/a.png")
        assertThat(encoded).contains("mime_len=9")
    }

    @Test
    fun `encodes a tool call id and assistant tool calls`() {
        val message =
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls =
                    listOf(
                        ToolCall(id = "call-1", name = "lookup", argumentsJson = "{}"),
                    ),
            )

        val encoded = CanonicalMessageEncoder.encode(listOf(message))

        assertThat(encoded).contains("tool_calls_count=1")
        assertThat(encoded).contains("tool_call_index=0")
        assertThat(encoded).contains("tool_call_id_len=6")
        assertThat(encoded).contains("call-1")
        assertThat(encoded).contains("tool_call_name_len=6")
        assertThat(encoded).contains("lookup")
        assertThat(encoded).contains("tool_call_args_len=2")
        assertThat(encoded).contains("{}")
    }

    @Test
    fun `omits tool call id field entirely when absent`() {
        val encoded =
            CanonicalMessageEncoder.encode(
                listOf(Message(role = MessageRole.TOOL, content = "result", toolCallId = null)),
            )

        assertThat(encoded).doesNotContain("tool_call_id")
    }

    @Test
    fun `encodes a message-level tool call id for TOOL role`() {
        val message =
            Message(
                role = MessageRole.TOOL,
                content = "result",
                toolCallId = "tc-9",
            )

        val encoded = CanonicalMessageEncoder.encode(listOf(message))

        assertThat(encoded).contains("tool_call_id_len=4")
        assertThat(encoded).contains("tc-9")
    }

    @Test
    fun `appends multiple tool calls with distinct indices`() {
        val message =
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls =
                    listOf(
                        ToolCall(id = "a", name = "f1", argumentsJson = "{}"),
                        ToolCall(id = "b", name = "f2", argumentsJson = "{}"),
                    ),
            )

        val encoded = CanonicalMessageEncoder.encode(listOf(message))

        assertThat(encoded).contains("tool_call_index=0")
        assertThat(encoded).contains("tool_call_index=1")
    }

    @Test
    fun `encoding is deterministic across calls`() {
        val messages =
            listOf(
                Message.text("same"),
                Message(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(ToolCall("c", "f", "{}"))),
            )

        assertThat(CanonicalMessageEncoder.encode(messages))
            .isEqualTo(CanonicalMessageEncoder.encode(messages))
    }

    @Test
    fun `encodes non-ASCII content using UTF-8 byte length`() {
        val encoded = CanonicalMessageEncoder.encode(listOf(Message.text("héllo")))

        assertThat(encoded).contains("content_len=6") // h é l l o in UTF-8: 1+2+1+1+1
        assertThat(encoded).contains("héllo")
    }
}
