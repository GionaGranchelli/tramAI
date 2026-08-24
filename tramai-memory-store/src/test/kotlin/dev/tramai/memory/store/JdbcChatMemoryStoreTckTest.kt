package dev.tramai.memory.store

import dev.tramai.core.memory.ChatMemoryStore
import dev.tramai.testing.persistence.memory.ChatMemoryStoreTck
import dev.tramai.testing.persistence.memory.ChatMemoryStoreTckHarness
import dev.tramai.testing.persistence.memory.MutableMillisClock
import javax.sql.DataSource
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Epic 8.1h: JdbcChatMemoryStore must satisfy the shared chat-memory
 * compatibility contract (tramai-testing testFixtures) against a REAL
 * relational engine — H2. The SPI is standard SQL, so H2 gives stronger
 * contract evidence without Testcontainers. The runner owns the
 * datasource, schema, and per-case reset, and exposes TWO distinct store
 * objects over the SAME DataSource so the concurrency cases prove
 * backend-level coordination.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcChatMemoryStoreTckTest : ChatMemoryStoreTck() {

    private lateinit var dataSource: DataSource

    @BeforeAll
    fun setUpAll() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:chat_memory_tck;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
        ds.user = "sa"
        ds.password = ""
        dataSource = ds
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(JdbcChatMemoryStore(dataSource).createTableSql()) }
        }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP TABLE IF EXISTS chat_memory") }
            }
        }
    }

    override fun createHarness(clock: MutableMillisClock): ChatMemoryStoreTckHarness {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("DELETE FROM chat_memory") }
        }
        return object : ChatMemoryStoreTckHarness {
            override val primary: ChatMemoryStore = JdbcChatMemoryStore(dataSource, clockMillis = clock)
            override val peer: ChatMemoryStore = JdbcChatMemoryStore(dataSource, clockMillis = clock)
        }
    }
}
