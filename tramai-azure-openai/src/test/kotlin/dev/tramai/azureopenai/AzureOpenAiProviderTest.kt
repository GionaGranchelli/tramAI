package dev.tramai.azureopenai

import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.Base64
import kotlin.test.Test

class AzureOpenAiProviderTest {

    @Test
    fun `converts image parts to azure openai content array format`() {
        val imageData = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

        val message = Message(
            role = MessageRole.USER,
            content = "",
            contentParts = listOf(
                ContentPart.TextPart("What's in this image?"),
                ContentPart.ImagePart(
                    mimeType = "image/png",
                    data = imageData,
                ),
            ),
        )

        val result = provider.messageToMap(message)

        @Suppress("UNCHECKED_CAST")
        val content = result["content"] as List<Map<String, Any?>>
        assertThat(content).hasSize(2)

        val textBlock = content[0]
        assertThat(textBlock["type"]).isEqualTo("text")
        assertThat(textBlock["text"]).isEqualTo("What's in this image?")

        val imageBlock = content[1]
        assertThat(imageBlock["type"]).isEqualTo("image_url")
        @Suppress("UNCHECKED_CAST")
        val imageUrl = imageBlock["image_url"] as Map<String, Any?>
        assertThat(imageUrl["url"]).isEqualTo("data:image/png;base64,${Base64.getEncoder().encodeToString(imageData)}")
    }

    @Test
    fun `converts image url content to image_url format`() {
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

        val message = Message(
            role = MessageRole.USER,
            content = "",
            contentParts = listOf(
                ContentPart.TextPart("Analyze this"),
                ContentPart.ImageUrlContent("https://example.com/photo.jpg"),
            ),
        )

        val result = provider.messageToMap(message)

        @Suppress("UNCHECKED_CAST")
        val content = result["content"] as List<Map<String, Any?>>
        assertThat(content).hasSize(2)

        val imageBlock = content[1]
        assertThat(imageBlock["type"]).isEqualTo("image_url")
        @Suppress("UNCHECKED_CAST")
        val imageUrl = imageBlock["image_url"] as Map<String, Any?>
        assertThat(imageUrl["url"]).isEqualTo("https://example.com/photo.jpg")
    }

    @Test
    fun `keeps plain string content when no parts are present`() {
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

        val message = Message(
            role = MessageRole.USER,
            content = "say hello",
        )

        val result = provider.messageToMap(message)

        assertThat(result["content"]).isEqualTo("say hello")
    }

    @Test
    fun `maps user role correctly`() {
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

        val message = Message(
            role = MessageRole.USER,
            content = "hello",
        )

        val result = provider.messageToMap(message)
        assertThat(result["role"]).isEqualTo("user")
    }

    @Test
    fun `maps assistant role correctly`() {
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

        val message = Message(
            role = MessageRole.ASSISTANT,
            content = "hello",
        )

        val result = provider.messageToMap(message)
        assertThat(result["role"]).isEqualTo("assistant")
    }

    @Test
    fun `rejects unsupported image mime type`() {
        val imageData = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

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

        assertThatThrownBy { provider.messageToMap(message) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unsupported image mimeType")
    }

    @Test
    fun `provider id is azure-openai`() {
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

        assertThat(provider.providerId()).isEqualTo("azure-openai")
    }

    @Test
    fun `fails when neither api key nor entra token source is provided`() {
        assertThatThrownBy {
            AzureOpenAiProvider(
                resourceName = "test-resource",
                deploymentId = "gpt-4o",
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("apiKey")
    }

    @Test
    fun `fails when resource name is blank`() {
        assertThatThrownBy {
            AzureOpenAiProvider(
                resourceName = "",
                deploymentId = "gpt-4o",
                apiKey = "test-key",
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("resourceName")
    }

    @Test
    fun `fails when deployment id is blank`() {
        assertThatThrownBy {
            AzureOpenAiProvider(
                resourceName = "test-resource",
                deploymentId = "",
                apiKey = "test-key",
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("deploymentId")
    }
}
