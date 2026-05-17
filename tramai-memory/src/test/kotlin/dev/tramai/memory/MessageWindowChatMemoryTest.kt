package dev.tramai.memory

import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class MessageWindowChatMemoryTest {

    // ── Basic CRUD ──────────────────────────────────────────────

    @Test
    fun `empty conversation returns empty list`() {
        val memory = MessageWindowChatMemory()
        assertThat(memory.get("conv-1")).isEmpty()
    }

    @Test
    fun `add and retrieve single message`() {
        val memory = MessageWindowChatMemory()
        val msg = Message(MessageRole.USER, "hello")
        memory.add("conv-1", msg)
        assertThat(memory.get("conv-1")).containsExactly(msg)
    }

    @Test
    fun `add and retrieve multiple messages`() {
        val memory = MessageWindowChatMemory()
        val msgs = listOf(
            Message(MessageRole.USER, "first"),
            Message(MessageRole.ASSISTANT, "second"),
        )
        memory.add("conv-1", msgs)
        assertThat(memory.get("conv-1")).containsExactlyElementsOf(msgs)
    }

    @Test
    fun `get returns a snapshot not a live view`() {
        val memory = MessageWindowChatMemory()
        memory.add("conv-1", Message(MessageRole.USER, "first"))
        val snapshot = memory.get("conv-1")
        memory.add("conv-1", Message(MessageRole.USER, "second"))
        assertThat(snapshot).hasSize(1)
    }

    @Test
    fun `clear removes conversation history`() {
        val memory = MessageWindowChatMemory()
        memory.add("conv-1", Message(MessageRole.USER, "hello"))
        memory.clear("conv-1")
        assertThat(memory.get("conv-1")).isEmpty()
    }

    @Test
    fun `rejects blank conversation id on get`() {
        val memory = MessageWindowChatMemory()
        assertThatThrownBy { memory.get("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects blank conversation id on add`() {
        val memory = MessageWindowChatMemory()
        assertThatThrownBy { memory.add("", Message(MessageRole.USER, "x")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects blank conversation id on clear`() {
        val memory = MessageWindowChatMemory()
        assertThatThrownBy { memory.clear("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── Message Eviction ─────────────────────────────────────────

    @Test
    fun `keeps at most maxMessages non-system messages per conversation`() {
        val memory = MessageWindowChatMemory(maxMessages = 3)
        memory.add("conv-1", Message(MessageRole.USER, "msg-1"))
        memory.add("conv-1", Message(MessageRole.USER, "msg-2"))
        memory.add("conv-1", Message(MessageRole.USER, "msg-3"))
        memory.add("conv-1", Message(MessageRole.USER, "msg-4"))

        val history = memory.get("conv-1")
        assertThat(history).hasSize(3)
        assertThat(history.map { it.content }).containsExactly("msg-2", "msg-3", "msg-4")
    }

    @Test
    fun `evicts oldest non-system messages first when over maxMessages`() {
        val memory = MessageWindowChatMemory(maxMessages = 2)
        memory.add("conv-1", Message(MessageRole.USER, "keep-me"))
        memory.add("conv-1", Message(MessageRole.USER, "also-keep"))
        memory.add("conv-1", Message(MessageRole.USER, "evicted"))

        val history = memory.get("conv-1")
        assertThat(history).hasSize(2)
        assertThat(history.map { it.content }).containsExactly("also-keep", "evicted")
    }

    @Test
    fun `counts only non-system messages against maxMessages`() {
        val memory = MessageWindowChatMemory(maxMessages = 2)
        memory.add("conv-1", Message(MessageRole.SYSTEM, "system-prompt"))
        memory.add("conv-1", Message(MessageRole.USER, "user-1"))
        memory.add("conv-1", Message(MessageRole.USER, "user-2"))
        memory.add("conv-1", Message(MessageRole.USER, "user-3"))

        val history = memory.get("conv-1")
        assertThat(history).hasSize(3) // system + 2 user
        assertThat(history.map { it.content }).containsExactly(
            "system-prompt", "user-2", "user-3",
        )
    }

    @Test
    fun `adding more than the window in one batch keeps only the newest messages`() {
        val memory = MessageWindowChatMemory(maxMessages = 2)
        memory.add(
            "conv-1",
            listOf(
                Message(MessageRole.USER, "user-1"),
                Message(MessageRole.USER, "user-2"),
                Message(MessageRole.USER, "user-3"),
            ),
        )

        assertThat(memory.get("conv-1").map { it.content }).containsExactly("user-2", "user-3")
    }

    // ── System Message Handling ─────────────────────────────────

    @Test
    fun `system messages are never evicted from a conversation`() {
        val memory = MessageWindowChatMemory(maxMessages = 2)
        memory.add("conv-1", Message(MessageRole.SYSTEM, "system"))
        memory.add("conv-1", Message(MessageRole.USER, "user-1"))
        memory.add("conv-1", Message(MessageRole.USER, "user-2"))
        memory.add("conv-1", Message(MessageRole.USER, "user-3"))

        val history = memory.get("conv-1")
        assertThat(history.map { it.content }).contains("system")
    }

    @Test
    fun `system message dedup replaces old system with new one`() {
        val memory = MessageWindowChatMemory(maxMessages = 10)
        memory.add("conv-1", Message(MessageRole.SYSTEM, "old-system"))
        memory.add("conv-1", Message(MessageRole.USER, "user-1"))
        memory.add("conv-1", Message(MessageRole.SYSTEM, "new-system"))

        val history = memory.get("conv-1")
        // Only one system message, which is the newer one
        assertThat(history.filter { it.role == MessageRole.SYSTEM }).hasSize(1)
        assertThat(history.first { it.role == MessageRole.SYSTEM }.content).isEqualTo("new-system")
    }

    @Test
    fun `system message dedup preserves user messages`() {
        val memory = MessageWindowChatMemory(maxMessages = 10)
        memory.add("conv-1", Message(MessageRole.SYSTEM, "system"))
        memory.add("conv-1", Message(MessageRole.USER, "user-1"))
        memory.add("conv-1", Message(MessageRole.SYSTEM, "updated-system"))

        val history = memory.get("conv-1")
        assertThat(history).hasSize(2)
        // User message is preserved, system is dedup'd to the latest
        assertThat(history.first { it.role == MessageRole.USER }.content).isEqualTo("user-1")
        assertThat(history.first { it.role == MessageRole.SYSTEM }.content).isEqualTo("updated-system")
    }

    // ── Conversation Eviction ────────────────────────────────────

    @Test
    fun `evicts least recently used conversation when maxConversations exceeded`() {
        val memory = MessageWindowChatMemory(maxMessages = 5, maxConversations = 2)
        memory.add("conv-a", Message(MessageRole.USER, "hello from A"))
        memory.add("conv-b", Message(MessageRole.USER, "hello from B"))
        // Write to conv-a to make it most recently used (LRU based on writes only)
        memory.add("conv-a", Message(MessageRole.USER, "another message from A"))
        // LRU order: [conv-b, conv-a]
        // Adding conv-c should evict conv-b (oldest written)
        memory.add("conv-c", Message(MessageRole.USER, "hello from C"))

        assertThat(memory.get("conv-a")).isNotEmpty
        assertThat(memory.get("conv-b")).isEmpty()
        assertThat(memory.get("conv-c")).isNotEmpty
    }

    @Test
    fun `evicts entire least recently used conversation`() {
        val memory = MessageWindowChatMemory(maxMessages = 5, maxConversations = 1)
        memory.add("conv-a", Message(MessageRole.USER, "only message"))
        memory.add("conv-b", Message(MessageRole.USER, "replaces A"))

        assertThat(memory.get("conv-a")).isEmpty()
        assertThat(memory.get("conv-b")).hasSize(1)
    }

    @Test
    fun `write refreshes LRU order for a conversation preventing eviction`() {
        val memory = MessageWindowChatMemory(maxMessages = 5, maxConversations = 2)
        memory.add("conv-a", Message(MessageRole.USER, "A1"))
        memory.add("conv-b", Message(MessageRole.USER, "B1"))
        // Write to conv-a to make it most recently used
        memory.add("conv-a", Message(MessageRole.USER, "A2"))
        // Adding conv-c should evict conv-b (oldest written, not the recently re-written conv-a)
        memory.add("conv-c", Message(MessageRole.USER, "C1"))

        assertThat(memory.get("conv-a")).isNotEmpty
        assertThat(memory.get("conv-b")).isEmpty()
        assertThat(memory.get("conv-c")).isNotEmpty
    }

    // ── Thread Safety ────────────────────────────────────────────

    @Test
    fun `supports concurrent adds to same conversation`() = runBlocking {
        val memory = MessageWindowChatMemory(maxMessages = 100)
        val numMessages = 50
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
        val memory = MessageWindowChatMemory(maxMessages = 10)
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
        val memory = MessageWindowChatMemory(maxMessages = 100)
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
        assertThat(history.size).isGreaterThanOrEqualTo(1) // system + at least some users
    }

    @Test
    fun `concurrent eviction does not orphan entries`() = runBlocking {
        val memory = MessageWindowChatMemory(maxMessages = 5, maxConversations = 5)
        val numConversations = 50

        coroutineScope {
            val jobs = (1..numConversations).map { i ->
                async {
                    memory.add("conv-${i % 10}", Message(MessageRole.USER, "msg-$i"))
                }
            }
            jobs.awaitAll()
        }

        // Total tracked conversations should never exceed maxConversations
        val nonEmptyConversations = (0..9).count { memory.get("conv-$it").isNotEmpty() }
        assertThat(nonEmptyConversations).isLessThanOrEqualTo(5)
    }

    @Test
    fun `concurrent dedup does not duplicate system messages`() = runBlocking {
        val memory = MessageWindowChatMemory(maxMessages = 50)

        coroutineScope {
            val jobs = (1..50).map { i ->
                async {
                    memory.add("dedup-test", Message(MessageRole.SYSTEM, "system-v$i"))
                }
            }
            jobs.awaitAll()
        }

        val history = memory.get("dedup-test")
        val systemMessages = history.filter { it.role == MessageRole.SYSTEM }
        // At most one system message should remain after all dedup completes
        assertThat(systemMessages).hasSize(1)
    }
}
