package dev.tramai.vectorstore

/**
 * The result of a similarity search against a [VectorStore].
 *
 * @param id       The unique identifier of the matched entry (for RAG traceability).
 * @param content  The text content of the matched entry.
 * @param metadata Key-value metadata of the matched entry.
 * @param score    The similarity score. Higher values indicate greater similarity.
 *                 The score range depends on the [VectorStore] implementation and
 *                 distance metric used. For Chroma with default L2 distance, scores
 *                 are in [0, 1] via `1 / (1 + distance)`. For pgvector with cosine
 *                 distance (`<=>` operator), scores are in [-1, 1] via
 *                 `1 - cosine_distance`.
 */
data class SearchResult(
    val id: String,
    val content: String,
    val metadata: Map<String, String>,
    val score: Double,
)
