package dev.tramai.rag

import dev.tramai.rag.chunkers.FixedSizeChunker
import dev.tramai.rag.chunkers.RecursiveCharacterChunker
import dev.tramai.rag.chunkers.TokenAwareChunker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChunkersTest {

    // ─── RecursiveCharacterChunker ───────────────────────────────────────────

    @Test
    fun `recursive chunker splits on double newline`() {
        val chunker = RecursiveCharacterChunker(chunkSize = 20, chunkOverlap = 0)
        val doc = Document("test", "Para one.  \n\nPara two.  \n\nPara three.\n\nPara four.  \n\nPara five. ")

        val chunks = chunker.chunk(doc)

        // chunkSize=20 with paragraphs ~10-12 chars each => ~2 paragraphs per chunk => ~3 chunks
        assertTrue(chunks.size in 2..5, "Expected 2-5 chunks, got ${chunks.size}")
        chunks.forEach { assertTrue(it.content.length <= 20, "Chunk too long: ${it.content.length} > 20") }
    }

    @Test
    fun `recursive chunker splits on single newline`() {
        val chunker = RecursiveCharacterChunker(chunkSize = 15, chunkOverlap = 0)
        val doc = Document("test", "Line one.\nLine two.\nLine three.\nLine four.")

        val chunks = chunker.chunk(doc)

        // Each line is ~10 chars, chunkSize=15 => ~2 lines/chunk => 2 chunks
        assertTrue(chunks.size >= 2, "Expected at least 2 chunks, got ${chunks.size}")
        chunks.forEach { assertTrue(it.content.length <= 15, "Chunk too long: ${it.content.length} > 15") }
    }

    @Test
    fun `recursive chunker splits on period when no newlines`() {
        val chunker = RecursiveCharacterChunker(chunkSize = 30, chunkOverlap = 0)
        val doc = Document("test", "Short sentence. Another one. Third text. Fourth piece. Fifth element.")

        val chunks = chunker.chunk(doc)

        // Each sentence is ~15-18 chars, chunkSize=30 means ~2 per chunk
        assertTrue(chunks.size >= 2, "Expected at least 2 chunks, got ${chunks.size}")
        chunks.forEach { assertTrue(it.content.length <= 30, "Chunk too long: ${it.content.length} > 30") }
    }

    @Test
    fun `recursive chunker returns single chunk for short text`() {
        val chunker = RecursiveCharacterChunker(chunkSize = 500, chunkOverlap = 0)
        val doc = Document("test", "Short text.")

        val chunks = chunker.chunk(doc)

        assertEquals(1, chunks.size)
        assertEquals("Short text.", chunks[0].content)
    }

    @Test
    fun `recursive chunker returns empty list for blank content`() {
        val chunker = RecursiveCharacterChunker()
        val doc = Document("test", "   ")

        val chunks = chunker.chunk(doc)

        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `recursive chunker preserves source and metadata`() {
        val chunker = RecursiveCharacterChunker(chunkSize = 50, chunkOverlap = 0)
        val doc = Document("source.txt", "Word. ".repeat(20), mapOf("author" to "test"))

        val chunks = chunker.chunk(doc)

        chunks.forEach { chunk ->
            assertEquals("source.txt", chunk.source)
            assertEquals("test", chunk.metadata["author"])
            assertNotNull(chunk.metadata["chunk_index"])
        }
    }

    @Test
    fun `recursive chunker validates chunkSize`() {
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            RecursiveCharacterChunker(chunkSize = 0)
        }
        assertTrue(exception.message!!.contains("chunkSize must be positive"))
    }

    @Test
    fun `recursive chunker validates overlap`() {
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            RecursiveCharacterChunker(chunkSize = 100, chunkOverlap = -1)
        }
        assertTrue(exception.message!!.contains("chunkOverlap must be non-negative"))
    }

    @Test
    fun `recursive chunker validates overlap less than chunkSize`() {
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            RecursiveCharacterChunker(chunkSize = 50, chunkOverlap = 60)
        }
        assertTrue(exception.message!!.contains("chunkOverlap"))
    }

    @Test
    fun `recursive chunker respects overlap`() {
        val chunker = RecursiveCharacterChunker(chunkSize = 15, chunkOverlap = 5)
        val doc = Document("test", "This is a longer text that needs splitting for testing purposes.")

        val chunks = chunker.chunk(doc)

        assertTrue(chunks.size >= 2, "Should produce at least 2 chunks, got ${chunks.size}")
        // Verify all chunks stay within chunkSize bound (with small margin for separator chars)
        chunks.forEach { chunk ->
            assertTrue(
                chunk.content.length <= 15,
                "Chunk exceeds chunkSize bound: length ${chunk.content.length} > 15. Content: '${chunk.content}'"
            )
        }
        for (i in 1 until chunks.size) {
            val prev = chunks[i - 1].content
            val curr = chunks[i].content
            assertTrue(curr.contains(prev.takeLast(5)), "Chunk $i should contain overlap from chunk ${i-1}. prev ends: '${prev.takeLast(5)}', curr starts: '${curr.take(20)}'")
        }
    }

    @Test
    fun `recursive chunker handles unicode text`() {
        val chunker = RecursiveCharacterChunker(chunkSize = 30, chunkOverlap = 0)
        val doc = Document("test", "Hello 世界. How are you today? 你好吗？This is fine.")

        val chunks = chunker.chunk(doc)

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.content.length <= 30 })
        // All chunks combined should contain all the original content
        val combined = chunks.joinToString("")
        assertTrue(combined.contains("Hello"))
        assertTrue(combined.contains("世界"))
    }

    @Test
    fun `recursive chunker handles oversize segment`() {
        // A single word longer than chunkSize should still be handled
        val chunker = RecursiveCharacterChunker(chunkSize = 10, chunkOverlap = 0)
        val doc = Document("test", "Supercalifragilisticexpialidocious is a long word.")

        val chunks = chunker.chunk(doc)

        assertTrue(chunks.isNotEmpty())
        // The long word will be split by fixed-size when no separator found
        assertTrue(chunks.size >= 2)
    }

    // ─── FixedSizeChunker ────────────────────────────────────────────────────

    @Test
    fun `fixed size chunker splits by character count`() {
        val chunker = FixedSizeChunker(chunkSize = 10, chunkOverlap = 0)
        val doc = Document("test", "abcdefghijklmnopqrstuvwxyz")

        val chunks = chunker.chunk(doc)

        assertEquals(3, chunks.size)
        assertEquals("abcdefghij", chunks[0].content)
        assertEquals("klmnopqrst", chunks[1].content)
        assertEquals("uvwxyz", chunks[2].content)
    }

    @Test
    fun `fixed size chunker returns empty for blank content`() {
        val chunker = FixedSizeChunker()
        val doc = Document("test", "")

        val chunks = chunker.chunk(doc)

        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `fixed size chunker single chunk for small text`() {
        val chunker = FixedSizeChunker(chunkSize = 100, chunkOverlap = 0)
        val doc = Document("test", "Hello")

        val chunks = chunker.chunk(doc)

        assertEquals(1, chunks.size)
        assertEquals("Hello", chunks[0].content)
    }

    @Test
    fun `fixed size chunker respects overlap`() {
        val chunker = FixedSizeChunker(chunkSize = 10, chunkOverlap = 3)
        val doc = Document("test", "abcdefghijklmnopqrstuvwxyz")

        val chunks = chunker.chunk(doc)

        assertTrue(chunks.size >= 3)
        assertEquals("hij", chunks[1].content.take(3))
    }

    @Test
    fun `fixed size chunker validates chunkSize`() {
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            FixedSizeChunker(chunkSize = 0)
        }
        assertTrue(exception.message!!.contains("chunkSize must be positive"))
    }

    // ─── TokenAwareChunker ───────────────────────────────────────────────────

    @Test
    fun `token aware chunker splits by token count`() {
        val chunker = TokenAwareChunker(maxTokens = 3, overlapTokens = 0)
        val doc = Document("test", "one two three four five six")

        val chunks = chunker.chunk(doc)

        assertEquals(2, chunks.size)
        assertEquals("one two three", chunks[0].content)
        assertEquals("four five six", chunks[1].content)
    }

    @Test
    fun `token aware chunker returns single chunk for short text`() {
        val chunker = TokenAwareChunker(maxTokens = 100, overlapTokens = 0)
        val doc = Document("test", "short text here")

        val chunks = chunker.chunk(doc)

        assertEquals(1, chunks.size)
    }

    @Test
    fun `token aware chunker returns empty for blank content`() {
        val chunker = TokenAwareChunker()
        val doc = Document("test", "   ")

        val chunks = chunker.chunk(doc)

        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `token aware chunker respects overlap`() {
        val chunker = TokenAwareChunker(maxTokens = 3, overlapTokens = 1)
        val doc = Document("test", "one two three four five six seven")

        val chunks = chunker.chunk(doc)

        assertTrue(chunks.size >= 2)
        val firstWords = chunks[0].content.split(" ")
        val secondWords = chunks[1].content.split(" ")
        assertEquals(firstWords.last(), secondWords.first())
    }

    @Test
    fun `token aware chunker validates maxTokens`() {
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            TokenAwareChunker(maxTokens = 0)
        }
        assertTrue(exception.message!!.contains("maxTokens must be positive"))
    }

    @Test
    fun `token aware chunker validates overlapTokens less than maxTokens`() {
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            TokenAwareChunker(maxTokens = 10, overlapTokens = 15)
        }
        assertTrue(exception.message!!.contains("overlapTokens"))
    }

    @Test
    fun `token aware chunker handles unicode whitespace`() {
        val chunker = TokenAwareChunker(maxTokens = 3, overlapTokens = 0)
        val doc = Document("test", "one\ttwo  three   four\tfive six")

        val chunks = chunker.chunk(doc)

        assertEquals(2, chunks.size)
        assertEquals("one two three", chunks[0].content)
    }

    @Test
    fun `recursive chunker includes empty string as final separator`() {
        // Test that even with no natural separators, the text gets split by characters
        val chunker = RecursiveCharacterChunker(chunkSize = 5, chunkOverlap = 0)
        val doc = Document("test", "ABCDEFGHIJKLMNO")

        val chunks = chunker.chunk(doc)

        assertEquals(3, chunks.size)
        assertEquals("ABCDE", chunks[0].content)
        assertEquals("FGHIJ", chunks[1].content)
        assertEquals("KLMNO", chunks[2].content)
    }
}
