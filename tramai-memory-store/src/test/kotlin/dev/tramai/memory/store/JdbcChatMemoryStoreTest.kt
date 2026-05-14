package dev.tramai.memory.store

import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.sql.DataSource

class JdbcChatMemoryStoreTest {
    private lateinit var dataSource: DataSource
    private lateinit var store: JdbcChatMemoryStore

    @BeforeEach
    fun setUp() {
        dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:chat-memory-${System.nanoTime()};DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        store = JdbcChatMemoryStore(dataSource)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(store.createTableSql())
            }
        }
    }

    @Test
    fun `get messages returns empty list for unknown conversation`() {
        assertThat(store.getMessages("missing")).isEmpty()
    }

    @Test
    fun `append messages round trips rich messages in ordinal order`() {
        val first = Message(
            role = MessageRole.SYSTEM,
            content = "system",
        )
        val second = Message(
            role = MessageRole.USER,
            content = "",
            contentParts = listOf(
                ContentPart.TextPart("caption"),
                ContentPart.ImagePart("image/png", byteArrayOf(1, 2, 3)),
            ),
        )
        val third = Message(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = listOf(
                ToolCall(
                    id = "call-1",
                    name = "lookup",
                    argumentsJson = """{"id":1}""",
                ),
            ),
        )
        val fourth = Message(
            role = MessageRole.TOOL,
            content = """{"ok":true}""",
            toolCallId = "call-1",
        )

        store.appendMessages("conv-1", listOf(first, second))
        store.appendMessages("conv-1", listOf(third, fourth))

        assertThat(store.getMessages("conv-1")).containsExactly(first, second, third, fourth)
    }

    @Test
    fun `delete conversation removes all messages`() {
        store.appendMessages("conv-1", listOf(Message(MessageRole.USER, "hello")))

        store.deleteConversation("conv-1")

        assertThat(store.getMessages("conv-1")).isEmpty()
    }

    @Test
    fun `list conversations orders by most recent append descending`() {
        store = JdbcChatMemoryStore(
            dataSource = dataSource,
            clockMillis = object {
                private var current = 0L

                fun next(): Long = ++current
            }::next,
        )

        store.appendMessages("conv-a", listOf(Message(MessageRole.USER, "a1")))
        store.appendMessages("conv-b", listOf(Message(MessageRole.USER, "b1")))
        store.appendMessages("conv-a", listOf(Message(MessageRole.USER, "a2")))

        assertThat(store.listConversations(limit = 10, offset = 0))
            .containsExactly("conv-a", "conv-b")
        assertThat(store.listConversations(limit = 1, offset = 1))
            .containsExactly("conv-b")
    }
}
