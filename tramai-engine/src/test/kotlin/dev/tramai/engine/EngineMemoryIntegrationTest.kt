package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.ConversationId
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.SystemPrompt
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.provider.ModelProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class EngineMemoryIntegrationTest {

    // ── Simple Persistence ──────────────────────────────────────

    @Test
    fun `persists user messages and assistant responses when chatMemory is configured`() {
        val memory = TestMemory()
        val provider = TestProvider { ModelResponse(content = "hello from provider") }
        val engine = TramaiEngine(provider = provider, chatMemory = memory)
        val service = engine.create<TestMemoryChatService>()

        val result = runBlocking { service.chat(sessionId = "s1", message = "hi") }

        assertThat(result).isEqualTo("hello from provider")
        val history = memory.get("s1")
        assertThat(history).hasSize(2)
        assertThat(history[0].role).isEqualTo(MessageRole.USER)
        assertThat(history[1].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(history[1].content).isEqualTo("hello from provider")
    }

    @Test
    fun `does not persist when chatMemory is null`() {
        val provider = TestProvider { ModelResponse(content = "answer") }
        val engine = TramaiEngine(provider = provider)
        val service = engine.create<TestMemoryChatService>()

        val result = runBlocking { service.chat(sessionId = "s1", message = "hi") }

        assertThat(result).isEqualTo("answer")
        // No crash should occur when chatMemory is null
    }

    @Test
    fun `clear memory after a request works for subsequent requests`() {
        val memory = TestMemory()
        val provider = TestProvider { ModelResponse(content = "answer") }
        val engine = TramaiEngine(provider = provider, chatMemory = memory)
        val service = engine.create<TestMemoryChatService>()

        runBlocking { service.chat(sessionId = "s1", message = "first") }
        assertThat(memory.get("s1")).hasSize(2)

        memory.clear("s1")
        assertThat(memory.get("s1")).isEmpty()

        val result = runBlocking { service.chat(sessionId = "s1", message = "second") }
        assertThat(result).isEqualTo("answer")
        // After clear, the history should be only the new turn
        assertThat(memory.get("s1")).hasSize(2)
        assertThat(memory.get("s1").first().content).contains("second")
    }

    // ── Tool Call Persistence ───────────────────────────────────

    @Test
    fun `persists tool messages from the loop when chatMemory is configured with a tool`() {
        val memory = TestMemory()
        val provider = TestToolCallingProvider(
            ModelResponse(
                content = "",
                toolCalls = listOf(ToolCall("call-1", "lookup", """{"query":"test"}""")),
            ),
            ModelResponse(content = "final result"),
        )
        val tool = object : ResolvedTool {
            override val name: String = "lookup"
            override val description: String = "Lookup tool"
            override val inputSchemaJson: String = """{"type":"object"}"""
            override val idempotent: Boolean = false
            override val sideEffectLevel: dev.tramai.core.model.SideEffectLevel =
                dev.tramai.core.model.SideEffectLevel.READ_ONLY

            override suspend fun execute(
                input: Any,
                context: ToolExecutionContext,
            ): ToolResult = ToolResult.Success("""{"value":"resolved"}""")
        }
        val engine = TramaiEngine(
            provider = provider,
            chatMemory = memory,
            toolRegistry = ToolRegistry(mapOf(tool.name to tool)),
        )
        val service = engine.create<TestMemoryToolService>()

        runBlocking { service.answer(sessionId = "s1", question = "find it") }

        val history = memory.get("s1")
        // The tool loop in executeWithTools persists: USER prompt, ASSISTANT (with toolCalls),
        // TOOL result, and ASSISTANT (final response) — 4 messages
        assertThat(history).hasSize(4)
        assertThat(history[0].role).isEqualTo(MessageRole.USER)
        assertThat(history[1].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(history[1].toolCalls).hasSize(1)
        assertThat(history[2].role).isEqualTo(MessageRole.TOOL)
        assertThat(history[2].content).contains("resolved")
        assertThat(history[3].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(history[3].content).isEqualTo("final result")
    }

    @Test
    fun `multiple tool calls in one request produce multiple tool rounds all persisted`() {
        val memory = TestMemory()
        val provider = TestToolCallingProvider(
            ModelResponse(
                content = "",
                toolCalls = listOf(ToolCall("call-1", "lookup", """{"query":"first"}""")),
            ),
            ModelResponse(
                content = "",
                toolCalls = listOf(ToolCall("call-2", "lookup", """{"query":"second"}""")),
            ),
            ModelResponse(content = "done"),
        )
        val tool = object : ResolvedTool {
            override val name: String = "lookup"
            override val description: String = "Lookup tool"
            override val inputSchemaJson: String = """{"type":"object"}"""
            override val idempotent: Boolean = false
            override val sideEffectLevel: dev.tramai.core.model.SideEffectLevel =
                dev.tramai.core.model.SideEffectLevel.READ_ONLY

            override suspend fun execute(
                input: Any,
                context: ToolExecutionContext,
            ): ToolResult = ToolResult.Success("""{"value":"resolved"}""")
        }
        val engine = TramaiEngine(
            provider = provider,
            chatMemory = memory,
            toolRegistry = ToolRegistry(mapOf(tool.name to tool)),
        )
        val service = engine.create<TestMemoryToolService>()

        runBlocking { service.answer(sessionId = "s1", question = "search") }

        val history = memory.get("s1")
        // Tool loop persists: USER, ASSISTANT(tc=[c1]), TOOL(c1), ASSISTANT(tc=[c2]), TOOL(c2), ASSISTANT("done")
        assertThat(history).hasSize(6)
        assertThat(history[0].role).isEqualTo(MessageRole.USER)
        assertThat(history[1].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(history[1].toolCalls).hasSize(1)
        assertThat(history[2].role).isEqualTo(MessageRole.TOOL)
        assertThat(history[3].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(history[3].toolCalls).hasSize(1)
        assertThat(history[4].role).isEqualTo(MessageRole.TOOL)
        assertThat(history[5].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(history[5].content).isEqualTo("done")
    }

    @Test
    fun `tool call round trip persists user tool and assistant messages`() {
        val memory = TestMemory()
        val provider = TestToolCallingProvider(
            ModelResponse(
                content = "",
                toolCalls = listOf(ToolCall("call-1", "lookup", """{"query":"data"}""")),
            ),
            ModelResponse(content = "the answer is 42"),
        )
        val tool = object : ResolvedTool {
            override val name: String = "lookup"
            override val description: String = "Lookup tool"
            override val inputSchemaJson: String = """{"type":"object"}"""
            override val idempotent: Boolean = false
            override val sideEffectLevel: dev.tramai.core.model.SideEffectLevel =
                dev.tramai.core.model.SideEffectLevel.READ_ONLY

            override suspend fun execute(
                input: Any,
                context: ToolExecutionContext,
            ): ToolResult = ToolResult.Success("found-data")
        }
        val engine = TramaiEngine(
            provider = provider,
            chatMemory = memory,
            toolRegistry = ToolRegistry(mapOf(tool.name to tool)),
        )
        val service = engine.create<TestMemoryToolService>()

        val result = runBlocking { service.answer(sessionId = "s1", question = "what is the data") }

        assertThat(result).isEqualTo("the answer is 42")

        val history = memory.get("s1")
        // Tool loop persists 4 messages: USER, ASSISTANT(tc), TOOL, ASSISTANT(final)
        assertThat(history).hasSize(4)
        assertThat(history[0].role).isEqualTo(MessageRole.USER)
        assertThat(history[0].content).contains("what is the data")
        assertThat(history[1].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(history[1].toolCalls).hasSize(1)
        assertThat(history[2].role).isEqualTo(MessageRole.TOOL)
        assertThat(history[2].content).contains("found-data")
        assertThat(history[3].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(history[3].content).isEqualTo("the answer is 42")
    }
}

// ── Test Service Interfaces ──────────────────────────────────────

@AiService
@SystemPrompt("You are a helpful assistant.")
private interface TestMemoryChatService {
    @Operation(
        prompt = "Respond to the user's message",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun chat(
        @ConversationId sessionId: String,
        message: String,
    ): String
}

@AiService
@SystemPrompt("You are a helpful assistant.")
private interface TestMemoryToolService {
    @Operation(
        prompt = "Respond to the user's query using the lookup tool",
        model = "claude-sonnet-4-20250514",
        tools = ["lookup"],
    )
    suspend fun answer(
        @ConversationId sessionId: String,
        question: String,
    ): String
}

// ── Test Helpers ───────────────────────────────────────────

private class TestMemory : ChatMemory {
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

private class TestProvider(
    private val responder: suspend (ModelRequest) -> ModelResponse,
) : ModelProvider {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return responder(request)
    }
}

private class TestToolCallingProvider(
    vararg responses: ModelResponse,
) : ModelProvider {
    private val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return queue.removeFirstOrNull() ?: error("No more queued responses")
    }
}
