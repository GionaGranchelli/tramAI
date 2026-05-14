package dev.tramai.memory

import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.Tokenizer
import dev.tramai.core.memory.roughTokenizer
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class TokenAwareChatMemoryTest {

    // ── Basic CRUD ──────────────────────────────────────────────

    @Test
    fun `empty conversation returns empty list`() {
        val memory = TokenAwareChatMemory(maxTokens = 100)
        assertThat(memory.get("conv-1")).isEmpty()
    }

    @Test
    fun `messages with total tokens under limit are fully returned`() {
        val memory = TokenAwareChatMemory(maxTokens = 100)
        val msgs = listOf(
            Message(MessageRole.USER, "hello"),
            Message(MessageRole.ASSISTANT, "world"),
        )
        memory.add("conv-1", msgs)
        assertThat(memory.get("conv-1")).containsExactlyElementsOf(msgs)
    }

    @Test
    fun `adding messages that exceed maxTokens evicts oldest non-system messages`() {
        // Each char ~1/3 token, so "x".repeat(90) ~= 30 tokens, 3 of those = 90 tokens > 50 limit
        val memory = TokenAwareChatMemory(maxTokens = 50)
        memory.add("conv-1", Message(MessageRole.USER, "x".repeat(90)))  // ~30 tokens
        memory.add("conv-1", Message(MessageRole.USER, "y".repeat(90)))  // ~30 tokens
        memory.add("conv-1", Message(MessageRole.USER, "z".repeat(90)))  // ~30 tokens

        val history = memory.get("conv-1")
        // Should have at most 1-2 messages (total tokens <= 50)
        val totalTokens = history.sumOf { roughTokenizer().countTokens(it.content) }
        assertThat(totalTokens).isLessThanOrEqualTo(50)
    }

    @Test
    fun `system messages are never evicted even when over token limit`() {
        val memory = TokenAwareChatMemory(maxTokens = 10)
        memory.add("conv-1", Message(MessageRole.SYSTEM, "system-prompt-123"))  // ~6 tokens
        memory.add("conv-1", Message(MessageRole.USER, "x".repeat(60)))  // ~20 tokens

        val history = memory.get("conv-1")
        assertThat(history).isNotEmpty
        assertThat(history.any { it.role == MessageRole.SYSTEM }).isTrue
        assertThat(history.first { it.role == MessageRole.SYSTEM }.content).isEqualTo("system-prompt-123")
    }

    @Test
    fun `system message dedup replaces old system with new one`() {
        val memory = TokenAwareChatMemory(maxTokens = 100)
        memory.add("conv-1", Message(MessageRole.SYSTEM, "old-system"))
        memory.add("conv-1", Message(MessageRole.USER, "user-1"))
        memory.add("conv-1", Message(MessageRole.SYSTEM, "new-system"))

        val history = memory.get("conv-1")
        assertThat(history.filter { it.role == MessageRole.SYSTEM }).hasSize(1)
        assertThat(history.first { it.role == MessageRole.SYSTEM }.content).isEqualTo("new-system")
    }

    @Test
    fun `adding many small messages stays within token budget`() {
        val memory = TokenAwareChatMemory(maxTokens = 30)
        val messages = (1..20).map { Message(MessageRole.USER, "ab") }  // ~1 token each
        memory.add("conv-1", messages)

        val history = memory.get("conv-1")
        // Each msg is ~1 token, so up to ~30 messages (coerceAtLeast(1) means min 1 token)
        assertThat(history.size).isLessThanOrEqualTo(30)
        val totalTokens = history.sumOf { roughTokenizer().countTokens(it.content) }
        assertThat(totalTokens).isLessThanOrEqualTo(30)
    }

    @Test
    fun `a single over-budget message exceeds budget and is evicted`() {
        val memory = TokenAwareChatMemory(maxTokens = 10)
        val bigMsg = Message(MessageRole.USER, "x".repeat(90))  // ~30 tokens, way over 10
        memory.add("conv-1", bigMsg)

        // The implementation evicts all non-system messages when total exceeds budget,
        // even if that means leaving the conversation empty
        val history = memory.get("conv-1")
        assertThat(history).isEmpty()
    }

    @Test
    fun `adding after eviction respects the limit`() {
        val memory = TokenAwareChatMemory(maxTokens = 20)
        // Add enough to fill the budget
        memory.add("conv-1", Message(MessageRole.USER, "x".repeat(60)))  // ~20 tokens
        // Add another message, triggering eviction
        memory.add("conv-1", Message(MessageRole.USER, "y".repeat(60)))  // ~20 tokens

        val history = memory.get("conv-1")
        val totalTokens = history.sumOf { roughTokenizer().countTokens(it.content) }
        assertThat(totalTokens).isLessThanOrEqualTo(20)
    }

    @Test
    fun `creating with custom tokenizer`() {
        // Custom tokenizer: 1 token per 4 characters
        val customTokenizer = Tokenizer { chars -> chars.length / 4 }
        val memory = TokenAwareChatMemory(maxTokens = 10, tokenizer = customTokenizer)

        // "hello world" is 11 chars -> 2 tokens with custom tokenizer
        memory.add("conv-1", Message(MessageRole.USER, "hello world"))
        memory.add("conv-1", Message(MessageRole.USER, "a".repeat(40)))  // 10 tokens, hits limit

        val history = memory.get("conv-1")
        // Should have evicted oldest (hello world) to stay under 10 tokens
        val totalTokens = history.sumOf { customTokenizer.countTokens(it.content) }
        assertThat(totalTokens).isLessThanOrEqualTo(10)
    }

    // ── Constructor Validation ──────────────────────────────────

    @Test
    fun `passing zero maxTokens throws IllegalArgumentException`() {
        assertThatThrownBy { TokenAwareChatMemory(maxTokens = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maxTokens must be positive")
    }

    @Test
    fun `passing negative maxTokens throws IllegalArgumentException`() {
        assertThatThrownBy { TokenAwareChatMemory(maxTokens = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maxTokens must be positive")
    }

    @Test
    fun `passing zero maxConversations throws IllegalArgumentException`() {
        assertThatThrownBy { TokenAwareChatMemory(maxTokens = 100, maxConversations = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maxConversations must be positive")
    }

    // ── Blank ConversationId ────────────────────────────────────

    @Test
    fun `blank conversationId throws IllegalArgumentException on get`() {
        val memory = TokenAwareChatMemory(maxTokens = 100)
        assertThatThrownBy { memory.get("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank conversationId throws IllegalArgumentException on add`() {
        val memory = TokenAwareChatMemory(maxTokens = 100)
        assertThatThrownBy { memory.add("", Message(MessageRole.USER, "x")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank conversationId throws IllegalArgumentException on clear`() {
        val memory = TokenAwareChatMemory(maxTokens = 100)
        assertThatThrownBy { memory.clear("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── Snapshot & Clear ────────────────────────────────────────

    @Test
    fun `get returns a snapshot not a live view`() {
        val memory = TokenAwareChatMemory(maxTokens = 100)
        memory.add("conv-1", Message(MessageRole.USER, "first"))
        val snapshot = memory.get("conv-1")
        memory.add("conv-1", Message(MessageRole.USER, "second"))
        assertThat(snapshot).hasSize(1)
    }

    @Test
    fun `clear removes conversation`() {
        val memory = TokenAwareChatMemory(maxTokens = 100)
        memory.add("conv-1", Message(MessageRole.USER, "hello"))
        memory.clear("conv-1")
        assertThat(memory.get("conv-1")).isEmpty()
    }

    // ── Token Budget Recheck ────────────────────────────────────

    @Test
    fun `token budget is checked on every add call not just initially`() {
        val memory = TokenAwareChatMemory(maxTokens = 15)
        // Add 3 messages, each ~5 tokens
        memory.add("conv-1", Message(MessageRole.USER, "a".repeat(15)))  // 5 tokens
        memory.add("conv-1", Message(MessageRole.USER, "b".repeat(15)))  // 5 tokens
        memory.add("conv-1", Message(MessageRole.USER, "c".repeat(15)))  // 5 tokens
        // Each add() should evict oldest when total > 15
        val history = memory.get("conv-1")
        val totalTokens = history.sumOf { roughTokenizer().countTokens(it.content) }
        assertThat(totalTokens).isLessThanOrEqualTo(15)
    }

    @Test
    fun `eviction respects token count per message with varying message lengths`() {
        val memory = TokenAwareChatMemory(maxTokens = 20)
        memory.add("conv-1", Message(MessageRole.USER, "a".repeat(3)))   // 1 token
        memory.add("conv-1", Message(MessageRole.USER, "b".repeat(60)))  // ~20 tokens (fills budget alone)

        val history = memory.get("conv-1")
        // After 2nd message, total = 1 + 20 = 21 > 20, so oldest (a) evicted
        assertThat(history).hasSize(1)
        assertThat(history.first().content).isEqualTo("b".repeat(60))
    }

    @Test
    fun `mixed system and user messages with system preserved and users evicted to stay under limit`() {
        val memory = TokenAwareChatMemory(maxTokens = 20)
        memory.add("conv-1", Message(MessageRole.SYSTEM, "system-prompt"))
        memory.add("conv-1", Message(MessageRole.USER, "user-one".repeat(10)))   // ~30 tokens
        memory.add("conv-1", Message(MessageRole.USER, "user-two".repeat(10)))   // ~30 tokens

        val history = memory.get("conv-1")
        // System is always preserved
        assertThat(history.any { it.role == MessageRole.SYSTEM }).isTrue
        // Total tokens of non-system messages <= 20
        val nonSystemTokens = history
            .filter { it.role != MessageRole.SYSTEM }
            .sumOf { roughTokenizer().countTokens(it.content) }
        assertThat(nonSystemTokens).isLessThanOrEqualTo(20)
    }

    // ── Thread Safety ──────────────────────────────────────────

    @Test
    fun `thread safety concurrent adds to same conversation`() = runBlocking {
        val memory = TokenAwareChatMemory(maxTokens = 200)
        val numMessages = 30
        coroutineScope {
            val jobs = (1..numMessages).map { i ->
                async {
                    memory.add("shared", Message(MessageRole.USER, "msg-$i"))
                }
            }
            jobs.awaitAll()
        }
        val history = memory.get("shared")
        assertThat(history).isNotEmpty
        // All messages might not fit within token budget, but should not crash
        val totalTokens = history.sumOf { roughTokenizer().countTokens(it.content) }
        assertThat(totalTokens).isLessThanOrEqualTo(200)
    }

    @Test
    fun `thread safety concurrent read and write on same conversation`() = runBlocking {
        val memory = TokenAwareChatMemory(maxTokens = 200)
        memory.add("shared", Message(MessageRole.SYSTEM, "system"))

        coroutineScope {
            val reader = async {
                repeat(20) {
                    memory.get("shared")
                }
            }
            val writer = async {
                repeat(20) {
                    memory.add("shared", Message(MessageRole.USER, "user-$it"))
                }
            }
            awaitAll(reader, writer)
        }
        // Should not throw. After all writers done, should have system + some users.
        val history = memory.get("shared")
        assertThat(history.size).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `system message dedup preserves user messages`() {
        val memory = TokenAwareChatMemory(maxTokens = 100)
        memory.add("conv-1", Message(MessageRole.SYSTEM, "system"))
        memory.add("conv-1", Message(MessageRole.USER, "user-1"))
        memory.add("conv-1", Message(MessageRole.SYSTEM, "updated-system"))

        val history = memory.get("conv-1")
        assertThat(history).hasSize(2)
        assertThat(history.first { it.role == MessageRole.USER }.content).isEqualTo("user-1")
        assertThat(history.first { it.role == MessageRole.SYSTEM }.content).isEqualTo("updated-system")
    }

    // ── Conversation Eviction ────────────────────────────────────

    @Test
    fun `evicts LRU conversation when maxConversations exceeded`() {
        val memory = TokenAwareChatMemory(maxTokens = 100, maxConversations = 2)
        memory.add("conv-a", Message(MessageRole.USER, "hello from A"))
        memory.add("conv-b", Message(MessageRole.USER, "hello from B"))
        memory.add("conv-a", Message(MessageRole.USER, "another from A"))
        // LRU order: [conv-b, conv-a]; adding conv-c evicts conv-b
        memory.add("conv-c", Message(MessageRole.USER, "hello from C"))

        assertThat(memory.get("conv-a")).isNotEmpty
        assertThat(memory.get("conv-b")).isEmpty()
        assertThat(memory.get("conv-c")).isNotEmpty
    }

    @Test
    fun `evicts entire LRU conversation leaving others intact`() {
        val memory = TokenAwareChatMemory(maxTokens = 100, maxConversations = 1)
        memory.add("conv-a", Message(MessageRole.USER, "only message"))
        memory.add("conv-b", Message(MessageRole.USER, "replaces A"))

        assertThat(memory.get("conv-a")).isEmpty()
        assertThat(memory.get("conv-b")).hasSize(1)
    }

    @Test
    fun `write refreshes LRU order preventing eviction`() {
        val memory = TokenAwareChatMemory(maxTokens = 100, maxConversations = 2)
        memory.add("conv-a", Message(MessageRole.USER, "A1"))
        memory.add("conv-b", Message(MessageRole.USER, "B1"))
        memory.add("conv-a", Message(MessageRole.USER, "A2"))
        // conv-a is now most recently used; adding conv-c evicts conv-b
        memory.add("conv-c", Message(MessageRole.USER, "C1"))

        assertThat(memory.get("conv-a")).isNotEmpty
        assertThat(memory.get("conv-b")).isEmpty()
        assertThat(memory.get("conv-c")).isNotEmpty
    }

    @Test
    fun `concurrent eviction with maxConversations does not orphan entries`() = runBlocking {
        val memory = TokenAwareChatMemory(maxTokens = 100, maxConversations = 5)
        val numOps = 50

        coroutineScope {
            val jobs = (1..numOps).map { i ->
                async {
                    memory.add("conv-${i % 10}", Message(MessageRole.USER, "msg-$i"))
                }
            }
            jobs.awaitAll()
        }

        val nonEmptyConversations = (0..9).count { memory.get("conv-$it").isNotEmpty() }
        assertThat(nonEmptyConversations).isLessThanOrEqualTo(5)
    }
}
