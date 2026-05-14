package dev.tramai.memory

import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ChatMemoryStore
import dev.tramai.core.model.Message

class PersistentChatMemory(
    private val store: ChatMemoryStore,
    private val cache: MessageWindowChatMemory? = null,
) : ChatMemory {

    override fun get(conversationId: String): List<Message> {
        require(conversationId.isNotBlank())
        val cached = cache?.get(conversationId)
        if (cached != null && cached.isNotEmpty()) return cached
        return store.getMessages(conversationId)
    }

    override fun add(conversationId: String, message: Message) {
        add(conversationId, listOf(message))
    }

    override fun add(conversationId: String, messages: List<Message>) {
        require(conversationId.isNotBlank())
        store.appendMessages(conversationId, messages)
        cache?.add(conversationId, messages)
    }

    override fun clear(conversationId: String) {
        require(conversationId.isNotBlank())
        store.deleteConversation(conversationId)
        cache?.clear(conversationId)
    }
}
