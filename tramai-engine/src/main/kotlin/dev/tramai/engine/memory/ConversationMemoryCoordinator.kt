package dev.tramai.engine.memory

import dev.tramai.core.annotations.ConversationId
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ConversationIdProvider
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import java.lang.reflect.Method

internal class ConversationMemoryCoordinator(
    private val chatMemory: ChatMemory?,
    private val conversationIdProvider: ConversationIdProvider,
) {
    fun resolveConversationId(method: Method, arguments: Array<out Any?>): String {
        val parameters = method.parameters
        for (i in parameters.indices) {
            if (parameters[i].isAnnotationPresent(ConversationId::class.java)) {
                val argument = arguments[i]
                    ?: throw IllegalArgumentException("@ConversationId parameter '${parameters[i].name}' at index $i is null")
                return argument.toString()
            }
        }
        return conversationIdProvider.resolve()
    }

    fun prepareMessages(initialMessages: List<Message>, conversationId: String?): PreparedConversationMessages? {
        if (chatMemory == null || conversationId == null) return null
        val history = chatMemory.get(conversationId)
        if (history.isEmpty()) return null
        val currentSystem = initialMessages.firstOrNull { it.role == MessageRole.SYSTEM }
        val deduped = if (currentSystem != null && history.any { it.role == MessageRole.SYSTEM }) {
            initialMessages.filter { it.role != MessageRole.SYSTEM }
        } else {
            initialMessages
        }
        return PreparedConversationMessages(history, history + deduped)
    }

    fun persistTurn(request: PersistConversationTurnRequest) {
        if (chatMemory == null) return
        val turnMessages = request.messages.drop(request.historySize).filter { it.role != MessageRole.SYSTEM }
        chatMemory.add(request.conversationId, turnMessages + request.assistantMessage)
    }

    fun persistStructuredTurn(request: PersistStructuredConversationTurnRequest) {
        if (chatMemory == null) return
        val userPrompt = request.messages.subList(request.historySize, request.messagesBeforeCall)
            .filter { it.role != MessageRole.SYSTEM }
        val toolMessages = request.messages.drop(request.messagesBeforeCall)
        chatMemory.add(request.conversationId, userPrompt + toolMessages + request.assistantMessage)
    }
}
