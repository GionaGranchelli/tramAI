package dev.tramai.core.memory

import dev.tramai.core.model.Message

/**
 * Persistence contract for conversation history backed by an external store.
 *
 * All implementations must be thread-safe.
 * All `conversationId` arguments must be non-blank.
 * Implementations should defensively enforce this contract with `require(conversationId.isNotBlank())`.
 *
 * @see ChatMemory for the in-memory caching counterpart
 */
interface ChatMemoryStore {
    /**
     * Returns all messages for the given [conversationId].
     *
     * Returns an empty list when the conversation does not exist in the store.
     * The returned list must be a snapshot or unmodifiable copy of the stored data.
     *
     * @param conversationId the non-blank conversation identifier
     * @return the stored messages, or an empty list if no history exists for the conversation
     * @throws IllegalArgumentException if [conversationId] is blank
     */
    fun getMessages(conversationId: String): List<Message>

    /**
     * Appends the given [messages] to the conversation identified by [conversationId].
     *
     * @param conversationId the non-blank conversation identifier
     * @param messages the messages to append to the conversation history
     * @throws IllegalArgumentException if [conversationId] is blank
     */
    fun appendMessages(conversationId: String, messages: List<Message>)

    /**
     * Permanently removes all history for the given [conversationId].
     *
     * @param conversationId the non-blank conversation identifier
     * @throws IllegalArgumentException if [conversationId] is blank
     */
    fun deleteConversation(conversationId: String)

    /**
     * Returns a paginated list of known conversation identifiers.
     *
     * @param limit the maximum number of identifiers to return (must be >= 1)
     * @param offset the number of identifiers to skip before returning results (must be >= 0)
     * @return a list of conversation identifiers, ordered by creation time descending
     * @throws IllegalArgumentException if [limit] is less than 1 or [offset] is negative
     */
    fun listConversations(limit: Int, offset: Int): List<String>
}
