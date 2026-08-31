package dev.tramai.vectorstore.chroma

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.provider.transport.ExperimentalProviderTransportApi
import dev.tramai.core.provider.transport.readBoundedResponseBody
import dev.tramai.vectorstore.SearchResult
import dev.tramai.vectorstore.VectorEntry
import dev.tramai.vectorstore.VectorStore
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * [VectorStore] implementation backed by Chroma's HTTP API.
 *
 * @param baseUrl      Base URL of the Chroma server (default: http://localhost:8000).
 * @param httpClient   HTTP client for making requests.
 * @param objectMapper Jackson mapper for JSON serialization/deserialization.
 */
@OptIn(ExperimentalProviderTransportApi::class)
class ChromaVectorStore(
    private val baseUrl: String = "http://localhost:8000",
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val ioDispatcher: CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
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

    override suspend fun upsert(
        collection: String,
        vectors: List<VectorEntry>,
    ) = withContext(ioDispatcher) {
        try {
            ensureCollectionExists(collection)

            val payload =
                mutableMapOf<String, Any?>(
                    "ids" to vectors.map { it.id },
                    "embeddings" to vectors.map { entry -> entry.vector.toList() },
                    "documents" to vectors.map { it.content },
                    "metadatas" to
                        vectors.map { entry ->
                            entry.metadata.ifEmpty { emptyMap<String, String>() }
                        },
                )

            val httpRequest =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("${baseUrl.trimEnd('/')}/api/v1/collections/${urlEncode(collection)}/add"))
                    .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
            val bounded = readBoundedResponseBody(response)
            if (response.statusCode() !in 200..299) {
                throw ChromaException(
                    "Chroma upsert returned HTTP ${response.statusCode()}: ${bounded.text.take(500)}",
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
    ): List<SearchResult> =
        withContext(ioDispatcher) {
            try {
                val payload =
                    mutableMapOf<String, Any?>(
                        "query_embeddings" to listOf(query.toList()),
                        "n_results" to topK,
                    )

                if (!filter.isNullOrEmpty()) {
                    payload["where"] = filter
                }

                val httpRequest =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create("${baseUrl.trimEnd('/')}/api/v1/collections/${urlEncode(collection)}/query"))
                        .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                        .build()

                val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                val bounded = readBoundedResponseBody(response)
                if (response.statusCode() !in 200..299) {
                    throw ChromaException(
                        "Chroma query returned HTTP ${response.statusCode()}: ${bounded.text.take(500)}",
                    )
                }

                parseChromaSearchResponse(objectMapper, bounded.text)
            } catch (e: ChromaException) {
                throw e
            } catch (e: Exception) {
                throw ChromaException("Chroma search failed: ${e.message}", e)
            }
        }

    /**
     * Extracts a single [SearchResult] from the Chroma response arrays at the given [index].
     *
     * @param ids        the "ids" array from the response (may be missing/non-array).
     * @param documents  the "documents" array from the response.
     * @param metadatas  the "metadatas" array from the response (may be missing/non-array).
     * @param distances  the "distances" array from the response (may be missing/non-array).
     * @param index      the zero-based index of the entry to extract.
     * @return a [SearchResult] for the entry at [index].
     */
    private fun extractEntry(
        ids: JsonNode,
        documents: JsonNode,
        metadatas: JsonNode,
        distances: JsonNode,
        index: Int,
    ): SearchResult {
        val entryId =
            if (!ids.isMissingNode && ids.isArray && ids.size() > index) {
                ids.get(index).asText("")
            } else {
                ""
            }

        val metadata =
            if (!metadatas.isMissingNode && metadatas.isArray && metadatas.size() > index) {
                val metaNode = metadatas.get(index)
                if (metaNode.isObject) {
                    metaNode
                        .fieldNames()
                        .asSequence()
                        .map { name ->
                            name to metaNode.get(name).asText("")
                        }.toMap()
                } else {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

        val distance =
            if (!distances.isMissingNode && distances.isArray && distances.size() > index) {
                distances.get(index).asDouble()
            } else {
                0.0
            }

        // Chroma returns L2 distance by default.
        // This is an approximate conversion from L2 distance to a similarity score in [0, 1].
        // For a true cosine similarity metric, configure Chroma with "cosine" distance
        // and use `1.0 - distance` directly.
        val similarity = 1.0 / (1.0 + distance)

        return SearchResult(
            id = entryId,
            content = documents.get(index).asText(""),
            metadata = metadata,
            score = similarity,
        )
    }

    /**
     * Parses the JSON response body from a Chroma search query into a list of [SearchResult] objects.
     */
    private fun parseChromaSearchResponse(
        mapper: ObjectMapper,
        rawBody: String,
    ): List<SearchResult> {
        val body = mapper.readTree(rawBody)
        val ids = body.path("ids").get(0)
        val documents = body.path("documents").get(0)
        val metadatas = body.path("metadatas").get(0)
        val distances = body.path("distances").get(0)

        if (documents.isMissingNode || !documents.isArray) {
            throw ChromaException(
                "Chroma response missing 'documents' array: ${rawBody.take(500)}",
            )
        }

        return documents.mapIndexedNotNull { index, doc ->
            if (doc.isNull) return@mapIndexedNotNull null
            extractEntry(ids, documents, metadatas, distances, index)
        }
    }

    override suspend fun delete(
        collection: String,
        ids: List<String>,
    ) = withContext(ioDispatcher) {
        try {
            val payload = mapOf("ids" to ids)

            val httpRequest =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("${baseUrl.trimEnd('/')}/api/v1/collections/${urlEncode(collection)}/delete"))
                    .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                    .timeout(Duration.ofSeconds(30))
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
            val bounded = readBoundedResponseBody(response)
            if (response.statusCode() !in 200..299) {
                throw ChromaException(
                    "Chroma delete returned HTTP ${response.statusCode()}: ${bounded.text.take(500)}",
                )
            }
        } catch (e: ChromaException) {
            throw e
        } catch (e: Exception) {
            throw ChromaException("Chroma delete failed: ${e.message}", e)
        }
    }

    override suspend fun listCollections(): List<String> =
        withContext(ioDispatcher) {
            try {
                val httpRequest =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create("${baseUrl.trimEnd('/')}/api/v1/collections"))
                        .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build()

                val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                val bounded = readBoundedResponseBody(response)
                if (response.statusCode() !in 200..299) {
                    throw ChromaException(
                        "Chroma list collections returned HTTP ${response.statusCode()}: ${bounded.text.take(500)}",
                    )
                }

                val body = objectMapper.readTree(bounded.text)
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

        val httpRequest =
            HttpRequest
                .newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/api/v1/collections"))
                .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
        val bounded = readBoundedResponseBody(response)
        if (response.statusCode() !in 200..299 && response.statusCode() != 409) {
            // Collection may have been created by another caller; ignore if it already exists.
            throw ChromaException(
                "Chroma create collection returned HTTP ${response.statusCode()}: ${bounded.text.take(500)}",
            )
        }
        collectionExistsCache[collection] = true
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

/** @see ChromaVectorStore */
private const val HEADER_CONTENT_TYPE = "Content-Type"

/** @see ChromaVectorStore */
private const val APPLICATION_JSON = "application/json"
