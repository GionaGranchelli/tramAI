package dev.tramai.vectorstore.chroma

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.vectorstore.SearchResult
import dev.tramai.vectorstore.VectorEntry
import dev.tramai.vectorstore.VectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * [VectorStore] implementation backed by Chroma's HTTP API.
 *
 * @param baseUrl      Base URL of the Chroma server (default: http://localhost:8000).
 * @param httpClient   HTTP client for making requests.
 * @param objectMapper Jackson mapper for JSON serialization/deserialization.
 */
class ChromaVectorStore(
    private val baseUrl: String = "http://localhost:8000",
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : VectorStore {

    private val collectionExistsCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Invalidates the collection existence cache.
     *
     * **Limitation:** The collection cache has no automatic invalidation. If an external
     * process deletes a collection, the cache will think it still exists. Call this method
     * to force a refresh on the next operation.
     */
    fun invalidateCache() {
        collectionExistsCache.clear()
    }

    override suspend fun upsert(collection: String, vectors: List<VectorEntry>) = withContext(Dispatchers.IO) {
        try {
            ensureCollectionExists(collection)

            val payload = mutableMapOf<String, Any?>(
                "ids" to vectors.map { it.id },
                "embeddings" to vectors.map { entry -> entry.vector.toList() },
                "documents" to vectors.map { it.content },
                "metadatas" to vectors.map { entry ->
                    entry.metadata.ifEmpty { emptyMap<String, String>() }
                },
            )

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/api/v1/collections/${urlEncode(collection)}/add"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw ChromaException(
                    "Chroma upsert returned HTTP ${response.statusCode()}: ${response.body().take(500)}"
                )
            }
        } catch (e: ChromaException) {
            throw e
        } catch (e: Exception) {
            throw ChromaException("Chroma upsert failed: ${e.message}", e)
        }
    }

    override suspend fun search(
        collection: String,
        query: FloatArray,
        topK: Int,
        filter: Map<String, String>?,
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val payload = mutableMapOf<String, Any?>(
                "query_embeddings" to listOf(query.toList()),
                "n_results" to topK,
            )

            if (filter != null && filter.isNotEmpty()) {
                payload["where"] = filter
            }

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/api/v1/collections/${urlEncode(collection)}/query"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw ChromaException(
                    "Chroma query returned HTTP ${response.statusCode()}: ${response.body().take(500)}"
                )
            }

            val body = objectMapper.readTree(response.body())
            val ids = body.path("ids").get(0)
            val documents = body.path("documents").get(0)
            val metadatas = body.path("metadatas").get(0)
            val distances = body.path("distances").get(0)

            if (documents.isMissingNode || !documents.isArray) {
                throw ChromaException(
                    "Chroma response missing 'documents' array: ${response.body().take(500)}"
                )
            }

            documents.mapIndexedNotNull { index, doc ->
                if (doc.isNull) return@mapIndexedNotNull null
                val entryId = if (!ids.isMissingNode && ids.isArray && ids.size() > index) {
                    ids.get(index).asText("")
                } else ""
                val metadata = if (!metadatas.isMissingNode && metadatas.isArray && metadatas.size() > index) {
                    val metaNode = metadatas.get(index)
                    if (metaNode.isObject) {
                        metaNode.fieldNames().asSequence().map { name ->
                            name to metaNode.get(name).asText("")
                        }.toMap()
                    } else emptyMap()
                } else emptyMap()

                val distance = if (!distances.isMissingNode && distances.isArray && distances.size() > index) {
                    distances.get(index).asDouble()
                } else 0.0

                // Chroma returns L2 distance by default.
                // This is an approximate conversion from L2 distance to a similarity score in [0, 1].
                // For a true cosine similarity metric, configure Chroma with "cosine" distance
                // and use `1.0 - distance` directly.
                val similarity = 1.0 / (1.0 + distance)

                SearchResult(
                    id = entryId,
                    content = doc.asText(""),
                    metadata = metadata,
                    score = similarity,
                )
            }
        } catch (e: ChromaException) {
            throw e
        } catch (e: Exception) {
            throw ChromaException("Chroma search failed: ${e.message}", e)
        }
    }

    override suspend fun delete(collection: String, ids: List<String>) = withContext(Dispatchers.IO) {
        try {
            val payload = mapOf("ids" to ids)

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/api/v1/collections/${urlEncode(collection)}/delete"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .method("DELETE", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw ChromaException(
                    "Chroma delete returned HTTP ${response.statusCode()}: ${response.body().take(500)}"
                )
            }
        } catch (e: ChromaException) {
            throw e
        } catch (e: Exception) {
            throw ChromaException("Chroma delete failed: ${e.message}", e)
        }
    }

    override suspend fun listCollections(): List<String> = withContext(Dispatchers.IO) {
        try {
            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/api/v1/collections"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw ChromaException(
                    "Chroma list collections returned HTTP ${response.statusCode()}: ${response.body().take(500)}"
                )
            }

            val body = objectMapper.readTree(response.body())
            if (body.isArray) {
                body.map { it.path("name").asText("") }.filter { it.isNotBlank() }
            } else {
                emptyList()
            }
        } catch (e: ChromaException) {
            throw e
        } catch (e: Exception) {
            throw ChromaException("Chroma list collections failed: ${e.message}", e)
        }
    }

    /**
     * Ensures the named collection exists. If not, creates it.
     * Results are cached to avoid redundant API calls.
     *
     * **Limitation:** If an external process deletes a collection, the cache will think
     * it still exists. Call [invalidateCache] to force a refresh.
     */
    private suspend fun ensureCollectionExists(collection: String) {
        if (collectionExistsCache.getOrDefault(collection, false)) return

        val existing = listCollections()
        if (collection in existing) {
            collectionExistsCache[collection] = true
            return
        }

        val payload = mapOf("name" to collection)

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/api/v1/collections"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            // Collection may have been created by another caller; ignore if it already exists.
            if (response.statusCode() != 409) {
                throw ChromaException(
                    "Chroma create collection returned HTTP ${response.statusCode()}: ${response.body().take(500)}"
                )
            }
        }
        collectionExistsCache[collection] = true
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
