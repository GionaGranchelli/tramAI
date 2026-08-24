package dev.tramai.orchestration

import dev.tramai.testing.persistence.checkpoint.WorkflowCheckpointStoreTck
import java.sql.DriverManager
import javax.sql.DataSource
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Epic 8.1f: JdbcWorkflowCheckpointStore must satisfy the shared checkpoint
 * compatibility contract (tramai-testing testFixtures) against a REAL
 * relational engine — H2 — rather than a fake JDBC backend. The checkpoint
 * SPI is standard SQL (primary keys, inserts, conditional updates, deletes),
 * so H2 gives stronger contract evidence without Testcontainers. The runner
 * owns the datasource, schema, and per-case table reset; SQL mechanics are
 * never part of the shared contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcWorkflowCheckpointStoreTckTest : WorkflowCheckpointStoreTck() {

    private lateinit var dataSource: DataSource

    @BeforeAll
    fun setUpAll() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:checkpoint_tck;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
        ds.user = "sa"
        ds.password = ""
        dataSource = ds
        val store = JdbcWorkflowCheckpointStore(dataSource)
        DriverManager.getConnection("jdbc:h2:mem:checkpoint_tck;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "")
            .use { conn -> conn.createStatement().use { it.execute(store.createTableSql()) } }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching {
            DriverManager.getConnection("jdbc:h2:mem:checkpoint_tck;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "")
                .use { conn -> conn.createStatement().use { it.execute("DROP TABLE IF EXISTS tramai_workflow_checkpoint") } }
        }
    }

    override fun createStore(): WorkflowCheckpointStore {
        // Fresh isolated storage per case.
        DriverManager.getConnection("jdbc:h2:mem:checkpoint_tck;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "")
            .use { conn ->
                conn.createStatement().use { it.execute("DELETE FROM tramai_workflow_checkpoint") }
            }
        return JdbcWorkflowCheckpointStore(dataSource)
    }
}
