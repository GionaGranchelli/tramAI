package dev.tramai.engine.memory

import dev.tramai.core.model.Message

internal data class PreparedConversationMessages(
    val history: List<Message>,
    val effectiveMessages: List<Message>,
)

internal data class PersistConversationTurnRequest(
    val conversationId: String,
    val messages: List<Message>,
    val historySize: Int,
    val assistantMessage: Message,
)

internal data class PersistStructuredConversationTurnRequest(
    val conversationId: String,
    val messages: List<Message>,
    val historySize: Int,
    val messagesBeforeCall: Int,
    val assistantMessage: Message,
)
