package dev.tramai.vectorstore

/**
 * Interface for vector storage and similarity search.
 */
interface VectorStore {
    /**
     * Returns a unique identifier for this vector store implementation (e.g., "chroma", "pgvector", "in-memory").
     */
    fun storeId(): String = this::class.simpleName ?: "unknown"

    /**
     * Upserts (inserts or replaces) a batch of vectors into the given [collection].
     */
    suspend fun upsert(collection: String, vectors: List<VectorEntry>)

    /**
     * Searches the [collection] for the top K vectors most similar to [query].
     *
     * @param collection The collection to search within.
     * @param query      The query embedding vector.
     * @param topK       Maximum number of results to return.
     * @param filter     Optional metadata filter key-value pairs (exact match).
     */
    suspend fun search(
        collection: String,
        query: FloatArray,
        topK: Int,
        filter: Map<String, String>? = null,
    ): List<SearchResult>

    /**
     * Deletes entries by [ids] from the given [collection].
     */
    suspend fun delete(collection: String, ids: List<String>)

    /**
     * Lists all available collection names.
     */
    suspend fun listCollections(): List<String>
}
