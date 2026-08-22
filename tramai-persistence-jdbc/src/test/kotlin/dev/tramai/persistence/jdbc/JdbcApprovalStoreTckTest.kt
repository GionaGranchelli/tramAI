package dev.tramai.persistence.jdbc

import dev.tramai.core.approval.ApprovalStore
import dev.tramai.testing.persistence.approval.ApprovalStoreTck
import dev.tramai.testing.persistence.approval.ApprovalStoreTckHarness
import dev.tramai.testing.persistence.approval.MutableClock
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * Epic 8.1a: JdbcApprovalStore must satisfy the shared ApprovalStore
 * compatibility contract (tramai-testing testFixtures). The runner owns the
 * datasource + schema — storage technology never contaminates the contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcApprovalStoreTckTest : ApprovalStoreTck() {

    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection

    @BeforeAll
    fun setUpAll() {
        val postgres = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("approval_tck")
            .withUsername("test")
            .withPassword("test")
        postgres.start()
        dataSource = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
        setupConnection = DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
        )
        val schemaSql = this::class.java.classLoader
            .getResource("tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql")
            ?.readText()
            ?: throw IllegalStateException("Schema SQL resource not found")
        setupConnection.createStatement().use { stmt -> stmt.execute(schemaSql) }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { setupConnection.close() }
    }

    override val harness = object : ApprovalStoreTckHarness {
        override fun createStore(clock: MutableClock): ApprovalStore =
            JdbcApprovalStore(dataSource, clock = clock)
    }
}
