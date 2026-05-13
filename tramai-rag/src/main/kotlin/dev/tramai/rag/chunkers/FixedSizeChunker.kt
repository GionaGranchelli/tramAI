package dev.tramai.rag.chunkers

import dev.tramai.rag.Chunker
import dev.tramai.rag.Document

/**
 * Splits text into chunks of a fixed character count with optional overlap.
 *
 * This is the simplest chunking strategy — it divides text purely by character
 * boundaries without regard for sentence or word boundaries.
 *
 * @param chunkSize    Maximum number of characters per chunk (default: 500).
 * @param chunkOverlap Number of overlapping characters between chunks (default: 50).
 */
class FixedSizeChunker(
    private val chunkSize: Int = 500,
    private val chunkOverlap: Int = 50,
) : Chunker {

    init {
        require(chunkSize > 0) { "FixedSizeChunker: chunkSize must be positive, got $chunkSize" }
        require(chunkOverlap >= 0) { "FixedSizeChunker: chunkOverlap must be non-negative, got $chunkOverlap" }
        require(chunkOverlap < chunkSize) { "FixedSizeChunker: chunkOverlap ($chunkOverlap) must be less than chunkSize ($chunkSize)" }
    }

    override fun chunk(document: Document): List<Document> {
        if (document.content.isBlank()) return emptyList()

        val chunks = mutableListOf<String>()
        var start = 0
        val text = document.content

        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            chunks.add(text.substring(start, end))
            start += chunkSize - chunkOverlap
        }

        return chunks.mapIndexed { index, textContent ->
            Document(
                source = document.source,
                content = textContent,
                metadata = document.metadata + mapOf("chunk_index" to index.toString()),
            )
        }.filter { it.content.isNotBlank() }
    }
}
