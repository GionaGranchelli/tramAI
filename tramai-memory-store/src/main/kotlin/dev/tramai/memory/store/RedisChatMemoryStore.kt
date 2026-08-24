package dev.tramai.memory.store

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.tramai.core.memory.ChatMemoryStore
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import redis.clients.jedis.JedisPool
import redis.clients.jedis.exceptions.JedisDataException
import redis.clients.jedis.params.ScanParams
import redis.clients.jedis.resps.ScanResult

/**
 * Redis-backed [ChatMemoryStore] for persistent conversation history.
 *
 * Messages per conversation are stored as a Redis List with key format
 * `{keyPrefix}:{conversationId}`. A sorted-set activity index at the exact
 * `{keyPrefix}` key maps conversationId → last append epoch millis, which
 * makes [listConversations] deterministic (ZREVRANGE) instead of
 * SCAN-order. The index key is structurally disjoint from every
 * conversation key (conversation keys always contain the separator), so no
 * conversation ID can collide with the index.
 *
 * Every append is one atomic MULTI/EXEC transition: a single RPUSH carrying
 * the whole batch (never one RPUSH per message — another writer must not be
 * able to interleave between the messages of one logical append), plus the
 * activity-index ZADD in the same transaction. Deletes remove the
 * conversation list and the index member atomically.
 *
 * Existing pre-index deployments (lists without a ZSET member) stay
 * readable and discoverable: [listConversations] falls back to SCAN for
 * unindexed keys and lists them deterministically after indexed
 * conversations. The next append to a legacy conversation enrolls it in
 * the index as part of the same atomic append.
 *
 * @param jedisPool the Redis connection pool
 * @param keyPrefix prefix for Redis keys (default: "chat")
 * @param objectMapper Jackson ObjectMapper for serialization
 */
class RedisChatMemoryStore(
    private val jedisPool: JedisPool,
    private val keyPrefix: String = "chat",
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules(),
) : ChatMemoryStore {

    /**
     * Internal deterministic-clock seam for the TCK: the published
     * constructor ABI stays unchanged; tests wire a MutableMillisClock here.
     */
    internal var clockMillis: () -> Long = System::currentTimeMillis

    override fun getMessages(conversationId: String): List<Message> {
        require(conversationId.isNotBlank()) { VALIDATION_CONVERSATION_ID_BLANK }
        val key = conversationKey(conversationId)
        return jedisPool.resource.use { jedis ->
            jedis.lrange(key, 0, -1)
                ?.map { deserializeMessage(it) }
                ?: emptyList()
        }
    }

    override fun appendMessages(conversationId: String, messages: List<Message>) {
        require(conversationId.isNotBlank()) { VALIDATION_CONVERSATION_ID_BLANK }
        if (messages.isEmpty()) return
        val key = conversationKey(conversationId)
        val serialized = messages.map(::serializeMessage).toTypedArray()
        val activityTime = clockMillis().toDouble()
        jedisPool.resource.use { jedis ->
            val transaction = jedis.multi()
            try {
                // One logical append transition: the whole batch in a single
                // RPUSH (contiguous — another writer cannot interleave
                // between messages) and the activity-index update in the
                // same Redis transaction.
                transaction.rpush(key, *serialized)
                transaction.zadd(activityIndexKey(), activityTime, conversationId)
                throwIfTransactionFailed(transaction.exec())
            } finally {
                transaction.close()
            }
        }
    }

    override fun deleteConversation(conversationId: String) {
        require(conversationId.isNotBlank()) { VALIDATION_CONVERSATION_ID_BLANK }
        val key = conversationKey(conversationId)
        jedisPool.resource.use { jedis ->
            val transaction = jedis.multi()
            try {
                // Remove data and activity membership atomically: otherwise
                // listConversations could expose an ID whose history is gone.
                transaction.del(key)
                transaction.zrem(activityIndexKey(), conversationId)
                throwIfTransactionFailed(transaction.exec())
            } finally {
                transaction.close()
            }
        }
    }

    override fun listConversations(limit: Int, offset: Int): List<String> {
        require(limit >= 1) { "limit must be at least 1" }
        require(offset >= 0) { "offset must be at least 0" }
        val indexKey = activityIndexKey()
        return jedisPool.resource.use { jedis ->
            val indexed = jedis.zrevrange(indexKey, 0, -1) ?: emptyList()
            if (indexed.size >= offset + limit) {
                return@use indexed.drop(offset).take(limit)
            }
            // Legacy conversations (pre-index lists with no ZSET member) are
            // not in the activity index; discover them by SCAN and list them
            // deterministically (conversationId ascending) AFTER indexed
            // conversations.
            val indexedIds = indexed.toHashSet()
            val legacy = ArrayList<String>()
            var cursor = ScanParams.SCAN_POINTER_START
            val scanParams = ScanParams().match("$keyPrefix:*").count(100)
            do {
                val scanResult: ScanResult<String> = jedis.scan(cursor, scanParams)
                cursor = scanResult.cursor
                for (key in scanResult.result) {
                    val id = extractConversationId(key) ?: continue
                    if (id !in indexedIds) legacy.add(id)
                }
            } while (cursor != ScanParams.SCAN_POINTER_START)
            (indexed + legacy.distinct().sorted()).drop(offset).take(limit)
        }
    }

    /** The activity index lives at the exact key prefix, disjoint from every conversation key. */
    private fun activityIndexKey(): String = keyPrefix

    /**
     * Jedis 6 does NOT throw from `exec()` when a queued command fails — the
     * error is returned as a [JedisDataException] element of the result list.
     * A partial transition (e.g. WRONGTYPE on the index key) must fail
     * loudly, never report success with half the transition applied.
     */
    private fun throwIfTransactionFailed(results: List<Any?>?) {
        results?.firstOrNull { it is JedisDataException }?.let { throw it as JedisDataException }
    }

    private fun conversationKey(conversationId: String): String = "$keyPrefix:$conversationId"

    private fun extractConversationId(key: String): String? {
        val prefix = "$keyPrefix:"
        return if (key.startsWith(prefix)) key.removePrefix(prefix) else null
    }

    // ---- serialization (same pattern as JdbcChatMemoryStore) ----

    private fun serializeMessage(message: Message): String =
        objectMapper.writeValueAsString(
            StoredMessage(
                role = message.role.name,
                content = message.content,
                contentParts = message.contentParts?.map(::toStoredContentPart),
                toolCallId = message.toolCallId,
                toolCalls = message.toolCalls?.map(::toStoredToolCall),
            ),
        )

    private fun deserializeMessage(json: String): Message {
        val stored = objectMapper.readValue(json, StoredMessage::class.java)
        return Message(
            role = MessageRole.valueOf(stored.role),
            content = stored.content,
            contentParts = stored.contentParts?.map(::toContentPart),
            toolCallId = stored.toolCallId,
            toolCalls = stored.toolCalls?.map(::toToolCall),
        )
    }
}

/** @see RedisChatMemoryStore */
private const val VALIDATION_CONVERSATION_ID_BLANK = "conversationId must not be blank"
