package dev.tramai.rag

import dev.tramai.embedding.EmbeddingModel
import dev.tramai.vectorstore.SearchResult
import dev.tramai.vectorstore.VectorStore

/**
 * Retrieves context from a [VectorStore] by embedding a query and searching
 * for the most semantically similar entries.
 *
 * @param embeddingModel The model used to embed the query text.
 * @param vectorStore    The vector store to search against.
 * @param topK           Maximum number of search results to return (default: 3).
 * @param minScore       Minimum similarity score threshold; results below this are discarded (default: 0.0).
 */
class RagRetriever(
    private val embeddingModel: EmbeddingModel,
    private val vectorStore: VectorStore,
    private val topK: Int = 3,
    private val minScore: Double = 0.0,
) {
    init {
        require(topK > 0) { "RagRetriever: topK must be positive, got $topK" }
        require(minScore >= 0.0) { "RagRetriever: minScore must be non-negative, got $minScore" }
    }

    /**
     * Embeds the [query] and searches the given [collection] for similar entries.
     *
     * @param collection The vector store collection to search.
     * @param query      The natural language query string.
     * @param filter     Optional metadata filter key-value pairs (exact match).
     * @return A list of [SearchResult] entries sorted by descending similarity,
     *         filtered by [minScore].
     */
    suspend fun retrieve(
        collection: String,
        query: String,
        filter: Map<String, String>? = null,
    ): List<SearchResult> {
        require(query.isNotBlank()) { "RagRetriever: query must not be blank" }
        require(collection.isNotBlank()) { "RagRetriever: collection must not be blank" }

        if (filter != null) {
            filter.forEach { (k, v) ->
                require(k.isNotBlank()) { "RagRetriever: filter key must not be blank, got: '$k'" }
                require(v.isNotBlank()) { "RagRetriever: filter value must not be blank for key '$k', got: '$v'" }
            }
        }

        val queryVector = embeddingModel.embed(query)

        // Request topK * 2 internally so that post-filtering by minScore
        // doesn't silently reduce the result count below the requested topK.
        val internalTopK = topK * 2
        val results = vectorStore.search(collection, queryVector, internalTopK, filter)

        return results.filter { it.score >= minScore }.take(topK)
    }
}
