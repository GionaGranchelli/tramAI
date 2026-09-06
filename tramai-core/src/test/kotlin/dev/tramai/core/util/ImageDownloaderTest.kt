package dev.tramai.core.util

import com.sun.net.httpserver.HttpServer
import dev.tramai.core.model.ContentPart
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.net.InetSocketAddress
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
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---- SSRF and Scheme Security Tests (R12-001) ----

    @Test
    fun `download rejects file scheme for local file inclusion`() {
        assertThatThrownBy { ImageDownloader.download("file:///etc/passwd") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unsupported outbound scheme: file")
    }

    @Test
    fun `download rejects jar scheme`() {
        assertThatThrownBy { ImageDownloader.download("jar:file:/app.jar!/secret.txt") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unsupported outbound scheme")
    }

    @Test
    fun `download rejects non-http schemes`() {
        listOf(
            "ftp://example.com/image.png",
            "gopher://example.com/image.png",
            "data:image/png;base64,iVBORw0KGgo=",
            "ldap://example.com/image.png",
        ).forEach { schemeUrl ->
            assertThatThrownBy { ImageDownloader.download(schemeUrl) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("unsupported outbound scheme")
        }
    }

    @Test
    fun `download rejects loopback IPv4 destinations`() {
        listOf(
            "http://127.0.0.1/photo.png",
            "http://127.0.0.2:8080/photo.png",
            "http://127.128.0.1/photo.png",
            "http://localhost/photo.png",
            "http://localhost:8080/photo.png",
        ).forEach { loopbackUrl ->
            assertThatThrownBy { ImageDownloader.download(loopbackUrl) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("restricted")
        }
    }

    @Test
    fun `download rejects alternative IPv4 loopback notations`() {
        listOf(
            "http://0177.0.0.1/photo.png", // octal 127
            "http://0x7f.0.0.1/photo.png", // hex 127
            "http://0x7f000001/photo.png", // dword 127.0.0.1
            "http://2130706433/photo.png", // decimal dword 127.0.0.1
            "http://127.1/photo.png", // 2-part dotted 127.0.0.1
        ).forEach { altUrl ->
            assertThatThrownBy { ImageDownloader.download(altUrl) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("restricted")
        }
    }

    @Test
    fun `download rejects private RFC 1918 IPv4 destinations`() {
        listOf(
            "http://10.0.0.1/photo.png",
            "http://10.254.0.1/photo.png",
            "http://172.16.0.1/photo.png",
            "http://172.31.255.254/photo.png",
            "http://192.168.1.1/photo.png",
            "http://192.168.0.254/photo.png",
        ).forEach { privateUrl ->
            assertThatThrownBy { ImageDownloader.download(privateUrl) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("restricted")
        }
    }

    @Test
    fun `download rejects link-local and cloud metadata 169-254 destinations`() {
        listOf(
            "http://169.254.169.254/latest/meta-data/",
            "http://169.254.1.1/image.png",
            "http://0xa9fea9fe/metadata", // hex 169.254.169.254
            "http://2852039166/metadata", // decimal dword 169.254.169.254
        ).forEach { metaUrl ->
            assertThatThrownBy { ImageDownloader.download(metaUrl) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("restricted")
        }
    }

    @Test
    fun `download rejects Carrier-Grade NAT IPv4 destinations`() {
        listOf(
            "http://100.64.0.1/photo.png",
            "http://100.100.100.100/photo.png",
            "http://100.127.255.254/photo.png",
        ).forEach { cgnatUrl ->
            assertThatThrownBy { ImageDownloader.download(cgnatUrl) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("restricted")
        }
    }

    @Test
    fun `download rejects IPv6 loopback and unique-local destinations`() {
        listOf(
            "http://[::1]/photo.png",
            "http://[0:0:0:0:0:0:0:1]/photo.png",
            "http://[fc00::1]/photo.png",
            "http://[fd12:3456:789a::1]/photo.png",
            "http://[fe80::1]/photo.png", // link-local
        ).forEach { ipv6Url ->
            assertThatThrownBy { ImageDownloader.download(ipv6Url) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("restricted")
        }
    }

    @Test
    fun `resolveToImagePart rejects file URL in ImageUrlContent`() {
        val part = ContentPart.ImageUrlContent("file:///etc/hosts")
        assertThatThrownBy { ImageDownloader.resolveToImagePart(part) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unsupported outbound scheme: file")
    }

    // ---- MIME Detection Tests ----

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
