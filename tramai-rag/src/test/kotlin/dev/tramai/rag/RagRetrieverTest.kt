package dev.tramai.rag

import dev.tramai.vectorstore.VectorEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach

class RagRetrieverTest {

    private val embeddingModel = InMemoryEmbeddingModel(dimensions = 4)
    private val vectorStore = InMemoryVectorStore()

    @BeforeEach
    fun cleanup() {
        runBlocking {
            vectorStore.clearAll()
        }
    }

    @Test
    fun `retrieve returns results ordered by similarity`() = runBlocking {
        val retriever = RagRetriever(embeddingModel, vectorStore, topK = 3, minScore = 0.0)

        val aiDocText = "Tramai is an AI library for the JVM"
        val kotlinDocText = "Kotlin is a programming language"
        val weatherDocText = "Weather is nice today"

        vectorStore.upsert("test_collection", listOf(
            VectorEntry(
                id = "doc1",
                vector = embeddingModel.embed(aiDocText),
                content = aiDocText,
                metadata = mapOf("topic" to "ai"),
            ),
            VectorEntry(
                id = "doc2",
                vector = embeddingModel.embed(kotlinDocText),
                content = kotlinDocText,
                metadata = mapOf("topic" to "programming"),
            ),
            VectorEntry(
                id = "doc3",
                vector = embeddingModel.embed(weatherDocText),
                content = weatherDocText,
                metadata = mapOf("topic" to "weather"),
            ),
        ))

        // Query using exact text from doc1 - hash-based embedding ensures exact match
        val results = retriever.retrieve("test_collection", aiDocText)

        assertTrue(results.isNotEmpty())
        // Exact text match should have score 1.0 (identical hash vectors)
        val exactMatch = results.find { it.content == aiDocText }
        assertNotNull(exactMatch)
        assertEquals(1.0, exactMatch.score, 0.001)
    }

    @Test
    fun `retrieve returns empty for nonexistent collection`() = runBlocking {
        val retriever = RagRetriever(embeddingModel, vectorStore, topK = 5, minScore = 0.0)

        val results = retriever.retrieve("nonexistent", "some query")

        assertTrue(results.isEmpty())
    }

    @Test
    fun `retrieve filters by minScore`() = runBlocking {
        val retriever = RagRetriever(embeddingModel, vectorStore, topK = 5, minScore = 0.5)

        val docText = "Some document text"
        vectorStore.upsert("score_test", listOf(
            VectorEntry(
                id = "d1",
                vector = embeddingModel.embed(docText),
                content = docText,
            ),
        ))

        // Exact match should have score 1.0, above 0.5 threshold
        val results = retriever.retrieve("score_test", docText)
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.score >= 0.5 })
    }

    @Test
    fun `retrieve with metadata filter`() = runBlocking {
        val retriever = RagRetriever(embeddingModel, vectorStore, topK = 5, minScore = 0.0)

        val aiText = "AI content"
        val noteText = "More AI content"

        vectorStore.upsert("filter_test", listOf(
            VectorEntry(
                id = "d1",
                vector = embeddingModel.embed(aiText),
                content = aiText,
                metadata = mapOf("type" to "article"),
            ),
            VectorEntry(
                id = "d2",
                vector = embeddingModel.embed(noteText),
                content = noteText,
                metadata = mapOf("type" to "note"),
            ),
        ))

        // Use exact text from d1 to guarantee a match
        val results = retriever.retrieve("filter_test", aiText, filter = mapOf("type" to "article"))

        assertEquals(1, results.size)
        assertEquals("d1", results[0].id)
    }

    @Test
    fun `retrieve respects topK`() = runBlocking {
        val retriever = RagRetriever(embeddingModel, vectorStore, topK = 1, minScore = 0.0)

        val docText = "First doc"
        vectorStore.upsert("topk_test", listOf(
            VectorEntry("a", embeddingModel.embed(docText), docText),
            VectorEntry("b", embeddingModel.embed("Second doc"), "Second doc"),
        ))

        val results = retriever.retrieve("topk_test", docText)

        assertEquals(1, results.size)
    }

    @Test
    fun `retrieve validates blank query`() {
        val retriever = RagRetriever(embeddingModel, vectorStore)
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            runBlocking { retriever.retrieve("col", "  ") }
        }
        assertTrue(exception.message!!.contains("query must not be blank"))
    }

    @Test
    fun `retrieve validates blank collection`() {
        val retriever = RagRetriever(embeddingModel, vectorStore)
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            runBlocking { retriever.retrieve("  ", "query") }
        }
        assertTrue(exception.message!!.contains("collection must not be blank"))
    }

    @Test
    fun `retriever validates constructor parameters`() {
        val exception1 = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            RagRetriever(embeddingModel, vectorStore, topK = 0)
        }
        assertTrue(exception1.message!!.contains("topK must be positive"))

        val exception2 = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            RagRetriever(embeddingModel, vectorStore, minScore = -0.1)
        }
        assertTrue(exception2.message!!.contains("minScore must be non-negative"))
    }
}
