package dev.tramai.embedding

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Request payload for the OpenAI Embeddings API.
 */
internal data class OpenAiEmbeddingRequest(
    val model: String,
    val input: List<String>,
    val dimensions: Int? = null,
)

/**
 * [EmbeddingModel] implementation backed by the OpenAI Embeddings API.
 *
 * WARNING: The [apiKey] is stored as a raw String and sent via the Authorization header.
 * Ensure your logging middleware redacts sensitive headers to avoid accidental API key exposure.
 *
 * If [dimensions] is not specified, the dimensionality is derived dynamically from the
 * first successful API response (like Ollama does). If [dimensions] is specified, it is
 * passed to the API and used as the canonical value.
 *
 * @param apiKey       OpenAI API key for authentication.
 * @param model        The model ID to use (default: text-embedding-3-small).
 * @param dimensions   The number of dimensions for the output vectors (null derives from API response).
 * @param baseUrl      Base URL of the OpenAI API (default: https://api.openai.com/v1).
 * @param timeoutMs    HTTP request timeout in milliseconds (default: 60000).
 * @param httpClient   HTTP client for making requests.
 */
class OpenAiEmbeddingModel(
    private val apiKey: String,
    private val model: String = "text-embedding-3-small",
    private val dimensions: Int? = null,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val timeoutMs: Long = 60_000L,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val ioDispatcher: CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
) : EmbeddingModel {

    override fun providerId(): String = "openai"

    @Volatile
    private var cachedDimensions: Int? = dimensions

    override suspend fun embed(text: String): FloatArray {
        require(text.isNotBlank()) { "text must not be blank" }
        val result = embedAll(listOf(text))
        return result.firstOrNull()
            ?: throw EmbeddingException("OpenAI returned zero embeddings for single text input")
    }

    override suspend fun embedAll(texts: List<String>): List<FloatArray> {
        require(texts.isNotEmpty()) { "texts must not be empty" }
        // Build the JSON payload on the calling thread (CPU-bound work).
        val payload = OpenAiEmbeddingRequest(
            model = model,
            input = texts,
            dimensions = dimensions,
        )
        val jsonPayload = MAPPER.writeValueAsString(payload)

        // Only the HTTP send is dispatched to IO.
        return withContext(ioDispatcher) {
            try {
                val httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create("${baseUrl.trimEnd('/')}/embeddings"))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build()

                val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    throw EmbeddingException(
                        "OpenAI embeddings API returned HTTP ${response.statusCode()}: ${response.body().take(500)}"
                    )
                }

                val body = MAPPER.readTree(response.body())
                val data = body.path("data")
                if (!data.isArray) {
                    throw EmbeddingException("OpenAI embeddings response missing 'data' array")
                }

                val results = data.map { entry ->
                    val embeddingArray = entry.path("embedding")
                    if (!embeddingArray.isArray) {
                        throw EmbeddingException("OpenAI embeddings response entry missing 'embedding' array")
                    }
                    embeddingArray.map { it.floatValue() }.toFloatArray()
                }

                // Dynamically derive dimensions from first API response if not hardcoded.
                if (dimensions == null && results.isNotEmpty()) {
                    cachedDimensions = results.first().size
                }

                results
            } catch (e: Exception) {
                if (e is EmbeddingException) throw e
                throw EmbeddingException("OpenAI embeddings request failed: ${e.message}", e)
            }
        }
    }

    override fun dimensions(): Int {
        return cachedDimensions
            ?: throw EmbeddingException(
                "OpenAI embedding dimensions are not available until after the first successful embed() call. " +
                    "Call embed() or embedAll() first."
            )
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
        const val DEFAULT_DIMENSIONS: Int = 1536
        private val MAPPER: ObjectMapper = ObjectMapper()
    }
}
