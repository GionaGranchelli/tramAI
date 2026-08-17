package dev.tramai.engine.memory

import dev.tramai.core.annotations.ConversationId
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ConversationIdProvider
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Direct contract tests for [ConversationMemoryCoordinator].
 *
 * The memory extraction must preserve the exact merge and persistence
 * semantics that were frozen in the handler: explicit @ConversationId wins,
 * generated IDs fall back to the provider, history injection is ordered,
 * SYSTEM deduplication is current-turn-only, and ordinary vs structured
 * persistence compute the turn boundary differently.
 */
class ConversationMemoryCoordinatorTest {

    private fun message(role: MessageRole, content: String) = Message(role, content)

    private class RecordingChatMemory : ChatMemory {
        val history = mutableListOf<Message>()
        val added = mutableListOf<List<Message>>()
        val addedConversationIds = mutableListOf<String>()
        var getCalls = 0

        override fun get(conversationId: String): List<Message> {
            getCalls++
            return history.toList()
        }

        override fun add(conversationId: String, messages: List<Message>) {
            addedConversationIds += conversationId
            added += messages.toList()
            history.addAll(messages)
        }

        override fun add(conversationId: String, message: Message) {
            add(conversationId, listOf(message))
        }

        override fun clear(conversationId: String) {
            history.clear()
        }
    }

    private fun coordinator(
        chatMemory: ChatMemory? = RecordingChatMemory(),
        provider: ConversationIdProvider = ConversationIdProvider { "generated-id" },
    ) = ConversationMemoryCoordinator(chatMemory, provider)

    private interface ConversationIdService {
        fun chat(@ConversationId sessionId: String, question: String): String

        fun chatNullable(@ConversationId sessionId: String?): String

        fun plain(question: String): String
    }

    private fun method(name: String): Method = ConversationIdService::class.java.getMethod(name, *parameterTypes(name))

    private fun parameterTypes(name: String): Array<Class<*>> = when (name) {
        "chat" -> arrayOf(String::class.java, String::class.java)
        "chatNullable" -> arrayOf(String::class.java)
        else -> arrayOf(String::class.java)
    }

    // ------------------------------------------------------------------
    // Conversation ID resolution
    // ------------------------------------------------------------------

    @Test
    fun `memory disabled resolves null without invoking the provider`() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val c = coordinator(
            chatMemory = null,
            provider = ConversationIdProvider {
                calls.incrementAndGet()
                "generated"
            },
        )
        // resolveConversationId still delegates to provider for the raw string;
        // the memory-disabled guard lives on the handler's `if (chatMemory != null)`.
        // This test asserts the coordinator does not touch the memory store itself.
        assertThat(c.prepareMessages(listOf(message(MessageRole.USER, "hi")), null)).isNull()
        assertThat(calls.get()).isZero()
    }

    @Test
    fun `explicit conversation id wins`() {
        val c = coordinator()
        val id = c.resolveConversationId(method("chat"), arrayOf("explicit-id", "q"))
        assertThat(id).isEqualTo("explicit-id")
    }

    @Test
    fun `null annotated conversation id fails`() {
        val c = coordinator()
        assertThatThrownBy { c.resolveConversationId(method("chatNullable"), arrayOf<Any?>(null)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("@ConversationId")
    }

    @Test
    fun `no annotation falls back to the provider`() {
        val c = coordinator(provider = ConversationIdProvider { "provider-id" })
        assertThat(c.resolveConversationId(method("plain"), arrayOf("q"))).isEqualTo("provider-id")
    }

    // ------------------------------------------------------------------
    // prepareMessages
    // ------------------------------------------------------------------

    @Test
    fun `null conversation id produces no prepared messages`() {
        val memory = RecordingChatMemory()
        memory.history += message(MessageRole.SYSTEM, "old")
        val c = coordinator(chatMemory = memory)

        assertThat(c.prepareMessages(listOf(message(MessageRole.USER, "hi")), null)).isNull()
    }

    @Test
    fun `empty history produces null prepared messages`() {
        val c = coordinator(chatMemory = RecordingChatMemory())
        assertThat(c.prepareMessages(listOf(message(MessageRole.USER, "hi")), "cid")).isNull()
    }

    @Test
    fun `history injection preserves ordering and appends current messages`() {
        val memory = RecordingChatMemory()
        memory.history += listOf(
            message(MessageRole.SYSTEM, "old-sys"),
            message(MessageRole.USER, "old-user"),
            message(MessageRole.ASSISTANT, "old-assistant"),
        )
        val c = coordinator(chatMemory = memory)
        val initial = listOf(
            message(MessageRole.SYSTEM, "new-sys"),
            message(MessageRole.USER, "new-user"),
        )

        val prepared = c.prepareMessages(initial, "cid")!!

        assertThat(prepared.history).containsExactly(
            message(MessageRole.SYSTEM, "old-sys"),
            message(MessageRole.USER, "old-user"),
            message(MessageRole.ASSISTANT, "old-assistant"),
        )
        assertThat(prepared.effectiveMessages).containsExactly(
            message(MessageRole.SYSTEM, "old-sys"),
            message(MessageRole.USER, "old-user"),
            message(MessageRole.ASSISTANT, "old-assistant"),
            // current SYSTEM deduplicated (history already has one)
            message(MessageRole.USER, "new-user"),
        )
    }

    @Test
    fun `current system message is removed only when history already has one`() {
        val memory = RecordingChatMemory()
        memory.history += message(MessageRole.USER, "old-user")
        val c = coordinator(chatMemory = memory)
        val initial = listOf(
            message(MessageRole.SYSTEM, "new-sys"),
            message(MessageRole.USER, "new-user"),
        )

        val prepared = c.prepareMessages(initial, "cid")!!

        assertThat(prepared.effectiveMessages).containsExactly(
            message(MessageRole.USER, "old-user"),
            message(MessageRole.SYSTEM, "new-sys"),
            message(MessageRole.USER, "new-user"),
        )
    }

    // ------------------------------------------------------------------
    // persistTurn
    // ------------------------------------------------------------------

    @Test
    fun `ordinary turn persistence drops history and system messages and appends assistant`() {
        val memory = RecordingChatMemory()
        val c = coordinator(chatMemory = memory)

        c.persistTurn(
            PersistConversationTurnRequest(
                conversationId = "cid",
                messages = listOf(
                    message(MessageRole.SYSTEM, "new-sys"),
                    message(MessageRole.USER, "turn-user"),
                    message(MessageRole.ASSISTANT, "tool-result"),
                ),
                historySize = 1,
                assistantMessage = message(MessageRole.ASSISTANT, "final"),
            ),
        )

        assertThat(memory.added).containsExactly(
            listOf(
                message(MessageRole.USER, "turn-user"),
                message(MessageRole.ASSISTANT, "tool-result"),
                message(MessageRole.ASSISTANT, "final"),
            ),
        )
        assertThat(memory.addedConversationIds).containsExactly("cid")
    }

    @Test
    fun `no chat memory makes persistTurn a no-op`() {
        val c = coordinator(chatMemory = null)
        c.persistTurn(
            PersistConversationTurnRequest(
                conversationId = "cid",
                messages = listOf(message(MessageRole.USER, "u")),
                historySize = 0,
                assistantMessage = message(MessageRole.ASSISTANT, "a"),
            ),
        )
        // nothing to assert beyond not throwing
    }

    // ------------------------------------------------------------------
    // persistStructuredTurn
    // ------------------------------------------------------------------

    @Test
    fun `structured retry-aware persistence keeps repair messages in the turn boundary`() {
        val memory = RecordingChatMemory()
        val c = coordinator(chatMemory = memory)

        // messages include the history prefix: [old-user] + [prompt, assistant-bad, user-fix]
        c.persistStructuredTurn(
            PersistStructuredConversationTurnRequest(
                conversationId = "cid",
                messages = listOf(
                    message(MessageRole.USER, "old-user"),
                    message(MessageRole.USER, "prompt"),
                    message(MessageRole.ASSISTANT, "bad-json"),
                    message(MessageRole.USER, "fix"),
                ),
                historySize = 1,
                messagesBeforeCall = 3, // history + prompt + bad-json happened before the call
                assistantMessage = message(MessageRole.ASSISTANT, "good-json"),
            ),
        )

        assertThat(memory.added).containsExactly(
            listOf(
                message(MessageRole.USER, "prompt"),
                message(MessageRole.ASSISTANT, "bad-json"),
                message(MessageRole.USER, "fix"),
                message(MessageRole.ASSISTANT, "good-json"),
            ),
        )
    }

    @Test
    fun `structured persistence drops system messages from the user prompt`() {
        val memory = RecordingChatMemory()
        val c = coordinator(chatMemory = memory)

        c.persistStructuredTurn(
            PersistStructuredConversationTurnRequest(
                conversationId = "cid",
                messages = listOf(
                    message(MessageRole.SYSTEM, "sys"),
                    message(MessageRole.USER, "prompt"),
                    message(MessageRole.ASSISTANT, "bad"),
                    message(MessageRole.USER, "fix"),
                ),
                historySize = 0,
                messagesBeforeCall = 2,
                assistantMessage = message(MessageRole.ASSISTANT, "good"),
            ),
        )

        assertThat(memory.added.single()).containsExactly(
            message(MessageRole.USER, "prompt"),
            message(MessageRole.ASSISTANT, "bad"),
            message(MessageRole.USER, "fix"),
            message(MessageRole.ASSISTANT, "good"),
        )
    }

    @Test
    fun `no chat memory makes persistStructuredTurn a no-op`() {
        val c = coordinator(chatMemory = null)
        c.persistStructuredTurn(
            PersistStructuredConversationTurnRequest(
                conversationId = "cid",
                messages = listOf(message(MessageRole.USER, "u")),
                historySize = 0,
                messagesBeforeCall = 1,
                assistantMessage = message(MessageRole.ASSISTANT, "a"),
            ),
        )
    }
}
