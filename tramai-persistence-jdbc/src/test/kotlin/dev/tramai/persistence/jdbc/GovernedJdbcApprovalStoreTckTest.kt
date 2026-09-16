package dev.tramai.persistence.jdbc

import dev.tramai.core.approval.ApprovalStore
import dev.tramai.testing.persistence.approval.ApprovalStoreTck
import dev.tramai.testing.persistence.approval.ApprovalStoreTckHarness
import dev.tramai.testing.persistence.approval.MutableClock
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * 0.7.1d1: GovernedJdbcApprovalStore must satisfy the same ApprovalStore compatibility contract as
 * the store it composes.
 *
 * The governed capability delegates into the same `approvals` row, so the released contract has to
 * hold through the wrapper: same datasource, same schema, per-case isolation owned here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernedJdbcApprovalStoreTckTest : ApprovalStoreTck() {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("governed_approval_tck")
                .withUsername("test")
                .withPassword("test")
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        setupConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        val schemaSql =
            javaClass.classLoader
                .getResource("tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql")
                ?.readText()
                ?: error("Schema SQL resource not found")
        setupConnection.createStatement().use { it.execute(schemaSql) }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { setupConnection.close() }
        runCatching { postgres.stop() }
    }

    override val harness =
        object : ApprovalStoreTckHarness {
            override fun createStore(clock: MutableClock): ApprovalStore {
                dataSource.connection.use { conn ->
                    conn.createStatement().use { it.execute("DELETE FROM approvals") }
                }
                return GovernedJdbcApprovalStore(JdbcApprovalStore(dataSource, clock = clock))
            }
        }
}
