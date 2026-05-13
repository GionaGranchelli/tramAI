package dev.tramai.core.memory

import dev.tramai.core.model.Message

/**
 * Stores and retrieves conversation history keyed by conversation ID.
 *
 * All implementations must be thread-safe.
 * All `conversationId` arguments must be non-blank.
 * Implementations should defensively enforce this contract with `require(conversationId.isNotBlank())`.
 * [get] must return a snapshot of the stored history, or an unmodifiable copy, rather than a live view
 * of the implementation's internal data structure.
 */
interface ChatMemory {
    /**
     * Returns the conversation history for the given [conversationId].
     *
     * Returns an empty list when the conversation does not exist.
     * The returned list must be a snapshot of the stored history, or an unmodifiable copy, rather than
     * a live view of the implementation's internal data structure.
     *
     * @param conversationId the non-blank conversation identifier
     * @return the stored conversation history, or an empty list when no history exists for the conversation
     * @throws IllegalArgumentException if [conversationId] is blank
     */
    fun get(conversationId: String): List<Message>

    /**
     * Appends the given [messages] to the conversation identified by [conversationId].
     *
     * @param conversationId the non-blank conversation identifier
     * @param messages the messages to append to the conversation history
     * @throws IllegalArgumentException if [conversationId] is blank
     */
    fun add(
        conversationId: String,
        messages: List<Message>,
    )

    /**
     * Appends the given [message] to the conversation identified by [conversationId].
     *
     * @param conversationId the non-blank conversation identifier
     * @param message the message to append to the conversation history
     * @throws IllegalArgumentException if [conversationId] is blank
     */
    fun add(
        conversationId: String,
        message: Message,
    )

    /**
     * Clears the conversation history for the given [conversationId].
     *
     * @param conversationId the non-blank conversation identifier
     * @throws IllegalArgumentException if [conversationId] is blank
     */
    fun clear(conversationId: String)
}
