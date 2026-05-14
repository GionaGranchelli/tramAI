package dev.tramai.core.util

import dev.tramai.core.model.ContentPart
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class ImageDownloaderTest {

    @Test
    fun `resolveToImagePart returns ImagePart as-is`() {
        val part = ContentPart.ImagePart("image/png", byteArrayOf(1, 2, 3))
        val result = ImageDownloader.resolveToImagePart(part)
        assertThat(result).isSameAs(part)
    }

    @Test
    fun `resolveToImagePart returns non-image parts unchanged`() {
        val part = ContentPart.TextPart("hello")
        val result = ImageDownloader.resolveToImagePart(part)
        assertThat(result).isSameAs(part)
    }

    @Test
    fun `resolveToImagePart throws on invalid url`() {
        val part = ContentPart.ImageUrlContent("not-a-valid-url")
        assertThatThrownBy { ImageDownloader.resolveToImagePart(part) }
            .isInstanceOf(Exception::class.java)
    }

    @Test
    fun `detectMimeType returns jpeg for jpg extension`() {
        assertThat(ImageDownloader.detectMimeType("https://example.com/photo.jpg"))
            .isEqualTo("image/jpeg")
    }

    @Test
    fun `detectMimeType returns jpeg for jpeg extension`() {
        assertThat(ImageDownloader.detectMimeType("https://example.com/photo.jpeg"))
            .isEqualTo("image/jpeg")
    }

    @Test
    fun `detectMimeType returns png for png extension`() {
        assertThat(ImageDownloader.detectMimeType("https://example.com/photo.png"))
            .isEqualTo("image/png")
    }

    @Test
    fun `detectMimeType returns webp for webp extension`() {
        assertThat(ImageDownloader.detectMimeType("https://example.com/photo.webp"))
            .isEqualTo("image/webp")
    }

    @Test
    fun `detectMimeType returns gif for gif extension`() {
        assertThat(ImageDownloader.detectMimeType("https://example.com/photo.gif"))
            .isEqualTo("image/gif")
    }

    @Test
    fun `detectMimeType defaults to png for unknown extensions`() {
        assertThat(ImageDownloader.detectMimeType("https://example.com/photo.svg"))
            .isEqualTo("image/png")
    }

    @Test
    fun `detectMimeType defaults to png when no extension`() {
        assertThat(ImageDownloader.detectMimeType("https://example.com/photo"))
            .isEqualTo("image/png")
    }

    @Test
    fun `detectMimeType handles uppercase extensions`() {
        assertThat(ImageDownloader.detectMimeType("https://example.com/photo.JPG"))
            .isEqualTo("image/jpeg")
    }

    @Test
    fun `detectMimeType handles url with query parameters`() {
        assertThat(ImageDownloader.detectMimeType("https://example.com/photo.png?w=800&h=600"))
            .isEqualTo("image/png")
    }
}
