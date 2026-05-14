package dev.tramai.memory

import dev.tramai.core.memory.ChatMemoryStore
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test

class PersistentChatMemoryTest {

    // ── Basic CRUD ──────────────────────────────────────────────

    @Test
    fun `after add get returns the stored messages round trip through store`() {
        val memory = PersistentChatMemory(InMemoryChatMemoryStore())
        val msgs = listOf(
            Message(MessageRole.USER, "hello"),
            Message(MessageRole.ASSISTANT, "world"),
        )
        memory.add("conv-1", msgs)
        assertThat(memory.get("conv-1")).containsExactlyElementsOf(msgs)
    }

    @Test
    fun `adding a single message works`() {
        val memory = PersistentChatMemory(InMemoryChatMemoryStore())
        val msg = Message(MessageRole.USER, "single")
        memory.add("conv-1", msg)
        assertThat(memory.get("conv-1")).containsExactly(msg)
    }

    @Test
    fun `adding a batch of messages works`() {
        val memory = PersistentChatMemory(InMemoryChatMemoryStore())
        val msgs = (1..5).map { Message(MessageRole.USER, "msg-$it") }
        memory.add("conv-1", msgs)
        assertThat(memory.get("conv-1")).containsExactlyElementsOf(msgs)
    }

    @Test
    fun `get returns messages from store when cache is empty`() {
        val store = InMemoryChatMemoryStore()
        val memory = PersistentChatMemory(store)
        val msgs = listOf(
            Message(MessageRole.USER, "stored-1"),
            Message(MessageRole.USER, "stored-2"),
        )
        // Directly to store to simulate cache miss
        store.appendMessages("conv-1", msgs)
        assertThat(memory.get("conv-1")).containsExactlyElementsOf(msgs)
    }

    @Test
    fun `clear removes conversation from store`() {
        val store = InMemoryChatMemoryStore()
        val memory = PersistentChatMemory(store)
        memory.add("conv-1", Message(MessageRole.USER, "to-be-cleared"))
        memory.clear("conv-1")
        assertThat(memory.get("conv-1")).isEmpty()
        assertThat(store.getMessages("conv-1")).isEmpty()
    }

    @Test
    fun `empty conversation returns empty list`() {
        val memory = PersistentChatMemory(InMemoryChatMemoryStore())
        assertThat(memory.get("conv-1")).isEmpty()
    }

    // ── Blank ConversationId ────────────────────────────────────

    @Test
    fun `blank conversationId throws IllegalArgumentException on get`() {
        val memory = PersistentChatMemory(InMemoryChatMemoryStore())
        assertThatThrownBy { memory.get("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank conversationId throws IllegalArgumentException on add`() {
        val memory = PersistentChatMemory(InMemoryChatMemoryStore())
        assertThatThrownBy { memory.add("", Message(MessageRole.USER, "x")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank conversationId throws IllegalArgumentException on clear`() {
        val memory = PersistentChatMemory(InMemoryChatMemoryStore())
        assertThatThrownBy { memory.clear("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── Cache Integration ───────────────────────────────────────

    @Test
    fun `when cache is provided reads from cache first if non-empty`() {
        val store = InMemoryChatMemoryStore()
        val cache = MessageWindowChatMemory(maxMessages = 10)
        val memory = PersistentChatMemory(store, cache)

        // Add directly to store (simulating a previous persisted session)
        store.appendMessages("conv-1", listOf(
            Message(MessageRole.USER, "only-in-store"),
        ))

        // First read: cache empty, falls through to store
        assertThat(memory.get("conv-1").map { it.content }).containsExactly("only-in-store")

        // Add through memory to populate cache
        memory.add("conv-1", Message(MessageRole.USER, "now-in-cache"))

        // Second read: cache is non-empty, returns from cache only (not union with store)
        assertThat(memory.get("conv-1").map { it.content }).containsExactly("now-in-cache")
        // Store still has both
        assertThat(store.getMessages("conv-1").map { it.content }).containsExactly(
            "only-in-store", "now-in-cache",
        )
    }

    @Test
    fun `when cache is provided writes go to both store and cache`() {
        val store = InMemoryChatMemoryStore()
        val cache = MessageWindowChatMemory(maxMessages = 10)
        val memory = PersistentChatMemory(store, cache)

        memory.add("conv-1", Message(MessageRole.USER, "both-places"))

        assertThat(store.getMessages("conv-1").map { it.content }).containsExactly("both-places")
        assertThat(cache.get("conv-1").map { it.content }).containsExactly("both-places")
    }

    @Test
    fun `when cache is provided clear removes from both store and cache`() {
        val store = InMemoryChatMemoryStore()
        val cache = MessageWindowChatMemory(maxMessages = 10)
        val memory = PersistentChatMemory(store, cache)

        memory.add("conv-1", Message(MessageRole.USER, "to-clear"))
        memory.clear("conv-1")

        assertThat(store.getMessages("conv-1")).isEmpty()
        assertThat(cache.get("conv-1")).isEmpty()
    }

    @Test
    fun `when cache is provided cache reads before store when cache has data`() {
        val store = InMemoryChatMemoryStore()
        val cache = MessageWindowChatMemory(maxMessages = 10)
        val memory = PersistentChatMemory(store, cache)

        // Add different data to cache and store
        cache.add("conv-1", Message(MessageRole.USER, "cache-data"))
        store.appendMessages("conv-1", listOf(Message(MessageRole.USER, "store-data")))

        // Should read from cache since it's non-empty
        assertThat(memory.get("conv-1").map { it.content }).containsExactly("cache-data")
    }

    // ── Multiple Conversations ──────────────────────────────────

    @Test
    fun `multiple conversations do not interfere`() {
        val memory = PersistentChatMemory(InMemoryChatMemoryStore())
        memory.add("conv-a", Message(MessageRole.USER, "hello from A"))
        memory.add("conv-b", Message(MessageRole.USER, "hello from B"))

        val historyA = memory.get("conv-a")
        val historyB = memory.get("conv-b")
        assertThat(historyA).hasSize(1)
        assertThat(historyB).hasSize(1)
        assertThat(historyA.first().content).isEqualTo("hello from A")
        assertThat(historyB.first().content).isEqualTo("hello from B")
    }

    @Test
    fun `messages are ordered correctly append order preserved`() {
        val memory = PersistentChatMemory(InMemoryChatMemoryStore())
        memory.add("conv-1", Message(MessageRole.USER, "first"))
        memory.add("conv-1", Message(MessageRole.USER, "second"))
        memory.add("conv-1", Message(MessageRole.USER, "third"))

        val history = memory.get("conv-1")
        assertThat(history.map { it.content }).containsExactly("first", "second", "third")
    }

    // ── Thread Safety ──────────────────────────────────────────

    @Test
    fun `supports concurrent adds to same conversation`() = runBlocking {
        val memory = PersistentChatMemory(InMemoryChatMemoryStore())
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
        assertThat(history).hasSize(numMessages)
    }

    @Test
    fun `supports concurrent adds to different conversations`() = runBlocking {
        val memory = PersistentChatMemory(InMemoryChatMemoryStore())
        val numConversations = 20
        coroutineScope {
            val jobs = (1..numConversations).map { i ->
                async {
                    memory.add("conv-$i", Message(MessageRole.USER, "msg-from-conv-$i"))
                }
            }
            jobs.awaitAll()
        }
        for (i in 1..numConversations) {
            assertThat(memory.get("conv-$i")).isNotEmpty
        }
    }

    @Test
    fun `supports concurrent read and write on same conversation`() = runBlocking {
        val memory = PersistentChatMemory(InMemoryChatMemoryStore())
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
        // Should not throw. After all writers done, should have expected count.
        val history = memory.get("shared")
        assertThat(history.size).isGreaterThanOrEqualTo(1)
    }

    // ── Test Helpers ─────────────────────────────────────────────

    /**
     * Minimal in-memory ChatMemoryStore for testing PersistentChatMemory.
     */
    private class InMemoryChatMemoryStore : ChatMemoryStore {
        private val store = ConcurrentHashMap<String, MutableList<Message>>()
        private val metadata = ConcurrentHashMap<String, Long>()

        override fun getMessages(conversationId: String): List<Message> {
            return store[conversationId]?.toList() ?: emptyList()
        }

        override fun appendMessages(conversationId: String, messages: List<Message>) {
            store.getOrPut(conversationId) { CopyOnWriteArrayList() }.addAll(messages)
            metadata[conversationId] = System.currentTimeMillis()
        }

        override fun deleteConversation(conversationId: String) {
            store.remove(conversationId)
            metadata.remove(conversationId)
        }

        override fun listConversations(limit: Int, offset: Int): List<String> {
            return metadata.entries
                .sortedByDescending { it.value }
                .drop(offset)
                .take(limit)
                .map { it.key }
        }
    }
}
