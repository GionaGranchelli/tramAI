package dev.tramai.rag.loaders

import dev.tramai.rag.Document
import dev.tramai.rag.DocumentLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * A [DocumentLoader] that fetches text content from HTTP(S) URLs.
 *
 * Sets metadata with the URL, HTTP status code, and content type of the response.
 *
 * NOTE: This loader uses UTF-8 encoding by default via [HttpResponse.BodyHandlers.ofString].
 * If the HTTP response specifies a non-UTF-8 charset in the Content-Type header,
 * that charset is NOT automatically used. For proper charset handling, consider
 * using [HttpResponse.BodyHandlers.ofInputStream] and decoding with the detected charset.
 *
 * @param maxBytes Maximum number of bytes to read; throws [IllegalArgumentException]
 *                 if the response body exceeds this limit (default: 10 MB).
 * @throws IllegalArgumentException if the source is not a valid HTTP(S) URL.
 * @throws RuntimeException if the HTTP request fails.
 * @throws IllegalStateException if the HTTP response returns a non-2xx status code.
 */
class UrlDocumentLoader(
    private val maxBytes: Long = 10_000_000,
    private val ioDispatcher: CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
) : DocumentLoader {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override suspend fun load(source: String): Document {
        require(source.isNotBlank()) { "UrlDocumentLoader: source must not be blank" }

        val uri = try {
            URI.create(source)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("UrlDocumentLoader: invalid URL: $source", e)
        }

        require(uri.scheme == "http" || uri.scheme == "https") {
            "UrlDocumentLoader: unsupported scheme '${uri.scheme}' for URL: $source"
        }

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = try {
            withContext(ioDispatcher) {
                client.send(request, HttpResponse.BodyHandlers.ofString())
            }
        } catch (e: Exception) {
            throw RuntimeException("UrlDocumentLoader: failed to fetch URL: $source", e)
        }

        val statusCode = response.statusCode()
        check(statusCode in 200..299) {
                "UrlDocumentLoader: HTTP $statusCode for URL: $source"
        }

        val body = response.body()
        val bodyBytes = body.toByteArray().size.toLong()
        require(bodyBytes <= maxBytes) {
            "UrlDocumentLoader: response body for $source is $bodyBytes bytes, exceeds maxBytes limit of $maxBytes"
        }

        val contentType = response.headers().firstValue("Content-Type").orElse("text/plain")

        return Document(
            source = source,
            content = body,
            metadata = mapOf(
                "source_type" to "url",
                "url" to source,
                "status_code" to statusCode.toString(),
                "content_type" to contentType,
            ),
        )
    }
}
