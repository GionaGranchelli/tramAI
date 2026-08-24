package dev.tramai.orchestration

import dev.tramai.testing.persistence.lease.MutableMillisClock
import dev.tramai.testing.persistence.lease.WorkflowLeaseCheckpointFenceTck
import java.sql.DriverManager
import javax.sql.DataSource
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Epic 8.1g: JdbcWorkflowLeaseStore must also satisfy the companion
 * WorkflowLeaseCheckpointFence compatibility contract (tramai-testing
 * testFixtures). The lease store and the fenced JdbcWorkflowCheckpointStore
 * SHARE one DataSource — that is what makes the ownership check + checkpoint
 * mutation one atomic transaction boundary (FOR UPDATE row lock).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcWorkflowLeaseStoreCheckpointFenceTckTest : WorkflowLeaseCheckpointFenceTck() {

    private lateinit var dataSource: DataSource

    @BeforeAll
    fun setUpAll() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:fence_tck;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
        ds.user = "sa"
        ds.password = ""
        dataSource = ds
        val leaseStore = JdbcWorkflowLeaseStore(dataSource)
        val checkpointStore = JdbcWorkflowCheckpointStore(dataSource)
        DriverManager.getConnection("jdbc:h2:mem:fence_tck;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "")
            .use { conn ->
                conn.createStatement().use {
                    it.execute(leaseStore.createTableSql())
                    it.execute(checkpointStore.createTableSql())
                }
            }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching {
            DriverManager.getConnection("jdbc:h2:mem:fence_tck;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "")
                .use { conn ->
                    conn.createStatement().use {
                        it.execute("DROP TABLE IF EXISTS tramai_workflow_lease")
                        it.execute("DROP TABLE IF EXISTS tramai_workflow_checkpoint")
                    }
                }
        }
    }

    override fun newHarness(): Harness {
        val clock = MutableMillisClock()
        DriverManager.getConnection("jdbc:h2:mem:fence_tck;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "")
            .use { conn ->
                conn.createStatement().use {
                    it.execute("DELETE FROM tramai_workflow_lease")
                    it.execute("DELETE FROM tramai_workflow_checkpoint")
                }
            }
        val leaseStore = JdbcWorkflowLeaseStore(dataSource, clockMillis = clock)
        return Harness(
            clock = clock,
            leaseStore = leaseStore,
            fence = leaseStore,
            checkpointStore = JdbcWorkflowCheckpointStore(dataSource),
        )
    }
}
