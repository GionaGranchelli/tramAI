package dev.tramai.rag.chunkers

import dev.tramai.rag.Chunker
import dev.tramai.rag.Document

/**
 * Splits text by whitespace tokens, approximating token-count-based chunking.
 *
 * This is useful when a rough token budget per chunk is desired without an
 * actual tokenizer. Each word (whitespace-delimited) is counted as approximately
 * one token.
 *
 * WARNING: This chunker destroys all original whitespace formatting. The text is
 * split on `\\s+` (any whitespace) and rejoined with a single space. Tabs, newlines,
 * multiple consecutive spaces, and other whitespace nuances are LOST in the output.
 * Do NOT use this chunker when the original text formatting (code, markdown,
 * tabular data, or any format-sensitive content) must be preserved.
 *
 * Trailing chunks smaller than [minChunkTokens] are merged into the previous chunk
 * to avoid degenerate near-empty chunks.
 *
 * @param maxTokens        Maximum number of tokens per chunk (default: 500).
 * @param overlapTokens    Number of overlapping tokens between chunks (default: 50).
 * @param minChunkTokens   Minimum number of tokens for the last chunk; smaller
 *                         trailing chunks are absorbed into the preceding chunk
 *                         (default: 1, must be positive).
 */
class TokenAwareChunker(
    private val maxTokens: Int = 500,
    private val overlapTokens: Int = 50,
    private val minChunkTokens: Int = 1,
) : Chunker {

    init {
        require(maxTokens > 0) { "TokenAwareChunker: maxTokens must be positive, got $maxTokens" }
        require(overlapTokens >= 0) { "TokenAwareChunker: overlapTokens must be non-negative, got $overlapTokens" }
        require(overlapTokens < maxTokens) { "TokenAwareChunker: overlapTokens ($overlapTokens) must be less than maxTokens ($maxTokens)" }
        require(minChunkTokens > 0) { "TokenAwareChunker: minChunkTokens must be positive, got $minChunkTokens" }
    }

    companion object {
        private val whitespaceRegex = Regex("\\s+")
    }

    override fun chunk(document: Document): List<Document> {
        if (document.content.isBlank()) return emptyList()

        val tokens = document.content.split(whitespaceRegex)
        if (tokens.size <= maxTokens) {
            return listOf(
                Document(
                    source = document.source,
                    content = document.content,
                    metadata = document.metadata + mapOf("chunk_index" to "0"),
                )
            )
        }

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < tokens.size) {
            val end = minOf(start + maxTokens, tokens.size)
            val chunkText = tokens.subList(start, end).joinToString(" ")
            chunks.add(chunkText)
            start += maxTokens - overlapTokens
            if (start >= tokens.size) break
        }

        // Merge degenerate trailing chunk into previous to avoid
        // near-empty chunks with high overlap settings
        if (chunks.size > 1) {
            val lastTokens = chunks.last().split(" ")
            if (lastTokens.size < minChunkTokens) {
                val mergedTokens = chunks[chunks.size - 2].split(" ") + lastTokens
                // Cap merged chunk at maxTokens + overlapTokens to prevent unbounded growth
                val cap = maxTokens + overlapTokens
                val capped = if (mergedTokens.size > cap) mergedTokens.take(cap) else mergedTokens
                chunks[chunks.size - 2] = capped.joinToString(" ")
                chunks.removeAt(chunks.size - 1)
            }
        }

        return chunks.mapIndexed { index, text ->
            Document(
                source = document.source,
                content = text,
                metadata = document.metadata + mapOf("chunk_index" to index.toString()),
            )
        }.filter { it.content.isNotBlank() }
    }
}
