package dev.tramai.memory

import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.Tokenizer
import dev.tramai.core.memory.roughTokenizer
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory [ChatMemory] implementation backed by a token-count sliding window.
 *
 * Each conversation retains at most [maxTokens] tokens worth of non-system
 * messages. System messages ([MessageRole.SYSTEM]) are never evicted from
 * their conversation. When a new system message arrives, any existing system
 * message for that conversation is replaced (deduplication).
 *
 * Token counts are computed via the [tokenizer] function. The default
 * [roughTokenizer] uses a 3-character-per-token heuristic (~1 token per 3 chars),
 * which is a reasonable approximation for English text. For production use with
 * a specific LLM, provide a model-accurate tokenizer (e.g., tiktoken for OpenAI).
 *
 * The total number of tracked conversations is bounded by [maxConversations].
 * When the count exceeds this limit, the least recently used conversation is
 * evicted in its entirety, preventing unbounded memory growth. LRU order is
 * based on the most recent write to a conversation; reads do not affect ordering.
 *
 * All operations are thread-safe. A single global lock protects both the
 * conversation map and the LRU tracker, ensuring consistent lock ordering
 * and preventing races between concurrent adds, evictions, and clears.
 *
 * @param maxTokens maximum total tokens of non-system messages retained per
 *   conversation (default: 4096, must be > 0). Token counts are approximate
 *   and depend on the [tokenizer] implementation.
 * @param maxConversations maximum number of active conversations (default: 1000,
 *   must be > 0)
 * @param tokenizer the function used to count tokens in message content
 *   (default: [roughTokenizer])
 * @throws IllegalArgumentException if [maxTokens] or [maxConversations] is not
 *   positive
 */
class TokenAwareChatMemory(
    private val maxTokens: Int = 4096,
    private val maxConversations: Int = 1000,
    private val tokenizer: Tokenizer = roughTokenizer(),
) : ChatMemory {

    init {
        require(maxTokens > 0) { "maxTokens must be positive, got $maxTokens" }
        require(maxConversations > 0) { "maxConversations must be positive, got $maxConversations" }
    }

    private data class TokenCountMessage(
        val message: Message,
        val tokenCount: Int,
    )

    private val conversations = ConcurrentHashMap<String, ConcurrentLinkedDeque<TokenCountMessage>>()
    private val lruTracker = ConcurrentLinkedDeque<String>()
    private val lock = Any()

    override fun get(conversationId: String): List<Message> {
        requireNotNull(conversationId) { VALIDATION_CONVERSATION_ID_NULL }
        require(conversationId.isNotBlank())
        val deque = conversations[conversationId] ?: return emptyList()
        synchronized(lock) {
            return ArrayList(deque.map { it.message })
        }
    }

    override fun add(conversationId: String, message: Message) {
        requireNotNull(conversationId) { VALIDATION_CONVERSATION_ID_NULL }
        add(conversationId, listOf(message))
    }

    override fun add(conversationId: String, messages: List<Message>) {
        requireNotNull(conversationId) { VALIDATION_CONVERSATION_ID_NULL }
        require(conversationId.isNotBlank())
        synchronized(lock) {
            val deque = conversations.computeIfAbsent(conversationId) { ConcurrentLinkedDeque() }

            for (message in messages) {
                replaceSystemMessage(deque, message)
                val tokenCount = tokenizer.countTokens(message.content)
                deque.addLast(TokenCountMessage(message, tokenCount))
            }

            // Token eviction: count tokens of non-system messages
            evictTokens(deque)

            // Update LRU
            updateLru(conversationId)
        }
    }

    private fun replaceSystemMessage(deque: ConcurrentLinkedDeque<TokenCountMessage>, message: Message) {
        if (message.role == MessageRole.SYSTEM) {
            val iterator = deque.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().message.role == MessageRole.SYSTEM) {
                    iterator.remove()
                    break
                }
            }
        }
    }

    private fun updateLru(conversationId: String) {
        lruTracker.remove(conversationId)
        lruTracker.addLast(conversationId)

        // Conversation eviction
        while (conversations.size > maxConversations) {
            val lru = lruTracker.pollFirst() ?: break
            if (lru == conversationId) {
                lruTracker.addFirst(lru)
                break
            }
            conversations.remove(lru)
        }
    }

    private fun evictTokens(deque: ConcurrentLinkedDeque<TokenCountMessage>) {
        var tokenTotal = 0
        for (entry in deque) {
            if (entry.message.role != MessageRole.SYSTEM) {
                tokenTotal += entry.tokenCount
            }
        }
        val iterator = deque.iterator()
        while (iterator.hasNext() && tokenTotal > maxTokens) {
            val entry = iterator.next()
            if (entry.message.role != MessageRole.SYSTEM) {
                iterator.remove()
                tokenTotal -= entry.tokenCount
            }
        }
    }

    override fun clear(conversationId: String) {
        requireNotNull(conversationId) { VALIDATION_CONVERSATION_ID_NULL }
        require(conversationId.isNotBlank())
        synchronized(lock) {
            conversations.remove(conversationId)
            lruTracker.remove(conversationId)
        }
    }
}

/** @see TokenAwareChatMemory */
private const val VALIDATION_CONVERSATION_ID_NULL = "conversationId must not be null"
