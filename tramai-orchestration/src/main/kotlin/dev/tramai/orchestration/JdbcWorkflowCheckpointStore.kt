package dev.tramai.orchestration
import java.io.StringWriter
import java.sql.SQLException
import java.util.Properties
import javax.sql.DataSource
/**
 * JDBC-backed checkpoint store with revision-aware optimistic concurrency.
 *
 * Applications are responsible for supplying a JDBC driver and creating the target table.
 */
class JdbcWorkflowCheckpointStore(
    internal val dataSource: DataSource,
    private val table: JdbcWorkflowCheckpointTable = JdbcWorkflowCheckpointTable(),
) : WorkflowCheckpointStore, WorkflowCheckpointCatalog {
    override suspend fun load(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint? = dataSource.connection.use { connection ->
        load(connection, workflowName, workflowId)
    }
    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint = dataSource.connection.use { connection ->
        saveInConnection(connection, checkpoint, expectedRevision)
    }
    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
    ) {
        dataSource.connection.use { connection ->
            deleteInConnection(connection, workflowName, workflowId, expectedRevision)
        }
    }

    override suspend fun listCheckpoints(): List<WorkflowCheckpoint> = dataSource.connection.use { connection ->
        connection.prepareStatement(listSql()).use { statement ->
            statement.executeQuery().use { resultSet ->
                val checkpoints = mutableListOf<WorkflowCheckpoint>()
                while (resultSet.next()) {
                    checkpoints += resultSet.toCheckpoint()
                }
                checkpoints
            }
        }
    }
    fun createTableSql(): String = """
        CREATE TABLE ${table.tableName} (
            ${table.workflowNameColumn} VARCHAR(255) NOT NULL,
            ${table.workflowIdColumn} VARCHAR(255) NOT NULL,
            ${table.nextStepIndexColumn} INTEGER NOT NULL,
            ${table.stepExecutionsColumn} INTEGER NOT NULL,
            ${table.lastCompletedStepNameColumn} VARCHAR(255) NULL,
            ${table.statePayloadColumn} TEXT NOT NULL,
            ${table.revisionColumn} BIGINT NOT NULL,
            ${table.metadataColumn} TEXT NOT NULL,
            ${table.savedAtEpochMillisColumn} BIGINT NOT NULL,
            PRIMARY KEY (${table.workflowNameColumn}, ${table.workflowIdColumn})
        )
    """.trimIndent()
    internal fun saveInConnection(
        connection: java.sql.Connection,
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint = if (expectedRevision == null) {
        insertCheckpoint(connection, checkpoint)
    } else {
        updateCheckpoint(connection, checkpoint, expectedRevision)
    }

    internal fun deleteInConnection(
        connection: java.sql.Connection,
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
    ) {
        connection.prepareStatement(deleteSql(expectedRevision != null)).use { statement ->
            statement.setString(1, workflowName)
            statement.setString(2, workflowId)
            if (expectedRevision != null) {
                statement.setLong(3, expectedRevision)
            }
            val updated = statement.executeUpdate()
            if (expectedRevision != null && updated == 0) {
                val existing = load(connection, workflowName, workflowId)
                validateDeleteExpectedRevision(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    existing = existing,
                    expectedRevision = expectedRevision,
                )
            }
        }
    }

    internal fun load(
        connection: java.sql.Connection,
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint? = connection.prepareStatement(selectSql()).use { statement ->
        statement.setString(1, workflowName)
        statement.setString(2, workflowId)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) {
                null
            } else {
                resultSet.toCheckpoint()
            }
        }
    }

    private fun insertCheckpoint(
        connection: java.sql.Connection,
        checkpoint: WorkflowCheckpoint,
    ): WorkflowCheckpoint {
        val existing = load(connection, checkpoint.workflowName, checkpoint.workflowId)
        validateExpectedRevision(
            workflowName = checkpoint.workflowName,
            workflowId = checkpoint.workflowId,
            existing = existing,
            expectedRevision = null,
        )
        val persisted = checkpoint.copy(revision = 1)
        try {
            connection.prepareStatement(insertSql()).use { statement ->
                statement.bindCheckpoint(persisted)
                statement.executeUpdate()
            }
        } catch (error: SQLException) {
            val current = load(connection, checkpoint.workflowName, checkpoint.workflowId)
            if (current != null) {
                validateExpectedRevision(
                    workflowName = checkpoint.workflowName,
                    workflowId = checkpoint.workflowId,
                    existing = current,
                    expectedRevision = null,
                )
            }
            throw error
        }
        return persisted
    }
    private fun updateCheckpoint(
        connection: java.sql.Connection,
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long,
    ): WorkflowCheckpoint {
        val persisted = checkpoint.copy(revision = expectedRevision + 1)
        connection.prepareStatement(updateSql()).use { statement ->
            statement.setInt(1, persisted.nextStepIndex)
            statement.setInt(2, persisted.stepExecutions)
            statement.setString(3, persisted.lastCompletedStepName)
            statement.setString(4, persisted.statePayload)
            statement.setLong(5, persisted.revision)
            statement.setString(6, encodeMetadata(persisted.metadata))
            statement.setLong(7, persisted.savedAtEpochMillis)
            statement.setString(8, checkpoint.workflowName)
            statement.setString(9, checkpoint.workflowId)
            statement.setLong(10, expectedRevision)
            val updated = statement.executeUpdate()
            if (updated == 0) {
                val existing = load(connection, checkpoint.workflowName, checkpoint.workflowId)
                validateExpectedRevision(
                    workflowName = checkpoint.workflowName,
                    workflowId = checkpoint.workflowId,
                    existing = existing,
                    expectedRevision = expectedRevision,
                )
            }
        }
        return persisted
    }
    private fun insertSql(): String = """
        INSERT INTO ${table.tableName} (
            ${table.workflowNameColumn},
            ${table.workflowIdColumn},
            ${table.nextStepIndexColumn},
            ${table.stepExecutionsColumn},
            ${table.lastCompletedStepNameColumn},
            ${table.statePayloadColumn},
            ${table.revisionColumn},
            ${table.metadataColumn},
            ${table.savedAtEpochMillisColumn}
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent()
    private fun updateSql(): String = """
        UPDATE ${table.tableName}
        SET
            ${table.nextStepIndexColumn} = ?,
            ${table.stepExecutionsColumn} = ?,
            ${table.lastCompletedStepNameColumn} = ?,
            ${table.statePayloadColumn} = ?,
            ${table.revisionColumn} = ?,
            ${table.metadataColumn} = ?,
            ${table.savedAtEpochMillisColumn} = ?
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
            AND ${table.revisionColumn} = ?
    """.trimIndent()
    private fun selectSql(): String = """
        SELECT
            ${table.workflowNameColumn},
            ${table.workflowIdColumn},
            ${table.nextStepIndexColumn},
            ${table.stepExecutionsColumn},
            ${table.lastCompletedStepNameColumn},
            ${table.statePayloadColumn},
            ${table.revisionColumn},
            ${table.metadataColumn},
            ${table.savedAtEpochMillisColumn}
        FROM ${table.tableName}
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
    """.trimIndent()
    private fun listSql(): String = """
        SELECT
            ${table.workflowNameColumn},
            ${table.workflowIdColumn},
            ${table.nextStepIndexColumn},
            ${table.stepExecutionsColumn},
            ${table.lastCompletedStepNameColumn},
            ${table.statePayloadColumn},
            ${table.revisionColumn},
            ${table.metadataColumn},
            ${table.savedAtEpochMillisColumn}
        FROM ${table.tableName}
        ORDER BY ${table.workflowNameColumn}, ${table.workflowIdColumn}
    """.trimIndent()
    private fun deleteSql(revisionAware: Boolean): String = if (revisionAware) {
        """
        DELETE FROM ${table.tableName}
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
            AND ${table.revisionColumn} = ?
        """.trimIndent()
    } else {
        """
        DELETE FROM ${table.tableName}
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
        """.trimIndent()
    }
    private fun java.sql.PreparedStatement.bindCheckpoint(checkpoint: WorkflowCheckpoint) {
        setString(1, checkpoint.workflowName)
        setString(2, checkpoint.workflowId)
        setInt(3, checkpoint.nextStepIndex)
        setInt(4, checkpoint.stepExecutions)
        setString(5, checkpoint.lastCompletedStepName)
        setString(6, checkpoint.statePayload)
        setLong(7, checkpoint.revision)
        setString(8, encodeMetadata(checkpoint.metadata))
        setLong(9, checkpoint.savedAtEpochMillis)
    }
    private fun java.sql.ResultSet.toCheckpoint(): WorkflowCheckpoint = WorkflowCheckpoint(
        workflowName = getString(table.workflowNameColumn),
        workflowId = getString(table.workflowIdColumn),
        nextStepIndex = getInt(table.nextStepIndexColumn),
        stepExecutions = getInt(table.stepExecutionsColumn),
        lastCompletedStepName = getString(table.lastCompletedStepNameColumn),
        statePayload = getString(table.statePayloadColumn),
        revision = getLong(table.revisionColumn),
        metadata = decodeMetadata(getString(table.metadataColumn)),
        savedAtEpochMillis = getLong(table.savedAtEpochMillisColumn),
    )
}
data class JdbcWorkflowCheckpointTable(
    val tableName: String = "tramai_workflow_checkpoint",
    val workflowNameColumn: String = "workflow_name",
    val workflowIdColumn: String = "workflow_id",
    val nextStepIndexColumn: String = "next_step_index",
    val stepExecutionsColumn: String = "step_executions",
    val lastCompletedStepNameColumn: String = "last_completed_step_name",
    val statePayloadColumn: String = "state_payload",
    val revisionColumn: String = "revision",
    val metadataColumn: String = "metadata_payload",
    val savedAtEpochMillisColumn: String = "saved_at_epoch_millis",
)
internal fun encodeMetadata(metadata: Map<String, String>): String {
    val properties = Properties()
    metadata.forEach { (key, value) ->
        properties[base64Encode(key)] = base64Encode(value)
    }
    return StringWriter().also { writer ->
        properties.store(writer, "Tramai workflow checkpoint metadata")
    }.toString()
}
internal fun decodeMetadata(payload: String?): Map<String, String> {
    if (payload.isNullOrBlank()) {
        return emptyMap()
    }
    val properties = Properties().apply {
        load(payload.reader())
    }
    return properties.stringPropertyNames().associate { encodedKey ->
        base64Decode(encodedKey) to base64Decode(properties.getProperty(encodedKey))
    }
}
