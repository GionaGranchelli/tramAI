package dev.tramai.core.memory

/**
 * Resolves the conversation ID to associate with a model interaction.
 */
fun interface ConversationIdProvider {
    /**
     * Resolves the conversation ID for the current interaction.
     *
     * @return the resolved conversation identifier
     */
    fun resolve(): String
}
