package dev.tramai.orchestration

import org.h2.jdbcx.JdbcConnectionPool
import java.util.UUID

class DurableWorkflowRecoveryJdbcTest : DurableWorkflowRecoveryContractTest() {
    override fun createPersistence(): DurableRecoveryPersistence = JdbcDurableRecoveryPersistence()
}

private class JdbcDurableRecoveryPersistence : DurableRecoveryPersistence {
    private val pool = JdbcConnectionPool.create(
        "jdbc:h2:mem:durable_recovery_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
        "sa",
        "",
    )
    override var checkpointStore: WorkflowCheckpointStore = JdbcWorkflowCheckpointStore(pool)
    override var attemptStore: StepAttemptRecordStore = JdbcStepAttemptRecordStore(pool)

    init {
        pool.connection.use { conn ->
            conn.createStatement().use { statement ->
                statement.execute((checkpointStore as JdbcWorkflowCheckpointStore).createTableSql())
                (attemptStore as JdbcStepAttemptRecordStore).createSchemaSql().forEach { statement.execute(it) }
            }
        }
    }

    override fun recreate() {
        checkpointStore = JdbcWorkflowCheckpointStore(pool)
        attemptStore = JdbcStepAttemptRecordStore(pool)
    }

    override fun close() = pool.dispose()
}
