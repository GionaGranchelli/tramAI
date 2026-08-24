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
        require(conversationId.isNotBlank()) { VALIDATION_CONVERSATION_ID_BLANK }
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
        require(conversationId.isNotBlank()) { VALIDATION_CONVERSATION_ID_BLANK }
        if (messages.isEmpty()) return

        // Optimistic whole-transaction retry: two concurrent writers can both
        // read nextOrdinal = N; one commits N..N+k, the other loses on the
        // (conversation_id, ordinal) PK. A valid concurrent append must not
        // fail merely because another valid append won ordinal allocation —
        // retry the ENTIRE batch from a fresh durable MAX, never remaining
        // messages, never a partial commit. Only the known ordinal-uniqueness
        // race (SQLState 23505) is retried; other failures keep ordinary JDBC
        // semantics.
        var attempt = 0
        while (true) {
            attempt++
            val failure = appendBatchOnce(conversationId, messages)
            if (failure == null) return
            if (isOrdinalConflict(failure) && attempt < MAX_APPEND_ATTEMPTS) {
                continue
            }
            throw failure
        }
    }

    /**
     * True when [failure] is (or wraps) the ordinal-uniqueness race. H2
     * surfaces batch PK violations as a JdbcBatchUpdateException whose
     * underlying SQLException (SQLState 23505) sits on `nextException` —
     * walk the whole SQLException chain instead of trusting the top frame.
     */
    private fun isOrdinalConflict(failure: Throwable?): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            if (current is java.sql.SQLException && current.sqlState == ORDINAL_UNIQUE_SQL_STATE) return true
            if (current is java.sql.SQLException && current.nextException != null && current.nextException !== current) {
                current = current.nextException
            } else {
                current = current.cause
            }
        }
        return false
    }

    private fun appendBatchOnce(
        conversationId: String,
        messages: List<Message>,
    ): Exception? = dataSource.connection.use { connection ->
        val originalAutoCommit = connection.autoCommit
        var primaryFailure: Exception? = null
        var rollbackFailed = false
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
            null
        } catch (error: Exception) {
            primaryFailure = error
            try {
                connection.rollback()
            } catch (rollbackFailure: Exception) {
                primaryFailure.addSuppressed(rollbackFailure)
                rollbackFailed = true
            }
            // A failed rollback may have left the partial batch in the OPEN
            // transaction: retrying the batch would duplicate those rows.
            // Surface as a non-retryable failure and skip the autoCommit
            // restore (which would COMMIT the partial batch) — closing the
            // connection rolls it back instead.
            if (rollbackFailed) {
                IllegalStateException("append failed and rollback failed; not retrying", primaryFailure)
            } else {
                error
            }
        } finally {
            if (!rollbackFailed) {
                try {
                    connection.autoCommit = originalAutoCommit
                } catch (restoreFailure: Exception) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(restoreFailure)
                    } else {
                        throw restoreFailure
                    }
                }
            }
        }
    }

    override fun deleteConversation(conversationId: String) {
        require(conversationId.isNotBlank()) { VALIDATION_CONVERSATION_ID_BLANK }
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

/** @see JdbcChatMemoryStore */
private const val VALIDATION_CONVERSATION_ID_BLANK = "conversationId must not be blank"

/** SQLState for a unique-constraint violation (the (conversation_id, ordinal) PK race). */
private const val ORDINAL_UNIQUE_SQL_STATE = "23505"

/**
 * Bounded retries for the ordinal-allocation race; after this the race
 * failure is surfaced. Each attempt resolves at most one contention level
 * (one writer wins per ordinal), so the bound must comfortably exceed the
 * maximum expected concurrent writers.
 */
private const val MAX_APPEND_ATTEMPTS = 20

