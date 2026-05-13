package dev.tramai.rag

import dev.tramai.embedding.EmbeddingModel

/**
 * Deterministic in-memory [EmbeddingModel] for testing.
 *
 * Produces consistent float vectors based on the hash of the input text,
 * so the same text always produces the same vector. Identical texts produce
 * identical vectors (score = 1.0).
 */
class InMemoryEmbeddingModel(private val dimensions: Int = 4) : EmbeddingModel {

    override fun providerId(): String = "test-in-memory"

    override fun dimensions(): Int = dimensions

    override suspend fun embed(text: String): FloatArray {
        require(text.isNotBlank()) { "InMemoryEmbeddingModel: text must not be blank" }
        // Use absolute value and modulo to avoid negative/overflow issues
        val hash = (text.hashCode().toLong() and 0x7FFFFFFF).toDouble()
        return FloatArray(dimensions) { i ->
            (((hash * (i + 1)) % 1000) / 1000.0).toFloat()
        }
    }

    override suspend fun embedAll(texts: List<String>): List<FloatArray> {
        require(texts.isNotEmpty()) { "InMemoryEmbeddingModel: texts must not be empty" }
        return texts.map { embed(it) }
    }
}
