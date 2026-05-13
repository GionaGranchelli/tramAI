package dev.tramai.rag

import dev.tramai.vectorstore.SearchResult
import dev.tramai.vectorstore.VectorEntry
import dev.tramai.vectorstore.VectorStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * In-memory [VectorStore] implementation backed by [ConcurrentHashMap].
 *
 * Uses cosine similarity for search. Useful for testing and lightweight single-JVM usage.
 * This is a copy for tramai-rag tests, identical to the one in tramai-vectorstore-spi.
 */
class InMemoryVectorStore : VectorStore {

    private val collections = ConcurrentHashMap<String, ConcurrentHashMap<String, VectorEntry>>()

    override suspend fun upsert(collection: String, vectors: List<VectorEntry>) {
        val store = collections.getOrPut(collection) { ConcurrentHashMap() }
        vectors.forEach { entry ->
            store[entry.id] = entry
        }
    }

    override suspend fun search(
        collection: String,
        query: FloatArray,
        topK: Int,
        filter: Map<String, String>?,
    ): List<SearchResult> {
        val store = collections[collection]
            ?: return emptyList()

        return store.values.asSequence()
            .filter { entry -> filter == null || filter.all { (k, v) -> entry.metadata[k] == v } }
            .map { entry ->
                val similarity = cosineSimilarity(query, entry.vector)
                SearchResult(
                    id = entry.id,
                    content = entry.content,
                    metadata = entry.metadata,
                    score = similarity.toDouble(),
                )
            }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    override suspend fun delete(collection: String, ids: List<String>) {
        val store = collections[collection] ?: return
        ids.forEach { id -> store.remove(id) }
        if (store.isEmpty()) {
            collections.remove(collection)
        }
    }

    /**
     * Removes all entries from the given [collection].
     */
    suspend fun clearCollection(collection: String) {
        collections.remove(collection)
    }

    /**
     * Removes all entries from all collections.
     */
    suspend fun clearAll() {
        collections.clear()
    }

    override suspend fun listCollections(): List<String> {
        return collections.keys.toList().sorted()
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) {
            throw IllegalArgumentException(
                "Vector dimension mismatch: ${a.size} vs ${b.size}"
            )
        }

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        if (denominator == 0f) return 0f
        return dotProduct / denominator
    }
}
