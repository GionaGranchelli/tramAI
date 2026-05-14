package dev.tramai.memory.store

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.tramai.core.memory.ChatMemoryStore
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import javax.sql.DataSource

/**
 * JDBC-backed [ChatMemoryStore] for persistent conversation history.
 *
 * Applications are responsible for supplying a JDBC driver and creating the target table.
 */
class JdbcChatMemoryStore(
    private val dataSource: DataSource,
    private val table: JdbcChatMemoryTable = JdbcChatMemoryTable(),
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : ChatMemoryStore {

    override fun getMessages(conversationId: String): List<Message> {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        return dataSource.connection.use { connection ->
            connection.prepareStatement(selectMessagesSql()).use { statement ->
                statement.setString(1, conversationId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(deserializeMessage(resultSet.getString(table.messageBlobColumn)))
                        }
                    }
                }
            }
        }
    }

    override fun appendMessages(conversationId: String, messages: List<Message>) {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        if (messages.isEmpty()) return

        dataSource.connection.use { connection ->
            val originalAutoCommit = connection.autoCommit
            try {
                connection.autoCommit = false
                val nextOrdinal = loadNextOrdinal(connection, conversationId)
                connection.prepareStatement(insertMessageSql()).use { statement ->
                    messages.forEachIndexed { index, message ->
                        statement.setString(1, conversationId)
                        statement.setInt(2, nextOrdinal + index)
                        statement.setString(3, serializeMessage(message))
                        statement.setLong(4, clockMillis())
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = originalAutoCommit
            }
        }
    }

    override fun deleteConversation(conversationId: String) {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        dataSource.connection.use { connection ->
            connection.prepareStatement(deleteConversationSql()).use { statement ->
                statement.setString(1, conversationId)
                statement.executeUpdate()
            }
        }
    }

    override fun listConversations(limit: Int, offset: Int): List<String> {
        require(limit >= 1) { "limit must be at least 1" }
        require(offset >= 0) { "offset must be at least 0" }
        return dataSource.connection.use { connection ->
            connection.prepareStatement(listConversationsSql()).use { statement ->
                statement.setInt(1, limit)
                statement.setInt(2, offset)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.getString(1))
                        }
                    }
                }
            }
        }
    }

    fun createTableSql(): String = """
        CREATE TABLE ${table.tableName} (
            ${table.conversationIdColumn} VARCHAR(255) NOT NULL,
            ${table.ordinalColumn} INT NOT NULL,
            ${table.messageBlobColumn} TEXT NOT NULL,
            ${table.createdAtColumn} BIGINT NOT NULL,
            PRIMARY KEY (${table.conversationIdColumn}, ${table.ordinalColumn})
        )
    """.trimIndent()

    private fun loadNextOrdinal(
        connection: java.sql.Connection,
        conversationId: String,
    ): Int = connection.prepareStatement(nextOrdinalSql()).use { statement ->
        statement.setString(1, conversationId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.getInt(1) else 0
        }
    }

    private fun selectMessagesSql(): String = """
        SELECT ${table.messageBlobColumn}
        FROM ${table.tableName}
        WHERE ${table.conversationIdColumn} = ?
        ORDER BY ${table.ordinalColumn} ASC
    """.trimIndent()

    private fun nextOrdinalSql(): String = """
        SELECT COALESCE(MAX(${table.ordinalColumn}) + 1, 0)
        FROM ${table.tableName}
        WHERE ${table.conversationIdColumn} = ?
    """.trimIndent()

    private fun insertMessageSql(): String = """
        INSERT INTO ${table.tableName} (
            ${table.conversationIdColumn},
            ${table.ordinalColumn},
            ${table.messageBlobColumn},
            ${table.createdAtColumn}
        ) VALUES (?, ?, ?, ?)
    """.trimIndent()

    private fun deleteConversationSql(): String = """
        DELETE FROM ${table.tableName}
        WHERE ${table.conversationIdColumn} = ?
    """.trimIndent()

    private fun listConversationsSql(): String = """
        SELECT ${table.conversationIdColumn}
        FROM ${table.tableName}
        GROUP BY ${table.conversationIdColumn}
        ORDER BY MAX(${table.createdAtColumn}) DESC, ${table.conversationIdColumn} ASC
        LIMIT ?
        OFFSET ?
    """.trimIndent()

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

data class JdbcChatMemoryTable(
    val tableName: String = "chat_memory",
    val conversationIdColumn: String = "conversation_id",
    val ordinalColumn: String = "ordinal",
    val messageBlobColumn: String = "message_blob",
    val createdAtColumn: String = "created_at",
)


