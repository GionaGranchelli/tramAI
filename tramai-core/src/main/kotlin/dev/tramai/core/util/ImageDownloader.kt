package dev.tramai.core.util

import dev.tramai.core.model.ContentPart
import java.net.URI

/**
 * Utility for downloading images from URLs and resolving [ContentPart.ImageUrlContent]
 * to [ContentPart.ImagePart] by fetching the bytes.
 */
object ImageDownloader {
    private const val MAX_DOWNLOAD_SIZE = 20 * 1024 * 1024 // 20MB max per image

    /**
     * Downloads the content at [url] and returns it as a byte array.
     *
     * @throws IllegalArgumentException if the downloaded content exceeds [MAX_DOWNLOAD_SIZE].
     */
    fun download(url: String): ByteArray {
        val connection = URI(url).toURL().openConnection()
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        val stream = connection.getInputStream()
        return stream.use { it.readBytes().also { bytes ->
            require(bytes.size <= MAX_DOWNLOAD_SIZE) { "Image download exceeds maximum size: ${bytes.size} > $MAX_DOWNLOAD_SIZE" }
        }}
    }

    /**
     * Resolves [ContentPart.ImageUrlContent] to [ContentPart.ImagePart] by downloading
     * the URL. If [part] is already an [ContentPart.ImagePart], returns it as-is.
     * Non-image parts are returned unchanged.
     */
    fun resolveToImagePart(part: ContentPart): ContentPart = when (part) {
        is ContentPart.ImagePart -> part
        is ContentPart.ImageUrlContent -> {
            val bytes = download(part.url)
            ContentPart.ImagePart(
                mimeType = part.mimeType ?: detectMimeType(part.url),
                data = bytes,
            )
        }
        else -> part
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
