package dev.tramai.memory

import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelResponse

/**
 * Intercepts AI service method invocations to inject and persist conversation history.
 *
 * This is a standalone class — NOT an [dev.tramai.core.intercept.OperationInterceptor] — that the
 * [dev.tramai.engine.TramaiInvocationHandler] calls directly after resolving the conversation ID
 * from the method arguments (via [dev.tramai.core.memory.ConversationId]).
 *
 * [interceptRequest] prepends stored conversation history to the outgoing message list and
 * deduplicates system messages to prevent redundant system prompts.
 * [interceptResponse] persists the user messages and assistant response back to [ChatMemory].
 *
 * @param chatMemory the storage backend used to load and persist conversation history
 */
class MemoryInterceptor(
    private val chatMemory: ChatMemory,
) {

    /**
     * Prepends the stored conversation history to the given [messages] and deduplicates
     * system messages.
     *
     * When history exists for the [conversationId], the current request's system message
     * is removed if the stored history already contains one. This prevents redundant system
     * prompts from being sent to the provider on every turn.
     *
     * When no history exists, the messages are returned unchanged.
     *
     * @param conversationId the non-blank conversation identifier
     * @param messages the outgoing messages for the current operation invocation
     * @return the history-prepended message list, or the original [messages] when no history exists
     */
    fun interceptRequest(
        conversationId: String,
        messages: List<Message>,
    ): List<Message> {
        val history = chatMemory.get(conversationId)
        if (history.isEmpty()) return messages
        // System message dedup: remove system from current messages if history already has one
        val currentSystem = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        val dedupedMessages = if (currentSystem != null && history.any { it.role == MessageRole.SYSTEM }) {
            messages.filter { it.role != MessageRole.SYSTEM }
        } else {
            messages
        }
        return history + dedupedMessages
    }

    /**
     * Persists the user messages and assistant response from the completed invocation
     * back to [ChatMemory].
     *
     * Only messages with role [MessageRole.USER] are extracted from the [requestMessages]
     * and stored alongside the newly constructed assistant message from the [response].
     * Tool call/result messages from the engine's internal tool loop are not persisted
     * in v1 (see spec-020 known limitations).
     *
     * @param conversationId the non-blank conversation identifier
     * @param requestMessages the current turn's original request messages (before history prepending).
     *                        Must NOT include the history-prepended messages returned by [interceptRequest],
     *                        as that would cause duplicate persistence of historical turns.
     * @param response the model response to persist
     */
    fun interceptResponse(
        conversationId: String,
        requestMessages: List<Message>,
        response: ModelResponse,
    ) {
        val userMessages = requestMessages.filter { it.role == MessageRole.USER }
        val assistantMessage = Message(
            role = MessageRole.ASSISTANT,
            content = response.content,
            toolCalls = response.toolCalls,
        )
        chatMemory.add(conversationId, userMessages + assistantMessage)
    }
}
