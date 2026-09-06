@file:Suppress(
    "LongMethod",
    "TooGenericExceptionCaught",
    "ThrowsCount",
    "MagicNumber",
    "UseCheckOrError",
    "MaxLineLength",
    "ComplexMethod",
    "NestedBlockDepth",
)

package dev.tramai.core.util

import dev.tramai.core.model.ContentPart
import dev.tramai.core.provider.transport.ExperimentalProviderTransportApi
import dev.tramai.core.provider.transport.readBoundedBodyBytes
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Utility for securely downloading images from URLs and resolving [ContentPart.ImageUrlContent]
 * to [ContentPart.ImagePart] by fetching the bytes.
 *
 * Enforces outbound network security:
 * - Rejects non-HTTP(S) schemes (e.g. `file:`, `jar:`, `gopher:`, `data:`)
 * - Rejects restricted IP destinations (loopback, private RFC 1918, link-local metadata 169.254.169.254, CGNAT, IPv6 ULA)
 * - Re-validates every redirect destination against restricted address policies
 * - Enforces bounded payload size (default 20MB)
 */
@OptIn(ExperimentalProviderTransportApi::class)
object ImageDownloader {
    private const val MAX_DOWNLOAD_SIZE = 20 * 1024 * 1024 // 20MB max per image
    private const val MAX_REDIRECTS = 3
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * Downloads the content at [url] and returns it as a byte array.
     *
     * @throws IllegalArgumentException if the URL is invalid, uses an unpermitted scheme,
     *         resolves to a restricted address, or the content exceeds [MAX_DOWNLOAD_SIZE].
     * @throws IllegalStateException if the HTTP request fails or exceeds redirect limit.
     */
    fun download(url: String): ByteArray {
        var currentUri =
            try {
                URI(url)
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid image URL: $url", error)
            }

        var redirectCount = 0
        while (true) {
            OutboundAddressSecurity.validateOutboundUri(currentUri)

            val connection =
                currentUri.toURL().openConnection() as? HttpURLConnection
                    ?: throw IllegalArgumentException("URL must be an HTTP or HTTPS connection: $currentUri")

            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "TramAI-ImageDownloader/1.0")

            val responseCode =
                try {
                    connection.responseCode
                } catch (error: Exception) {
                    throw IllegalStateException("Failed to connect to image URL: $currentUri", error)
                }

            when (responseCode) {
                in 200..299 -> {
                    val stream: InputStream =
                        try {
                            connection.inputStream
                        } catch (error: Exception) {
                            throw IllegalStateException("Failed to open stream for image URL: $currentUri", error)
                        }
                    return try {
                        readBoundedBodyBytes(stream, MAX_DOWNLOAD_SIZE)
                    } catch (error: IllegalArgumentException) {
                        throw IllegalArgumentException("Image download exceeds maximum size: $MAX_DOWNLOAD_SIZE", error)
                    } finally {
                        connection.disconnect()
                    }
                }

                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307, // Temporary Redirect
                308, // Permanent Redirect
                -> {
                    connection.disconnect()
                    redirectCount++
                    if (redirectCount > MAX_REDIRECTS) {
                        throw IllegalStateException("Too many redirects ($redirectCount) downloading image from: $url")
                    }
                    val location =
                        connection.getHeaderField("Location")
                            ?: throw IllegalStateException("Redirect response missing Location header from: $currentUri")
                    currentUri =
                        try {
                            currentUri.resolve(location)
                        } catch (error: Exception) {
                            throw IllegalArgumentException("Invalid redirect Location '$location' from: $currentUri", error)
                        }
                }

                else -> {
                    connection.disconnect()
                    throw IllegalStateException("Image download failed with HTTP status $responseCode from: $currentUri")
                }
            }
        }
    }

    /**
     * Resolves [ContentPart.ImageUrlContent] to [ContentPart.ImagePart] by downloading
     * the URL. If [part] is already an [ContentPart.ImagePart], returns it as-is.
     * Non-image parts are returned unchanged.
     */
    fun resolveToImagePart(part: ContentPart): ContentPart =
        when (part) {
            is ContentPart.ImagePart -> {
                part
            }

            is ContentPart.ImageUrlContent -> {
                val bytes = download(part.url)
                ContentPart.ImagePart(
                    mimeType = part.mimeType ?: detectMimeType(part.url),
                    data = bytes,
                )
            }

            else -> {
                part
            }
        }

    /**
     * Detects a likely MIME type from a URL's file extension.
     */
    internal fun detectMimeType(url: String): String {
        val cleaned = url.split('?', '#').first().lowercase()
        return when {
            cleaned.endsWith(".jpg") || cleaned.endsWith(".jpeg") -> "image/jpeg"
            cleaned.endsWith(".png") -> "image/png"
            cleaned.endsWith(".webp") -> "image/webp"
            cleaned.endsWith(".gif") -> "image/gif"
            else -> "image/png" // safe default
        }
    }
}
