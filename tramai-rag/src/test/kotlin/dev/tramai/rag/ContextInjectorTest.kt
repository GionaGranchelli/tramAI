package dev.tramai.rag

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.vectorstore.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextInjectorTest {

    private val injector = ContextInjector()

    private val sampleRequest = ModelRequest(
        model = "test-model",
        messages = listOf(
            Message(role = MessageRole.USER, content = "What is Tramai?"),
        ),
    )

    private val sampleResults = listOf(
        SearchResult("1", "Tramai is an AI library.", mapOf("source" to "docs"), 0.95),
        SearchResult("2", "It supports structured output.", mapOf("source" to "docs"), 0.85),
    )

    @Test
    fun `inject prepends context to first user message`() {
        val enriched = injector.inject(sampleResults, sampleRequest)

        assertEquals(1, enriched.messages.size)
        assertEquals(MessageRole.USER, enriched.messages[0].role)
        assertTrue(enriched.messages[0].content.contains("Tramai is an AI library."))
        assertTrue(enriched.messages[0].content.contains("It supports structured output."))
        assertTrue(enriched.messages[0].content.contains("What is Tramai?"))
        assertTrue(enriched.messages[0].content.startsWith("The following information may be relevant:"))
    }

    @Test
    fun `inject with empty results returns original request`() {
        val enriched = injector.inject(emptyList(), sampleRequest)

        assertEquals(sampleRequest, enriched)
    }

    @Test
    fun `inject preserves other request fields`() {
        val request = ModelRequest(
            model = "gpt-4",
            messages = listOf(
                Message(role = MessageRole.SYSTEM, content = "You are helpful."),
                Message(role = MessageRole.USER, content = "Hello"),
            ),
            maxTokens = 500,
            temperature = 0.7,
        )

        val enriched = injector.inject(sampleResults, request)

        assertEquals("gpt-4", enriched.model)
        assertEquals(500, enriched.maxTokens)
        assertEquals(0.7, enriched.temperature)
        assertEquals(2, enriched.messages.size)
        assertEquals(MessageRole.SYSTEM, enriched.messages[0].role)
        assertEquals("You are helpful.", enriched.messages[0].content)
        assertEquals(MessageRole.USER, enriched.messages[1].role)
        assertTrue(enriched.messages[1].content.contains("The following information may be relevant:"))
    }

    @Test
    fun `inject appends context to existing system message when no user message exists`() {
        val request = ModelRequest(
            model = "test",
            messages = listOf(
                Message(role = MessageRole.SYSTEM, content = "System prompt."),
                Message(role = MessageRole.ASSISTANT, content = "Assistant reply."),
            ),
        )

        val enriched = injector.inject(sampleResults, request)

        // Should append context to the existing system message, not create a new one
        assertEquals(2, enriched.messages.size)
        assertEquals(MessageRole.SYSTEM, enriched.messages[0].role)
        assertTrue(enriched.messages[0].content.contains("System prompt."))
        assertTrue(enriched.messages[0].content.contains("The following information may be relevant:"))
        assertTrue(enriched.messages[0].content.contains("Tramai is an AI library."))
        assertEquals(MessageRole.ASSISTANT, enriched.messages[1].role)
    }

    @Test
    fun `inject creates system message when no user or system message exists`() {
        val noUserRequest = ModelRequest(
            model = "test",
            messages = listOf(
                Message(role = MessageRole.ASSISTANT, content = "Assistant reply."),
            ),
        )

        val enriched = injector.inject(sampleResults, noUserRequest)

        // Should prepend a system message with context
        assertEquals(2, enriched.messages.size)
        assertEquals(MessageRole.SYSTEM, enriched.messages[0].role)
        assertTrue(enriched.messages[0].content.contains("The following information may be relevant:"))
        assertEquals(MessageRole.ASSISTANT, enriched.messages[1].role)
    }

    @Test
    fun `inject handles single result`() {
        val singleResult = listOf(
            SearchResult("1", "Single piece of context.", mapOf(), 0.9),
        )

        val enriched = injector.inject(singleResult, sampleRequest)

        assertTrue(enriched.messages[0].content.contains("Single piece of context."))
        assertTrue(enriched.messages[0].content.contains("Use the information above to answer the user's question."))
    }

    @Test
    fun `inject includes source and score provenance`() {
        val results = listOf(
            SearchResult("1", "Context content.", mapOf("source" to "manual"), 0.95),
        )

        val enriched = injector.inject(results, sampleRequest)

        assertTrue(enriched.messages[0].content.contains("Source: manual"))
        assertTrue(enriched.messages[0].content.contains("Score: 0.950"))
    }

    @Test
    fun `inject does not modify original request`() {
        val original = sampleRequest.copy()
        injector.inject(sampleResults, sampleRequest)

        // Original should be unchanged
        assertEquals(original, sampleRequest)
        assertEquals(1, sampleRequest.messages.size)
        assertEquals("What is Tramai?", sampleRequest.messages[0].content)
    }
}
