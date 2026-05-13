package dev.tramai.vectorstore

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryVectorStoreTest {

    @Test
    fun `upsert and search returns nearest vectors`() = runBlocking {
        val store = InMemoryVectorStore()

        store.upsert("test", listOf(
            VectorEntry("1", floatArrayOf(1f, 0f, 0f), "Document A", mapOf("type" to "article")),
            VectorEntry("2", floatArrayOf(0f, 1f, 0f), "Document B", mapOf("type" to "note")),
            VectorEntry("3", floatArrayOf(1f, 1f, 0f), "Document C", mapOf("type" to "article")),
        ))

        val results = store.search("test", floatArrayOf(1f, 0f, 0f), topK = 2)
        assertEquals(2, results.size)
        assertEquals("Document A", results[0].content)
        assertEquals("1", results[0].id)
        assertTrue(results[0].score > 0.99)
    }

    @Test
    fun `search with empty collection returns empty`() = runBlocking {
        val store = InMemoryVectorStore()
        val results = store.search("nonexistent", floatArrayOf(1f, 0f, 0f), topK = 5)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search with metadata filter`() = runBlocking {
        val store = InMemoryVectorStore()

        store.upsert("test", listOf(
            VectorEntry("1", floatArrayOf(1f, 0f), "Article A", mapOf("type" to "article")),
            VectorEntry("2", floatArrayOf(0f, 1f), "Note B", mapOf("type" to "note")),
        ))

        val results = store.search("test", floatArrayOf(1f, 1f), topK = 5, filter = mapOf("type" to "article"))
        assertEquals(1, results.size)
        assertEquals("Article A", results[0].content)
        assertEquals("1", results[0].id)
    }

    @Test
    fun `delete removes specific vectors`() = runBlocking {
        val store = InMemoryVectorStore()

        store.upsert("test", listOf(
            VectorEntry("1", floatArrayOf(1f, 0f), "Doc A"),
            VectorEntry("2", floatArrayOf(0f, 1f), "Doc B"),
        ))

        store.delete("test", listOf("1"))

        val results = store.search("test", floatArrayOf(1f, 1f), topK = 5)
        assertEquals(1, results.size)
        assertEquals("Doc B", results[0].content)
    }

    @Test
    fun `listCollections returns collection names`() = runBlocking {
        val store = InMemoryVectorStore()

        store.upsert("alpha", listOf(VectorEntry("1", floatArrayOf(1f), "A")))
        store.upsert("beta", listOf(VectorEntry("2", floatArrayOf(1f), "B")))

        val collections = store.listCollections()
        assertEquals(listOf("alpha", "beta"), collections)
    }

    @Test
    fun `cosine similarity of orthogonal vectors`() = runBlocking {
        val store = InMemoryVectorStore()

        store.upsert("test", listOf(
            VectorEntry("1", floatArrayOf(0f, 1f), "Perpendicular"),
        ))

        val results = store.search("test", floatArrayOf(1f, 0f), topK = 5)
        // Orthogonal vectors have 0 similarity; they are now included (the >0 filter was removed).
        assertEquals(1, results.size)
        assertTrue(results[0].score <= 0.01)
    }

    @Test
    fun `search result includes id`() = runBlocking {
        val store = InMemoryVectorStore()

        store.upsert("test", listOf(
            VectorEntry("my-id-42", floatArrayOf(1f, 0f), "Content"),
        ))

        val results = store.search("test", floatArrayOf(1f, 0f), topK = 5)
        assertEquals(1, results.size)
        assertEquals("my-id-42", results[0].id)
    }
}
