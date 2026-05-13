package dev.tramai.rag

import dev.tramai.core.model.ModelRequest
import dev.tramai.embedding.EmbeddingModel
import dev.tramai.vectorstore.VectorEntry
import dev.tramai.vectorstore.VectorStore
import java.security.MessageDigest

/**
 * Exception thrown when a step in the RAG pipeline fails, providing
 * context about which step and source caused the failure.
 */
class RagPipelineException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * End-to-end RAG pipeline that coordinates document loading, chunking,
 * indexing, retrieval, and context injection.
 *
 * Typical usage:
 * ```
 * val pipeline = RagPipeline(loader, chunker, embeddingModel, vectorStore, contextInjector)
 * pipeline.index("path/to/doc.txt", "my_collection")
 * val enriched = pipeline.query("What is X?", originalRequest, "my_collection")
 * ```
 *
 * @param loader          Loads documents from sources (files, URLs, etc.).
 * @param chunker         Splits loaded documents into manageable chunks.
 * @param embeddingModel  Models used to embed text chunks.
 * @param vectorStore     The vector store for indexing and retrieval.
 * @param retriever       Retrieves relevant context from the vector store.
 * @param contextInjector Injects retrieved context into model requests.
 */
class RagPipeline(
    private val loader: DocumentLoader,
    private val chunker: Chunker,
    private val embeddingModel: EmbeddingModel,
    private val vectorStore: VectorStore,
    private val retriever: RagRetriever,
    private val contextInjector: ContextInjector,
) {
    /**
     * Loads a document from [source], chunks it, embeds all chunks, and
     * upserts them into the given [collection].
     *
     * Embedding is batched in groups of 2048 to respect common API limits
     * (e.g., OpenAI's batch limit). If the upsert fails partway through,
     * already-upserted entries are deleted to prevent partial data.
     *
     * @param source     The document source (file path, URL, etc.).
     * @param collection The vector store collection to index into.
     * @return The number of chunks indexed.
     * @throws RagPipelineException if indexing fails at any step.
     */
    suspend fun index(source: String, collection: String): Int {
        require(source.isNotBlank()) { "RagPipeline.index: source must not be blank" }
        require(collection.isNotBlank()) { "RagPipeline.index: collection must not be blank" }

        val document = try {
            loader.load(source)
        } catch (e: Exception) {
            throw RagPipelineException("RagPipeline.index: failed to load document: $source", e)
        }

        val chunks = try {
            chunker.chunk(document)
        } catch (e: Exception) {
            throw RagPipelineException("RagPipeline.index: failed to chunk document: $source", e)
        }

        if (chunks.isEmpty()) return 0

        val texts = chunks.map { it.content }

        // Batch embeddings in groups of 2048 (OpenAI batch limit)
        val batchSize = 2048
        val allVectors = mutableListOf<FloatArray>()
        for (batchStart in texts.indices step batchSize) {
            val batchEnd = minOf(batchStart + batchSize, texts.size)
            val batch = texts.subList(batchStart, batchEnd)
            try {
                val vectors = embeddingModel.embedAll(batch)
                allVectors.addAll(vectors)
            } catch (e: Exception) {
                throw RagPipelineException(
                    "RagPipeline.index: failed to embed chunks ${batchStart}..${batchEnd - 1}: $source", e
                )
            }
        }

        if (chunks.size != allVectors.size) {
            throw RagPipelineException(
                "RagPipeline.index: chunk count (${chunks.size}) does not match vector count (${allVectors.size}) for source: $source"
            )
        }

        // Generate safer IDs using hash of source + index to avoid :: ambiguity
        val entries = chunks.zip(allVectors).map { (chunk, vector) ->
            val rawId = "${chunk.source}::${chunk.metadata["chunk_index"] ?: "0"}"
            val idDigest = MessageDigest.getInstance("SHA-256")
            val hashBytes = idDigest.digest(rawId.toByteArray())
            val id = hashBytes.joinToString("") { "%02x".format(it) }.take(16)
            VectorEntry(
                id = id,
                vector = vector,
                content = chunk.content,
                metadata = chunk.metadata,
            )
        }

        // Upsert with transactional safety: delete already-upserted entries on failure
        val upsertedIds = entries.map { it.id }
        try {
            vectorStore.upsert(collection, entries)
        } catch (e: Exception) {
            // Attempt to clean up already-upserted entries
            try {
                vectorStore.delete(collection, upsertedIds)
            } catch (cleanupError: Exception) {
                throw RagPipelineException(
                    "RagPipeline.index: upsert failed and cleanup of ${upsertedIds.size} entries also failed: $source. " +
                    "Original error: ${e.message}",
                    e,
                )
            }
            throw RagPipelineException(
                "RagPipeline.index: upsert failed for $source, cleaned up ${upsertedIds.size} entries", e
            )
        }

        return chunks.size
    }

    /**
     * Queries the RAG pipeline: retrieves relevant context from the vector
     * store and injects it into the [request].
     *
     * @param query      The natural language query.
     * @param request    The original model request to enrich.
     * @param collection The vector store collection to search.
     * @param filter     Optional metadata filter.
     * @return A modified [ModelRequest] with context injected.
     * @throws RagPipelineException if querying fails.
     */
    suspend fun query(
        query: String,
        request: ModelRequest,
        collection: String,
        filter: Map<String, String>? = null,
    ): ModelRequest {
        require(query.isNotBlank()) { "RagPipeline.query: query must not be blank" }
        require(collection.isNotBlank()) { "RagPipeline.query: collection must not be blank" }

        val results = try {
            retriever.retrieve(collection, query, filter)
        } catch (e: Exception) {
            throw RagPipelineException("RagPipeline.query: failed to retrieve from collection '$collection'", e)
        }

        return contextInjector.inject(results, request)
    }
}
