package dev.tramai.memory

import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ToolCall
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class MemoryInterceptorTest {

    // ── interceptRequest ─────────────────────────────────────────

    @Test
    fun `interceptRequest prepends history to messages`() {
        val memory = InMemoryChatMemory()
        val history = listOf(
            Message(MessageRole.USER, "previous question"),
            Message(MessageRole.ASSISTANT, "previous answer"),
        )
        memory.add("conv-1", history)

        val interceptor = MemoryInterceptor(memory)
        val messages = listOf(Message(MessageRole.USER, "new question"))
        val result = interceptor.interceptRequest("conv-1", messages)

        assertThat(result).containsExactly(
            Message(MessageRole.USER, "previous question"),
            Message(MessageRole.ASSISTANT, "previous answer"),
            Message(MessageRole.USER, "new question"),
        )
    }

    @Test
    fun `interceptRequest returns messages unchanged when no history`() {
        val memory = InMemoryChatMemory()
        val interceptor = MemoryInterceptor(memory)
        val messages = listOf(Message(MessageRole.USER, "first message"))
        val result = interceptor.interceptRequest("conv-1", messages)

        assertThat(result).isSameAs(messages)
    }

    @Test
    fun `interceptRequest deduplicates system messages when history has one`() {
        val memory = InMemoryChatMemory()
        memory.add("conv-1", listOf(Message(MessageRole.SYSTEM, "stored-system")))

        val interceptor = MemoryInterceptor(memory)
        val messages = listOf(
            Message(MessageRole.SYSTEM, "current-system"),
            Message(MessageRole.USER, "user message"),
        )
        val result = interceptor.interceptRequest("conv-1", messages)

        // History's system message is kept, current request's system is removed
        val systemMessages = result.filter { it.role == MessageRole.SYSTEM }
        assertThat(systemMessages).hasSize(1)
        assertThat(systemMessages.first().content).isEqualTo("stored-system")
    }

    @Test
    fun `interceptRequest keeps system message when history has none`() {
        val memory = InMemoryChatMemory()
        memory.add("conv-1", listOf(
            Message(MessageRole.USER, "previous question"),
            Message(MessageRole.ASSISTANT, "previous answer"),
        ))

        val interceptor = MemoryInterceptor(memory)
        val messages = listOf(
            Message(MessageRole.SYSTEM, "current-system"),
            Message(MessageRole.USER, "new question"),
        )
        val result = interceptor.interceptRequest("conv-1", messages)

        val systemMessages = result.filter { it.role == MessageRole.SYSTEM }
        assertThat(systemMessages).hasSize(1)
        assertThat(systemMessages.first().content).isEqualTo("current-system")
    }

    @Test
    fun `interceptRequest preserves non-system messages when deduping`() {
        val memory = InMemoryChatMemory()
        memory.add("conv-1", listOf(
            Message(MessageRole.SYSTEM, "old-system"),
            Message(MessageRole.USER, "old question"),
        ))

        val interceptor = MemoryInterceptor(memory)
        val messages = listOf(
            Message(MessageRole.SYSTEM, "new-system"),
            Message(MessageRole.USER, "new question"),
        )
        val result = interceptor.interceptRequest("conv-1", messages)

        assertThat(result).hasSize(3) // old-system, old question, new question
        assertThat(result.map { it.content }).containsExactly(
            "old-system", "old question", "new question",
        )
    }

    @Test
    fun `interceptRequest handles empty current messages`() {
        val memory = InMemoryChatMemory()
        memory.add("conv-1", listOf(Message(MessageRole.USER, "existing")))

        val interceptor = MemoryInterceptor(memory)
        val result = interceptor.interceptRequest("conv-1", emptyList())

        assertThat(result).containsExactly(Message(MessageRole.USER, "existing"))
    }

    // ── interceptResponse ────────────────────────────────────────

    @Test
    fun `interceptResponse persists user messages and assistant response`() {
        val memory = InMemoryChatMemory()
        val interceptor = MemoryInterceptor(memory)

        val requestMessages = listOf(
            Message(MessageRole.SYSTEM, "system"),
            Message(MessageRole.USER, "user question"),
        )
        val response = ModelResponse(content = "assistant answer")

        interceptor.interceptResponse("conv-1", requestMessages, response)

        val history = memory.get("conv-1")
        assertThat(history).containsExactly(
            Message(MessageRole.USER, "user question"),
            Message(MessageRole.ASSISTANT, "assistant answer"),
        )
    }

    @Test
    fun `interceptResponse persists only user messages not system or tool`() {
        val memory = InMemoryChatMemory()
        val interceptor = MemoryInterceptor(memory)

        val requestMessages = listOf(
            Message(MessageRole.SYSTEM, "system-prompt"),
            Message(MessageRole.USER, "user-1"),
            Message(MessageRole.TOOL, "tool-result", toolCallId = "call-1"),
        )
        val response = ModelResponse(content = "answer")

        interceptor.interceptResponse("conv-1", requestMessages, response)

        val history = memory.get("conv-1")
        assertThat(history).hasSize(2)
        assertThat(history.map { it.role }).containsExactly(MessageRole.USER, MessageRole.ASSISTANT)
    }

    @Test
    fun `interceptResponse persists multiple user messages`() {
        val memory = InMemoryChatMemory()
        val interceptor = MemoryInterceptor(memory)

        val requestMessages = listOf(
            Message(MessageRole.USER, "first question"),
            Message(MessageRole.USER, "follow up"),
        )
        val response = ModelResponse(content = "final answer")

        interceptor.interceptResponse("conv-1", requestMessages, response)

        val history = memory.get("conv-1")
        assertThat(history).hasSize(3)
        assertThat(history.map { it.content }).containsExactly(
            "first question", "follow up", "final answer",
        )
    }

    @Test
    fun `interceptResponse persists assistant tool calls in response`() {
        val memory = InMemoryChatMemory()
        val interceptor = MemoryInterceptor(memory)

        val requestMessages = listOf(Message(MessageRole.USER, "use a tool"))
        val toolCalls = listOf(ToolCall(id = "call-1", name = "lookup", argumentsJson = "{}"))
        val response = ModelResponse(content = "", toolCalls = toolCalls)

        interceptor.interceptResponse("conv-1", requestMessages, response)

        val history = memory.get("conv-1")
        assertThat(history).hasSize(2)
        assertThat(history.last().toolCalls).isEqualTo(toolCalls)
    }

    @Test
    fun `interceptResponse appends to existing history`() {
        val memory = InMemoryChatMemory()
        memory.add("conv-1", listOf(
            Message(MessageRole.USER, "previous question"),
            Message(MessageRole.ASSISTANT, "previous answer"),
        ))

        val interceptor = MemoryInterceptor(memory)
        val requestMessages = listOf(Message(MessageRole.USER, "new question"))
        val response = ModelResponse(content = "new answer")

        interceptor.interceptResponse("conv-1", requestMessages, response)

        val history = memory.get("conv-1")
        assertThat(history).hasSize(4)
        assertThat(history.map { it.content }).containsExactly(
            "previous question", "previous answer",
            "new question", "new answer",
        )
    }

    @Test
    fun `interceptResponse handles empty request messages`() {
        val memory = InMemoryChatMemory()
        val interceptor = MemoryInterceptor(memory)
        val response = ModelResponse(content = "orphan answer")

        interceptor.interceptResponse("conv-1", emptyList(), response)

        val history = memory.get("conv-1")
        assertThat(history).containsExactly(
            Message(MessageRole.ASSISTANT, "orphan answer"),
        )
    }

    @Test
    fun `interceptResponse handles response with no content`() {
        val memory = InMemoryChatMemory()
        val interceptor = MemoryInterceptor(memory)
        val requestMessages = listOf(Message(MessageRole.USER, "hi"))

        interceptor.interceptResponse("conv-1", requestMessages, ModelResponse(content = ""))

        val history = memory.get("conv-1")
        assertThat(history).hasSize(2)
        assertThat(history.last().content).isEqualTo("")
    }

    /**
     * Minimal in-memory ChatMemory for testing MemoryInterceptor.
     */
    private class InMemoryChatMemory : ChatMemory {
        private val store = mutableMapOf<String, MutableList<Message>>()

        override fun get(conversationId: String): List<Message> {
            require(conversationId.isNotBlank())
            return store[conversationId]?.toList() ?: emptyList()
        }

        override fun add(conversationId: String, messages: List<Message>) {
            require(conversationId.isNotBlank())
            store.getOrPut(conversationId) { mutableListOf() }.addAll(messages)
        }

        override fun add(conversationId: String, message: Message) {
            require(conversationId.isNotBlank())
            store.getOrPut(conversationId) { mutableListOf() }.add(message)
        }

        override fun clear(conversationId: String) {
            require(conversationId.isNotBlank())
            store.remove(conversationId)
        }
    }
}
