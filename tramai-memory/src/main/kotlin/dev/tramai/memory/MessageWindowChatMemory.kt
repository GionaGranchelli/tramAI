package dev.tramai.memory

import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory [ChatMemory] implementation backed by a sliding message window.
 *
 * Each conversation retains at most [maxMessages] non-system messages.
 * System messages ([MessageRole.SYSTEM]) are never evicted from their conversation.
 * When a new system message arrives, any existing system message for that conversation
 * is replaced (deduplication).
 *
 * The total number of tracked conversations is bounded by [maxConversations].
 * When the count exceeds this limit, the least recently used conversation is evicted
 * in its entirety, preventing unbounded memory growth. LRU order is based on the most
 * recent write to a conversation; reads do not affect ordering.
 *
 * All operations are thread-safe. A single global lock protects both the conversation
 * map and the LRU tracker, ensuring consistent lock ordering and preventing races
 * between concurrent adds, evictions, and clears.
 *
 * @param maxMessages maximum number of non-system messages retained per conversation (default: 20, must be > 0)
 * @param maxConversations maximum number of active conversations (default: 1000, must be > 0)
 * @throws IllegalArgumentException if [maxMessages] or [maxConversations] is not positive
 */
class MessageWindowChatMemory(
    private val maxMessages: Int = 20,
    private val maxConversations: Int = 1000,
) : ChatMemory {

    init {
        require(maxMessages > 0) { "maxMessages must be positive, got $maxMessages" }
        require(maxConversations > 0) { "maxConversations must be positive, got $maxConversations" }
    }

    private val conversations = ConcurrentHashMap<String, ConcurrentLinkedDeque<Message>>()
    private val lruTracker = ConcurrentLinkedDeque<String>()
    private val lock = Any()

    override fun get(conversationId: String): List<Message> {
        requireNotNull(conversationId) { VALIDATION_CONVERSATION_ID_NULL }
        require(conversationId.isNotBlank())
        val deque = conversations[conversationId] ?: return emptyList()
        synchronized(lock) {
            return ArrayList(deque)
        }
    }

    override fun add(conversationId: String, message: Message) {
        requireNotNull(conversationId) { VALIDATION_CONVERSATION_ID_NULL }
        require(conversationId.isNotBlank())
        add(conversationId, listOf(message))
    }

    override fun add(conversationId: String, messages: List<Message>) {
        requireNotNull(conversationId) { VALIDATION_CONVERSATION_ID_NULL }
        require(conversationId.isNotBlank())

        synchronized(lock) {
            val deque = conversations.computeIfAbsent(conversationId) {
                ConcurrentLinkedDeque()
            }

            for (message in messages) {
                replaceSystemMessage(deque, message)
                deque.addLast(message)
            }

            // Message eviction: count non-system messages and evict oldest until within limit
            evictMessages(deque)

            // Update LRU: mark this conversation as most recently used
            updateLru(conversationId)
        }
    }

    private fun replaceSystemMessage(deque: ConcurrentLinkedDeque<Message>, message: Message) {
        if (message.role == MessageRole.SYSTEM) {
            val iterator = deque.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().role == MessageRole.SYSTEM) {
                    iterator.remove()
                    break
                }
            }
        }
    }

    private fun evictMessages(deque: ConcurrentLinkedDeque<Message>) {
        var nonSystemCount = deque.count { it.role != MessageRole.SYSTEM }
        while (nonSystemCount > maxMessages) {
            val iterator = deque.iterator()
            while (iterator.hasNext() && nonSystemCount > maxMessages) {
                val msg = iterator.next()
                if (msg.role != MessageRole.SYSTEM) {
                    iterator.remove()
                    nonSystemCount--
                }
            }
        }
    }

    private fun updateLru(conversationId: String) {
        lruTracker.remove(conversationId)
        lruTracker.addLast(conversationId)

        // Conversation eviction: remove least recently used conversations
        while (conversations.size > maxConversations) {
            val lru = lruTracker.pollFirst() ?: break
            if (lru == conversationId) {
                // Don't evict the conversation we just added to
                lruTracker.addFirst(lru)
                break
            }
            conversations.remove(lru)
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

/** @see MessageWindowChatMemory */
private const val VALIDATION_CONVERSATION_ID_NULL = "conversationId must not be null"
