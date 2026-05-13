package dev.tramai.rag

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.vectorstore.SearchResult

/**
 * Injects retrieved context into a [ModelRequest] to ground the model's response.
 *
 * By default, context is prepended to the first user message or, if a system
 * message exists but no user message, appended to the system message content.
 * If no user message and no system message exist, a new system message is created.
 * Subclasses can override this behaviour.
 */
open class ContextInjector {

    companion object {
        private val SCORE_FORMAT = "%.3f"
    }

    /**
     * Injects the retrieved [context] into the given [request] and returns a
     * modified copy.
     *
     * The context is wrapped in the following format:
     *
     * The following information may be relevant:
     * Source: {source} (Score: {score})
     * {content}
     *
     * Use the information above to answer the user's question.
     *
     * @param context The search results to inject as context.
     * @param request The original model request.
     * @return A new [ModelRequest] with context injected.
     */
    fun inject(context: List<SearchResult>, request: ModelRequest): ModelRequest {
        if (context.isEmpty()) return request

        val contextText = buildContextText(context)

        val newMessages = request.messages.toMutableList()

        // Find the first user message index
        val firstUserIndex = newMessages.indexOfFirst { it.role == MessageRole.USER }

        if (firstUserIndex >= 0) {
            // Inject context into the first user message
            val originalMessage = newMessages[firstUserIndex]
            val augmentedContent = "$contextText\n${originalMessage.content}"
            newMessages[firstUserIndex] = originalMessage.copy(content = augmentedContent)
        } else {
            // No user message found
            val systemIndex = newMessages.indexOfFirst { it.role == MessageRole.SYSTEM }
            if (systemIndex >= 0) {
                // System message exists — append context to it
                val originalMessage = newMessages[systemIndex]
                val augmentedContent = "${originalMessage.content}\n\n$contextText"
                newMessages[systemIndex] = originalMessage.copy(content = augmentedContent)
            } else {
                // No system message either — prepend a system message with context
                newMessages.add(0, Message(role = MessageRole.SYSTEM, content = contextText))
            }
        }

        return request.copy(messages = newMessages)
    }

    /**
     * Builds a formatted context text from the search results.
     * Includes source and score provenance for each result.
     */
    private fun buildContextText(results: List<SearchResult>): String {
        val body = results.joinToString("\n\n") { result ->
            val sourceInfo = if (result.metadata.containsKey("source")) {
                "Source: ${result.metadata["source"]} (Score: ${SCORE_FORMAT.format(result.score)})"
            } else {
                "Score: ${SCORE_FORMAT.format(result.score)}"
            }
            "$sourceInfo\n${result.content}"
        }
        return "The following information may be relevant:\n$body\n\nUse the information above to answer the user's question."
    }
}
