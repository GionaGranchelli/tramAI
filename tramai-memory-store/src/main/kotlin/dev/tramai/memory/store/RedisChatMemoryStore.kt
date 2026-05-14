package dev.tramai.memory.store

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.tramai.core.memory.ChatMemoryStore
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.ScanParams
import redis.clients.jedis.resps.ScanResult
import java.nio.charset.StandardCharsets

/**
 * Redis-backed [ChatMemoryStore] for persistent conversation history.
 *
 * Messages per conversation are stored as a Redis List with key format `{keyPrefix}:{conversationId}`.
 * Uses [JedisPool] for connection pooling.
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

    override fun getMessages(conversationId: String): List<Message> {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        val key = conversationKey(conversationId)
        return jedisPool.resource.use { jedis ->
            jedis.lrange(key, 0, -1)
                ?.map { deserializeMessage(it) }
                ?: emptyList()
        }
    }

    override fun appendMessages(conversationId: String, messages: List<Message>) {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        if (messages.isEmpty()) return
        val key = conversationKey(conversationId)
        jedisPool.resource.use { jedis ->
            messages.forEach { message ->
                jedis.rpush(key, serializeMessage(message))
            }
        }
    }

    override fun deleteConversation(conversationId: String) {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        val key = conversationKey(conversationId)
        jedisPool.resource.use { jedis ->
            jedis.del(key)
        }
    }

    override fun listConversations(limit: Int, offset: Int): List<String> {
        require(limit >= 1) { "limit must be at least 1" }
        require(offset >= 0) { "offset must be at least 0" }
        val pattern = "$keyPrefix:*"
        val scanParams = ScanParams().match(pattern).count(100)
        val result = mutableListOf<String>()
        var cursor = ScanParams.SCAN_POINTER_START

        jedisPool.resource.use { jedis ->
            do {
                val scanResult: ScanResult<ByteArray> = jedis.scan(cursor.toByteArray(StandardCharsets.UTF_8), scanParams)
                cursor = scanResult.cursor
                for (keyBytes in scanResult.result) {
                    val key = String(keyBytes, StandardCharsets.UTF_8)
                    val conversationId = extractConversationId(key) ?: continue
                    result.add(conversationId)
                }
            } while (cursor != ScanParams.SCAN_POINTER_START && result.size < offset + limit)
        }

        return result.drop(offset).take(limit)
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
