package dev.tramai.rag

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.rag.chunkers.FixedSizeChunker
import dev.tramai.rag.loaders.FileDocumentLoader
import dev.tramai.vectorstore.VectorEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class RagPipelineTest {

    private val embeddingModel = InMemoryEmbeddingModel(dimensions = 4)
    private val vectorStore = InMemoryVectorStore()
    private val retriever = RagRetriever(embeddingModel, vectorStore, topK = 3, minScore = 0.0)
    private val chunker = FixedSizeChunker(chunkSize = 100, chunkOverlap = 0)
    private val contextInjector = ContextInjector()

    @BeforeEach
    @AfterEach
    fun cleanup() = runBlocking {
        vectorStore.clearAll()
    }

    @Test
    fun `index loads chunks and upserts to vector store`() { runBlocking {
        val tempFile = Files.createTempFile("rag-test-", ".txt")
        tempFile.toFile().deleteOnExit()
        Files.writeString(tempFile, "Tramai is a structured-first AI library. ".repeat(5))

        val loader = FileDocumentLoader()
        val pipeline = RagPipeline(loader, chunker, embeddingModel, vectorStore, retriever, contextInjector)

        val chunkCount = pipeline.index(tempFile.toString(), "test_collection")

        assertTrue(chunkCount > 0, "Should have indexed at least one chunk")

        val results = vectorStore.search("test_collection", embeddingModel.embed("Tramai"), topK = 5)
        assertTrue(results.isNotEmpty(), "Should have stored vectors")
        assertEquals(chunkCount, results.size)
    }
    }

    @Test
    fun `query retrieves and injects context`() { runBlocking {
        val ragDocText = "The RAG pipeline retrieves documents from a vector store."
        vectorStore.upsert("query_test", listOf(
            VectorEntry(
                id = "rag_doc",
                vector = embeddingModel.embed(ragDocText),
                content = ragDocText,
                metadata = mapOf("topic" to "rag"),
            ),
        ))

        val loader = object : DocumentLoader {
            override suspend fun load(source: String): Document {
                return Document(source, "Dummy content")
            }
        }
        val pipeline = RagPipeline(loader, chunker, embeddingModel, vectorStore, retriever, contextInjector)

        val request = ModelRequest(
            model = "test-model",
            messages = listOf(
                Message(role = MessageRole.USER, content = "How does the RAG pipeline work?"),
            ),
        )

        // Use exact text from indexed doc to guarantee match with hash-based embedding
        val enriched = pipeline.query(ragDocText, request, "query_test")

        assertNotNull(enriched)
        assertTrue(enriched.messages[0].content.contains("The following information may be relevant:"))
        assertTrue(enriched.messages[0].content.contains(ragDocText))
        assertTrue(enriched.messages[0].content.contains("How does the RAG pipeline work?"))
    }
    }

    @Test
    fun `query with empty results returns original request`() { runBlocking {
        val loader = object : DocumentLoader {
            override suspend fun load(source: String): Document {
                return Document(source, "Dummy")
            }
        }
        val pipeline = RagPipeline(loader, chunker, embeddingModel, vectorStore, retriever, contextInjector)

        val request = ModelRequest(
            model = "test",
            messages = listOf(Message(role = MessageRole.USER, content = "Hello")),
        )

        val enriched = pipeline.query("nonexistent topic", request, "empty_collection")

        assertEquals(request, enriched)
    }
    }

    @Test
    fun `index returns 0 for blank document`() { runBlocking {
        val tempFile = Files.createTempFile("rag-empty-", ".txt")
        tempFile.toFile().deleteOnExit()
        Files.writeString(tempFile, "")

        val loader = FileDocumentLoader()
        val pipeline = RagPipeline(loader, chunker, embeddingModel, vectorStore, retriever, contextInjector)

        val chunkCount = pipeline.index(tempFile.toString(), "empty_test")

        assertEquals(0, chunkCount)
    }
    }

    @Test
    fun `pipeline validates blank source`() {
        val loader = object : DocumentLoader {
            override suspend fun load(source: String): Document = Document("", "")
        }
        val pipeline = RagPipeline(loader, chunker, embeddingModel, vectorStore, retriever, contextInjector)

        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            runBlocking { pipeline.index("  ", "col") }
        }
        assertTrue(exception.message!!.contains("source must not be blank"))
    }

    @Test
    fun `pipeline validates blank collection on index`() {
        val loader = object : DocumentLoader {
            override suspend fun load(source: String): Document = Document("src", "content")
        }
        val pipeline = RagPipeline(loader, chunker, embeddingModel, vectorStore, retriever, contextInjector)

        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            runBlocking { pipeline.index("src", "  ") }
        }
        assertTrue(exception.message!!.contains("collection must not be blank"))
    }

    @Test
    fun `pipeline validates blank query`() {
        val loader = object : DocumentLoader {
            override suspend fun load(source: String): Document = Document("src", "content")
        }
        val pipeline = RagPipeline(loader, chunker, embeddingModel, vectorStore, retriever, contextInjector)
        val request = ModelRequest("model", listOf(Message(MessageRole.USER, "hi")))

        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            runBlocking { pipeline.query("  ", request, "col") }
        }
        assertTrue(exception.message!!.contains("query must not be blank"))
    }

    @Test
    fun `pipeline end to end with file loader`() { runBlocking {
        val tempFile = Files.createTempFile("rag-e2e-", ".txt")
        tempFile.toFile().deleteOnExit()
        Files.writeString(tempFile, "Tramai is an AI workflow library for the JVM.")

        val loader = FileDocumentLoader()
        val e2eChunker = FixedSizeChunker(chunkSize = 200, chunkOverlap = 0)
        val pipeline = RagPipeline(loader, e2eChunker, embeddingModel, vectorStore, retriever, contextInjector)

        val count = pipeline.index(tempFile.toString(), "e2e_test")
        assertEquals(1, count)

        val request = ModelRequest(
            model = "test",
            messages = listOf(Message(role = MessageRole.USER, content = "Tell me about Tramai.")),
        )

        // Query with exact text from indexed content for deterministic hash-based matching
        val enriched = pipeline.query("Tramai is an AI workflow library for the JVM.", request, "e2e_test")
        assertTrue(enriched.messages[0].content.contains("The following information may be relevant:"))
    }
    }

    @Test
    fun `query with non-null filter returns filtered results`() { runBlocking {
        vectorStore.upsert("filter_query_test", listOf(
            VectorEntry(
                id = "matched",
                vector = embeddingModel.embed("Specific AI content"),
                content = "Specific AI content",
                metadata = mapOf("category" to "ai"),
            ),
            VectorEntry(
                id = "excluded",
                vector = embeddingModel.embed("Other content"),
                content = "Other content",
                metadata = mapOf("category" to "other"),
            ),
        ))

        val loader = object : DocumentLoader {
            override suspend fun load(source: String): Document {
                return Document(source, "Dummy")
            }
        }
        val pipeline = RagPipeline(loader, chunker, embeddingModel, vectorStore, retriever, contextInjector)

        val request = ModelRequest(
            model = "test",
            messages = listOf(Message(role = MessageRole.USER, content = "Tell me about AI.")),
        )

        val enriched = pipeline.query("Specific AI content", request, "filter_query_test", filter = mapOf("category" to "ai"))

        assertTrue(enriched.messages[0].content.contains("Specific AI content"))
        assertFalse(enriched.messages[0].content.contains("Other content"))
    }
    }

    @Test
    fun `index generates safe IDs`() { runBlocking {
        val tempFile = Files.createTempFile("rag-idtest-", ".txt")
        tempFile.toFile().deleteOnExit()
        Files.writeString(tempFile, "First chunk. Second chunk. Third chunk.")

        val loader = FileDocumentLoader()
        val pipeline = RagPipeline(loader, chunker, embeddingModel, vectorStore, retriever, contextInjector)

        val count = pipeline.index(tempFile.toString(), "id_test")
        assertTrue(count > 0)

        // Verify entries have hex IDs (no :: separator)
        val results = vectorStore.search("id_test", embeddingModel.embed("chunk"), 10)
        assertTrue(results.isNotEmpty())
        results.forEach { result ->
            // IDs should be hex strings, not containing ::
            assertTrue(!result.id.contains("::"), "ID should not contain :: but was: ${result.id}")
            assertTrue(result.id.matches(Regex("[0-9a-f]+")), "ID should be hex but was: ${result.id}")
        }
    }
    }

    @Test
    fun `pipeline throws RagPipelineException on loader failure`() {
        val failingLoader = object : DocumentLoader {
            override suspend fun load(source: String): Document {
                throw RuntimeException("Simulated loader failure")
            }
        }
        val pipeline = RagPipeline(failingLoader, chunker, embeddingModel, vectorStore, retriever, contextInjector)

        val exception = org.junit.jupiter.api.assertThrows<RagPipelineException> {
            runBlocking { pipeline.index("test.txt", "col") }
        }
        assertTrue(exception.message!!.contains("failed to load document"))
    }

    @Test
    fun `reindex replaces previous entries`() { runBlocking {
        val tempFile = Files.createTempFile("rag-reindex-", ".txt")
        tempFile.toFile().deleteOnExit()
        Files.writeString(tempFile, "Content to reindex.")

        val loader = FileDocumentLoader()
        val pipeline = RagPipeline(loader, chunker, embeddingModel, vectorStore, retriever, contextInjector)

        val count1 = pipeline.index(tempFile.toString(), "reindex_test")
        assertTrue(count1 > 0)

        val count2 = pipeline.index(tempFile.toString(), "reindex_test")
        assertTrue(count2 > 0)

        // After re-indexing, the collection should have the new entries (not duplicates)
        val results = vectorStore.search("reindex_test", embeddingModel.embed("reindex"), 10)
        // IDs are hash-based so they'll be the same - but upsert replaces them
        assertTrue(results.isNotEmpty())
        // Make sure we don't have way more entries than expected
        assertTrue(results.size <= count2 + 1)
    }
    }

    @Test
    fun `concurrent index operations complete without error`() { runBlocking {
        val tempFile1 = Files.createTempFile("rag-concurrent1-", ".txt")
        tempFile1.toFile().deleteOnExit()
        Files.writeString(tempFile1, "First concurrent document content for testing.")

        val tempFile2 = Files.createTempFile("rag-concurrent2-", ".txt")
        tempFile2.toFile().deleteOnExit()
        Files.writeString(tempFile2, "Second concurrent document with different content.")

        val loader = FileDocumentLoader()
        val pipeline = RagPipeline(loader, chunker, embeddingModel, vectorStore, retriever, contextInjector)

        coroutineScope {
            val deferred1 = async { pipeline.index(tempFile1.toString(), "concurrent_test") }
            val deferred2 = async { pipeline.index(tempFile2.toString(), "concurrent_test") }
            val count1 = deferred1.await()
            val count2 = deferred2.await()
            assertTrue(count1 > 0, "First concurrent index should produce chunks")
            assertTrue(count2 > 0, "Second concurrent index should produce chunks")
        }

        val results = vectorStore.search("concurrent_test", embeddingModel.embed("document"), 10)
        assertTrue(results.isNotEmpty(), "Concurrent index should store results")
    }
    }
}
