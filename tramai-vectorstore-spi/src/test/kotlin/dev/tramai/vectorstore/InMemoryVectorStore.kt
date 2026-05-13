package dev.tramai.vectorstore

import kotlin.math.sqrt
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [VectorStore] implementation backed by [ConcurrentHashMap].
 *
 * Uses cosine similarity for search. Useful for testing and lightweight single-JVM usage.
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

    override suspend fun listCollections(): List<String> {
        return collections.keys.toList().sorted()
    }

    /**
     * Computes cosine similarity between two float arrays.
     * Returns a value between 0 (orthogonal) and 1 (identical).
     */
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
