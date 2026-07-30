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
    ): WorkflowCheckpoint? = executeJdbcCancellable(dataSource) { conn ->
        load(conn, workflowName, workflowId)
    }
    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint = executeJdbcCancellable(dataSource) { conn ->
        saveInConnection(conn, checkpoint, expectedRevision)
    }
    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
    ) {
        executeJdbcCancellable(dataSource) { conn ->
            deleteInConnection(conn, workflowName, workflowId, expectedRevision)
        }
    }

    override suspend fun listCheckpoints(): List<WorkflowCheckpoint> = executeJdbcCancellable(dataSource) { conn ->
        conn.prepareStatement(listSql()).use { statement ->
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
            ${table.recoveryStateColumn} TEXT NULL,
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
            statement.setString(8, serializeRecoveryState(persisted.recoveryState))
            statement.setString(9, checkpoint.workflowName)
            statement.setString(10, checkpoint.workflowId)
            statement.setLong(11, expectedRevision)
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
            ${table.savedAtEpochMillisColumn},
            ${table.recoveryStateColumn}
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ${table.savedAtEpochMillisColumn} = ?,
            ${table.recoveryStateColumn} = ?
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
            ${table.savedAtEpochMillisColumn},
            ${table.recoveryStateColumn}
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
            ${table.savedAtEpochMillisColumn},
            ${table.recoveryStateColumn}
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
        setString(10, serializeRecoveryState(checkpoint.recoveryState))
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
        recoveryState = deserializeRecoveryState(getString(table.recoveryStateColumn)),
    )

    private fun serializeRecoveryState(state: WorkflowRecoveryState): String? = when (state) {
        WorkflowRecoveryState.Normal -> null
        is WorkflowRecoveryState.Required -> encodeMetadata(
            mapOf(
                "reason" to state.record.reason.name,
                "stepName" to state.record.stepName,
                "attemptId" to state.record.attemptId,
                "priorWorkerId" to state.record.priorWorkerId,
                "detectedAtEpochMillis" to state.record.detectedAtEpochMillis.toString(),
                "idempotencyKey" to (state.record.idempotencyKey ?: ""),
                "instructions" to (state.record.instructions ?: ""),
            )
        )
    }

    private fun deserializeRecoveryState(payload: String?): WorkflowRecoveryState {
        if (payload.isNullOrBlank()) return WorkflowRecoveryState.Normal
        val map = decodeMetadata(payload)
        val reason = WorkflowRecoveryReason.valueOf(map["reason"] ?: return WorkflowRecoveryState.Normal)
        return WorkflowRecoveryState.Required(
            WorkflowRecoveryRecord(
                reason = reason,
                stepName = map["stepName"] ?: return WorkflowRecoveryState.Normal,
                attemptId = map["attemptId"] ?: return WorkflowRecoveryState.Normal,
                priorWorkerId = map["priorWorkerId"] ?: return WorkflowRecoveryState.Normal,
                detectedAtEpochMillis = map["detectedAtEpochMillis"]?.toLongOrNull() ?: return WorkflowRecoveryState.Normal,
                idempotencyKey = map["idempotencyKey"]?.ifBlank { null },
                instructions = map["instructions"]?.ifBlank { null },
            )
        )
    }
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
    val recoveryStateColumn: String = "recovery_state",
) {
    init {
        requireValidSqlIdentifier(tableName, "JdbcWorkflowCheckpointTable.tableName")
        requireValidSqlIdentifier(workflowNameColumn, "JdbcWorkflowCheckpointTable.workflowNameColumn")
        requireValidSqlIdentifier(workflowIdColumn, "JdbcWorkflowCheckpointTable.workflowIdColumn")
        requireValidSqlIdentifier(nextStepIndexColumn, "JdbcWorkflowCheckpointTable.nextStepIndexColumn")
        requireValidSqlIdentifier(stepExecutionsColumn, "JdbcWorkflowCheckpointTable.stepExecutionsColumn")
        requireValidSqlIdentifier(lastCompletedStepNameColumn, "JdbcWorkflowCheckpointTable.lastCompletedStepNameColumn")
        requireValidSqlIdentifier(statePayloadColumn, "JdbcWorkflowCheckpointTable.statePayloadColumn")
        requireValidSqlIdentifier(revisionColumn, "JdbcWorkflowCheckpointTable.revisionColumn")
        requireValidSqlIdentifier(metadataColumn, "JdbcWorkflowCheckpointTable.metadataColumn")
        requireValidSqlIdentifier(savedAtEpochMillisColumn, "JdbcWorkflowCheckpointTable.savedAtEpochMillisColumn")
        requireValidSqlIdentifier(recoveryStateColumn, "JdbcWorkflowCheckpointTable.recoveryStateColumn")
    }
}
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

internal fun requireValidSqlIdentifier(
    identifier: String,
    label: String,
) {
    require(sqlIdentifierPattern.matches(identifier)) {
        "$label must match ^[A-Za-z][A-Za-z0-9_]*$"
    }
}

private val sqlIdentifierPattern = Regex("^[A-Za-z][A-Za-z0-9_]*$")
