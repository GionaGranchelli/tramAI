package dev.tramai.bedrock

import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.Base64
import kotlin.test.Test

class BedrockProviderTest {

    @Test
    fun `converts image parts to bedrock claude content block format`() {
        val imageData = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val provider = BedrockProvider(region = "us-west-2")

        val message = Message(
            role = MessageRole.USER,
            content = "",
            contentParts = listOf(
                ContentPart.TextPart("Describe this image"),
                ContentPart.ImagePart(
                    mimeType = "image/jpeg",
                    data = imageData,
                ),
            ),
        )

        val result = provider.buildClaudeMessage(message)

        @Suppress("UNCHECKED_CAST")
        val content = result["content"] as List<Map<String, Any?>>
        assertThat(content).hasSize(2)

        val textBlock = content[0]
        assertThat(textBlock["type"]).isEqualTo("text")
        assertThat(textBlock["text"]).isEqualTo("Describe this image")

        val imageBlock = content[1]
        assertThat(imageBlock["type"]).isEqualTo("image")
        @Suppress("UNCHECKED_CAST")
        val source = imageBlock["source"] as Map<String, Any?>
        assertThat(source["type"]).isEqualTo("base64")
        assertThat(source["media_type"]).isEqualTo("image/jpeg")
        assertThat(source["data"]).isEqualTo(Base64.getEncoder().encodeToString(imageData))
    }

    @Test
    fun `keeps plain string content when no image parts are present`() {
        val provider = BedrockProvider(region = "us-west-2")

        val message = Message(
            role = MessageRole.USER,
            content = "say hello",
        )

        val result = provider.buildClaudeMessage(message)

        @Suppress("UNCHECKED_CAST")
        val content = result["content"] as List<Map<String, Any?>>
        assertThat(content).hasSize(1)
        val textBlock = content[0]
        assertThat(textBlock["type"]).isEqualTo("text")
        assertThat(textBlock["text"]).isEqualTo("say hello")
    }

    @Test
    fun `rejects unsupported image mime type`() {
        val imageData = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val provider = BedrockProvider(region = "us-west-2")

        val message = Message(
            role = MessageRole.USER,
            content = "",
            contentParts = listOf(
                ContentPart.ImagePart(
                    mimeType = "image/bmp",
                    data = imageData,
                ),
            ),
        )

        assertThatThrownBy { provider.buildClaudeMessage(message) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unsupported image mimeType")
    }

    @Test
    fun `maps user role correctly`() {
        val provider = BedrockProvider(region = "us-west-2")

        val message = Message(
            role = MessageRole.USER,
            content = "hello",
        )

        val result = provider.buildClaudeMessage(message)
        assertThat(result["role"]).isEqualTo("user")
    }

    @Test
    fun `maps assistant role correctly`() {
        val provider = BedrockProvider(region = "us-west-2")

        val message = Message(
            role = MessageRole.ASSISTANT,
            content = "hello",
        )

        val result = provider.buildClaudeMessage(message)
        assertThat(result["role"]).isEqualTo("assistant")
    }

    @Test
    fun `provider id is bedrock`() {
        val provider = BedrockProvider(region = "us-west-2")
        assertThat(provider.providerId()).isEqualTo("bedrock")
    }
}
