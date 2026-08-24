package dev.tramai.orchestration

import dev.tramai.testing.persistence.lease.MutableMillisClock
import dev.tramai.testing.persistence.lease.WorkflowLeaseStoreTck
import java.sql.DriverManager
import javax.sql.DataSource
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Epic 8.1g: JdbcWorkflowLeaseStore must satisfy the shared lease
 * compatibility contract (tramai-testing testFixtures) against a REAL
 * relational engine — H2 — rather than a fake JDBC backend. The lease SPI
 * is standard SQL (PK insert, conditional update, delete, row lock), so H2
 * gives stronger contract evidence without Testcontainers. The runner owns
 * the datasource, schema, and per-case table reset.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcWorkflowLeaseStoreTckTest : WorkflowLeaseStoreTck() {

    private lateinit var dataSource: DataSource

    @BeforeAll
    fun setUpAll() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:lease_tck;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
        ds.user = "sa"
        ds.password = ""
        dataSource = ds
        val store = JdbcWorkflowLeaseStore(dataSource)
        DriverManager.getConnection("jdbc:h2:mem:lease_tck;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "")
            .use { conn -> conn.createStatement().use { it.execute(store.createTableSql()) } }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching {
            DriverManager.getConnection("jdbc:h2:mem:lease_tck;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "")
                .use { conn -> conn.createStatement().use { it.execute("DROP TABLE IF EXISTS tramai_workflow_lease") } }
        }
    }

    override fun createStore(clock: MutableMillisClock): WorkflowLeaseStore {
        DriverManager.getConnection("jdbc:h2:mem:lease_tck;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "")
            .use { conn ->
                conn.createStatement().use { it.execute("DELETE FROM tramai_workflow_lease") }
            }
        return JdbcWorkflowLeaseStore(dataSource, clockMillis = clock)
    }
}
