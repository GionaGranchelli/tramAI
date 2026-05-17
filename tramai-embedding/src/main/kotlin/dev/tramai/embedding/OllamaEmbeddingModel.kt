package dev.tramai.embedding

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [EmbeddingModel] implementation backed by a local Ollama API.
 *
 * Uses the `/api/embed` endpoint which accepts batched inputs.
 *
 * @param baseUrl      Base URL of the Ollama server (default: http://localhost:11434).
 * @param model        The model ID to use (e.g., nomic-embed-text, llama3).
 * @param token        Optional bearer token for authenticated Ollama instances.
 * @param timeoutMs    HTTP request timeout in milliseconds (default: 60000).
 * @param httpClient   HTTP client for making requests.
 */
class OllamaEmbeddingModel(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "nomic-embed-text",
    private val token: String? = null,
    private val timeoutMs: Long = 60_000L,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val ioDispatcher: CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
) : EmbeddingModel {

    override fun providerId(): String = "ollama"

    @Volatile
    private var cachedDimensions: Int? = null

    override suspend fun embed(text: String): FloatArray {
        require(text.isNotBlank()) { "text must not be blank" }
        val result = embedAll(listOf(text))
        return result.first()
    }

    override suspend fun embedAll(texts: List<String>): List<FloatArray> {
        require(texts.isNotEmpty()) { "texts must not be empty" }

        val payload = mapOf(
            "model" to model,
            "input" to texts,
        )
        val jsonPayload = MAPPER.writeValueAsString(payload)

        return withContext(ioDispatcher) {
            try {
                val requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create("${baseUrl.trimEnd('/')}/api/embed"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))

                token?.let { requestBuilder.header("Authorization", "Bearer $it") }

                val httpRequest = requestBuilder.build()
                val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    throw EmbeddingException(
                        "Ollama embeddings API returned HTTP ${response.statusCode()}: ${response.body().take(500)}"
                    )
                }

                val body = MAPPER.readTree(response.body())
                val embeddingsArray = body.path("embeddings")
                if (!embeddingsArray.isArray) {
                    throw EmbeddingException("Ollama embeddings response missing 'embeddings' array")
                }

                val results = embeddingsArray.map { entry ->
                    entry.map { it.floatValue() }.toFloatArray()
                }

                // Validate all results have the same positive dimension.
                require(results.all { it.isNotEmpty() }) {
                    "Ollama returned at least one zero-dimension embedding"
                }
                val dim = results.first().size
                require(results.all { it.size == dim }) {
                    "Ollama returned embeddings with inconsistent dimensions: expected $dim, got ${results.map { it.size }.distinct()}"
                }

                // Cache the dimensions from the first successful response.
                if (cachedDimensions == null && results.isNotEmpty()) {
                    cachedDimensions = results.first().size
                }

                results
            } catch (e: Exception) {
                if (e is EmbeddingException) throw e
                throw EmbeddingException("Ollama embeddings request failed: ${e.message}", e)
            }
        }
    }

    override fun dimensions(): Int {
        return cachedDimensions
            ?: throw IllegalStateException(
                "Ollama embeddings dimensions are not available until after the first successful embed() call. " +
                    "Call embed() or embedAll() first."
            )
    }

    companion object {
        private val MAPPER: ObjectMapper = ObjectMapper()
    }
}
