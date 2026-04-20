@file:OptIn(ExperimentalTramAIOrchestration::class)

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
@ExperimentalTramAIOrchestration
class JdbcWorkflowCheckpointStore(
    private val dataSource: DataSource,
    private val table: JdbcWorkflowCheckpointTable = JdbcWorkflowCheckpointTable(),
) : WorkflowCheckpointStore {
    override suspend fun load(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint? = dataSource.connection.use { connection ->
        connection.prepareStatement(selectSql()).use { statement ->
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
    }

    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint = if (expectedRevision == null) {
        insertCheckpoint(checkpoint)
    } else {
        updateCheckpoint(checkpoint, expectedRevision)
    }

    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(deleteSql(expectedRevision != null)).use { statement ->
                statement.setString(1, workflowName)
                statement.setString(2, workflowId)
                if (expectedRevision != null) {
                    statement.setLong(3, expectedRevision)
                }
                val updated = statement.executeUpdate()
                if (expectedRevision != null && updated == 0) {
                    val existing = load(workflowName, workflowId)
                    validateDeleteExpectedRevision(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        existing = existing,
                        expectedRevision = expectedRevision,
                    )
                }
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

    private suspend fun insertCheckpoint(checkpoint: WorkflowCheckpoint): WorkflowCheckpoint {
        val existing = load(checkpoint.workflowName, checkpoint.workflowId)
        validateExpectedRevision(
            workflowName = checkpoint.workflowName,
            workflowId = checkpoint.workflowId,
            existing = existing,
            expectedRevision = null,
        )

        val persisted = checkpoint.copy(revision = 1)
        dataSource.connection.use { connection ->
            try {
                connection.prepareStatement(insertSql()).use { statement ->
                    statement.bindCheckpoint(persisted)
                    statement.executeUpdate()
                }
            } catch (error: SQLException) {
                val current = load(checkpoint.workflowName, checkpoint.workflowId)
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
        }
        return persisted
    }

    private suspend fun updateCheckpoint(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long,
    ): WorkflowCheckpoint {
        val persisted = checkpoint.copy(revision = expectedRevision + 1)
        dataSource.connection.use { connection ->
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
                    val existing = load(checkpoint.workflowName, checkpoint.workflowId)
                    validateExpectedRevision(
                        workflowName = checkpoint.workflowName,
                        workflowId = checkpoint.workflowId,
                        existing = existing,
                        expectedRevision = expectedRevision,
                    )
                }
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

@ExperimentalTramAIOrchestration
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
