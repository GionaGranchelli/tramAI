package dev.tramai.rag.chunkers

import dev.tramai.rag.Chunker
import dev.tramai.rag.Document

/**
 * Splits text recursively on descending separator priority:
 * double newline, single newline, period, space, then character.
 *
 * This implementation follows the LangChain RecursiveCharacterTextSplitter
 * approach: for each separator in priority order, it finds the LAST occurrence
 * of that separator BEFORE the chunkSize boundary, splits there, and recurses
 * on the remainder. This preserves separators at chunk boundaries.
 *
 * NOTE: This is NOT a 1:1 port of LangChain. The LangChain standard algorithm
 * finds the last occurrence of the best separator before chunkSize, splits
 * there, and recurses on the remainder. This implementation follows that
 * approach but with simplified separator priority handling.
 *
 * @param chunkSize    Maximum number of characters per chunk (default: 500).
 * @param chunkOverlap Number of overlapping characters between chunks (default: 50).
 */
class RecursiveCharacterChunker(
    private val chunkSize: Int = 500,
    private val chunkOverlap: Int = 50,
) : Chunker {

    init {
        require(chunkSize > 0) { "RecursiveCharacterChunker: chunkSize must be positive, got $chunkSize" }
        require(chunkOverlap >= 0) { "RecursiveCharacterChunker: chunkOverlap must be non-negative, got $chunkOverlap" }
        require(chunkOverlap < chunkSize) { "RecursiveCharacterChunker: chunkOverlap ($chunkOverlap) must be less than chunkSize ($chunkSize)" }
    }

    companion object {
        private val separators = listOf("\n\n", "\n", ".", " ", "")
    }

    override fun chunk(document: Document): List<Document> {
        if (document.content.isBlank()) return emptyList()

        val windowStart = 0
        val chunks = splitText(document.content, separators, 0, chunkSize, chunkOverlap, windowStart)

        return chunks.mapIndexed { index, text ->
            Document(
                source = document.source,
                content = text,
                metadata = document.metadata + mapOf("chunk_index" to index.toString()),
            )
        }.filter { it.content.isNotBlank() }
    }

    /**
     * Splits [text] recursively. Follows LangChain's approach:
     * find the LAST occurrence of the current separator BEFORE chunkSize,
     * split there, and recurse on the remaining text with the same separator.
     *
     * Overlap is integrated into the sliding window: each subsequent chunk
     * starts [overlap] characters before the previous split point, ensuring
     * the chunk content naturally includes the overlap without exceeding
     * [maxSize].
     *
     * @param text             The text to split.
     * @param remainingSep     The remaining separators to try.
     * @param depth            Recursion depth (for safety).
     * @param maxSize          Maximum chunk size.
     * @param overlap          Number of overlapping characters between chunks.
     * @param windowStart      The absolute position in the original text where
     *                         the current [text] window began.
     */
    private fun splitText(
        text: String,
        remainingSep: List<String>,
        depth: Int,
        maxSize: Int,
        overlap: Int = 0,
        windowStart: Int = 0,
    ): List<String> {
        if (text.length <= maxSize || text.isEmpty()) return listOf(text)

        // Safety limit on recursion depth
        if (depth > 100 || remainingSep.isEmpty()) {
            return splitByFixedSize(text, maxSize)
        }

        val separator = remainingSep.first()
        val nextSeparators = remainingSep.drop(1)

        // When separator is empty string, split by character
        if (separator.isEmpty()) {
            return splitByFixedSize(text, maxSize)
        }

        // Find the LAST occurrence of the separator BEFORE maxSize
        var lastValidSplit = -1
        var searchFrom = 0

        while (true) {
            val idx = text.indexOf(separator, searchFrom)
            if (idx < 0) break
            if (idx + separator.length > maxSize) break
            // We want the split to be at the END of the separator
            lastValidSplit = idx + separator.length
            searchFrom = lastValidSplit
        }

        return if (lastValidSplit > 0) {
            // Split at the last valid position
            val before = text.substring(0, lastValidSplit)
            // For overlap: start the next chunk 'overlap' characters earlier
            // so the overlap is naturally part of the chunk content without
            // exceeding maxSize via post-processing concatenation.
            val overlapStart = if (overlap > 0) maxOf(0, lastValidSplit - overlap) else lastValidSplit
            val after = text.substring(overlapStart)
            val nextWindowStart = windowStart + overlapStart

            val result = mutableListOf(before)
            result.addAll(splitText(after, remainingSep, depth + 1, maxSize, overlap, nextWindowStart))
            result
        } else {
            // Couldn't find a valid split point with this separator, try next
            splitText(text, nextSeparators, depth + 1, maxSize, overlap, windowStart)
        }
    }

    private fun splitByFixedSize(text: String, maxSize: Int): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + maxSize, text.length)
            result.add(text.substring(start, end))
            start = end
        }
        return result
    }
}
