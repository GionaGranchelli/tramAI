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
) : WorkflowCheckpointStore,
    WorkflowCheckpointCatalog {
    var persistenceFailureDiagnosticObserver: PersistenceFailureDiagnosticObserver =
        NoOpPersistenceFailureDiagnosticObserver
        internal set

    constructor(
        dataSource: DataSource,
        table: JdbcWorkflowCheckpointTable,
        observer: PersistenceFailureDiagnosticObserver,
    ) : this(dataSource, table) {
        persistenceFailureDiagnosticObserver = observer
    }

    override suspend fun load(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint? =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.LOAD,
            persistenceFailureDiagnosticObserver,
            classify = ::classifyCheckpointFailure,
        ) {
            executeJdbcCancellable(dataSource) { conn ->
                load(conn, workflowName, workflowId)
            }
        }

    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.SAVE,
            persistenceFailureDiagnosticObserver,
            classify = ::classifyCheckpointFailure,
        ) {
            executeJdbcCancellable(dataSource) { conn ->
                saveInConnection(conn, checkpoint, expectedRevision)
            }
        }

    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
        expectedGeneration: String?,
    ) {
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.DELETE,
            persistenceFailureDiagnosticObserver,
            ::classifyCheckpointFailure,
        ) {
            executeJdbcCancellable(dataSource) { conn ->
                deleteInConnection(conn, workflowName, workflowId, expectedRevision, expectedGeneration)
            }
        }
    }

    override suspend fun lifecycle(
        workflowName: String,
        workflowId: String,
    ): WorkflowLifecycleState? =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.LOAD,
            persistenceFailureDiagnosticObserver,
            classify = ::classifyCheckpointFailure,
        ) {
            executeJdbcCancellable(dataSource) { conn ->
                lifecycleInConnection(conn, workflowName, workflowId)
            }
        }

    override suspend fun cancel(request: WorkflowCancellationRequest): WorkflowLifecycleState =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.COMPARE_AND_SET,
            persistenceFailureDiagnosticObserver,
            classify = ::classifyCheckpointFailure,
        ) {
            executeJdbcCancellable(dataSource) { conn ->
                cancelInConnection(conn, request)
            }
        }

    override suspend fun admit(request: WorkflowAdmissionRequest): WorkflowAdmission =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.COMPARE_AND_SET,
            persistenceFailureDiagnosticObserver,
            classify = ::classifyCheckpointFailure,
        ) {
            executeJdbcCancellable(dataSource) { conn ->
                admitInConnection(conn, request)
            }
        }

    override suspend fun listCheckpoints(): List<WorkflowCheckpoint> =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.LIST,
            persistenceFailureDiagnosticObserver,
            classify = ::classifyCheckpointFailure,
        ) {
            executeJdbcCancellable(dataSource) { conn ->
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
        }

    fun createTableSql(): String =
        """
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
            ${table.checkpointGenerationColumn} TEXT NULL,
            ${table.lifecycleStatusColumn} TEXT NULL,
            ${table.cancelledAtEpochMillisColumn} BIGINT NULL,
            ${table.cancelledByColumn} TEXT NULL,
            ${table.cancellationReasonColumn} TEXT NULL,
            ${table.admissionSequenceColumn} BIGINT NOT NULL DEFAULT 0,
            PRIMARY KEY (${table.workflowNameColumn}, ${table.workflowIdColumn})
        )
        """.trimIndent()

    fun migrationSql(): String = "ALTER TABLE ${table.tableName} ADD COLUMN ${table.recoveryStateColumn} TEXT NULL"

    fun checkpointGenerationMigrationSql(): String =
        "ALTER TABLE ${table.tableName} ADD COLUMN ${table.checkpointGenerationColumn} TEXT NULL"

    /**
     * Additive migration for the lifecycle columns. Nullable, so rows written before cancellation existed
     * read as "never cancelled" without a backfill.
     */
    fun lifecycleMigrationSql(): List<String> =
        listOf(
            "ALTER TABLE ${table.tableName} ADD COLUMN ${table.lifecycleStatusColumn} TEXT NULL",
            "ALTER TABLE ${table.tableName} ADD COLUMN ${table.cancelledAtEpochMillisColumn} BIGINT NULL",
            "ALTER TABLE ${table.tableName} ADD COLUMN ${table.cancelledByColumn} TEXT NULL",
            "ALTER TABLE ${table.tableName} ADD COLUMN ${table.cancellationReasonColumn} TEXT NULL",
            "ALTER TABLE ${table.tableName} ADD COLUMN ${table.admissionSequenceColumn} BIGINT NOT NULL DEFAULT 0",
        )

    internal fun saveInConnection(
        connection: java.sql.Connection,
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint =
        if (expectedRevision == null) {
            insertCheckpoint(connection, checkpoint)
        } else {
            updateCheckpoint(connection, checkpoint, expectedRevision)
        }

    internal fun deleteInConnection(
        connection: java.sql.Connection,
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
        expectedGeneration: String?,
    ) {
        // Retention and terminality in the statement itself: the cancelled row is the evidence of the
        // cancellation, and a probe followed by a delete would let a cancellation commit in between. The delete
        // carries the guard, so it either removes a live row or removes nothing.
        connection
            .prepareStatement(deleteSql(expectedRevision != null, expectedGeneration != null))
            .use { statement ->
                statement.setString(1, workflowName)
                statement.setString(2, workflowId)
                if (expectedRevision != null) {
                    statement.setLong(3, expectedRevision)
                    if (expectedGeneration != null) {
                        statement.setString(4, expectedGeneration)
                    }
                }
                val updated = statement.executeUpdate()
                if (updated == 0) {
                    val existing = load(connection, workflowName, workflowId)
                    // The row is still there, so the statement refused for a reason: a stale precondition or a
                    // committed cancellation. Either way this delete did not happen and must not look like it did.
                    if (existing != null) {
                        throw safePersistenceFailure(
                            PersistenceResourceKind.CHECKPOINT,
                            PersistenceOperation.DELETE,
                            PersistenceFailureCode.CONFLICT,
                        )
                    }
                    validateDeleteExpectedRevision(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        existing = null,
                        expectedRevision = expectedRevision,
                        expectedGeneration = expectedGeneration,
                    )
                }
            }
    }

    internal fun admitInConnection(
        connection: java.sql.Connection,
        request: WorkflowAdmissionRequest,
    ): WorkflowAdmission {
        connection.prepareStatement(admitSql(request.expectedGeneration != null)).use { statement ->
            statement.setString(1, request.workflowName)
            statement.setString(2, request.workflowId)
            statement.setLong(3, request.expectedRevision)
            if (request.expectedGeneration != null) {
                statement.setString(4, request.expectedGeneration)
            }
            if (statement.executeUpdate() > 0) {
                return WorkflowAdmission(true, admissionSequenceInConnection(connection, request))
            }
        }
        // Refused. Either the run is cancelled (the decisive case) or the precondition no longer holds.
        lifecycleInConnection(connection, request.workflowName, request.workflowId)?.let { cancellation ->
            return WorkflowAdmission(false, admissionSequenceInConnection(connection, request), cancellation)
        }
        throw safePersistenceFailure(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.COMPARE_AND_SET,
            PersistenceFailureCode.CONFLICT,
        )
    }

    private fun admissionSequenceInConnection(
        connection: java.sql.Connection,
        request: WorkflowAdmissionRequest,
    ): Long =
        connection.prepareStatement(admissionSequenceSelectSql()).use { statement ->
            statement.setString(1, request.workflowName)
            statement.setString(2, request.workflowId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) 0L else resultSet.getLong(table.admissionSequenceColumn)
            }
        }

    /**
     * The cancellation write is one conditional UPDATE on the row that already holds the run: exactly one
     * command can win it, and the winner is the linearization point. A loser either sees an already-cancelled
     * row (idempotent, returns the recorded state) or a stale precondition (conflict).
     */
    internal fun cancelInConnection(
        connection: java.sql.Connection,
        request: WorkflowCancellationRequest,
    ): WorkflowLifecycleState {
        connection.prepareStatement(cancelSql(request.expectedGeneration != null)).use { statement ->
            statement.setString(1, WorkflowLifecycleStatus.CANCELLED_UNCERTAIN.wireName)
            statement.setString(2, request.status.wireName)
            statement.setLong(3, request.cancelledAtEpochMillis)
            statement.setString(4, request.actor)
            statement.setString(5, request.reason)
            statement.setString(6, request.workflowName)
            statement.setString(7, request.workflowId)
            statement.setLong(8, request.expectedRevision)
            if (request.expectedGeneration != null) {
                statement.setString(9, request.expectedGeneration)
            }
            if (statement.executeUpdate() > 0) {
                // Read back instead of predicting: the stored status may have been derived as uncertain because
                // work was admitted before this commit, and predicting it here would be a second opinion.
                return lifecycleInConnection(connection, request.workflowName, request.workflowId)
                    ?: throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.COMPARE_AND_SET,
                        PersistenceFailureCode.CONFLICT,
                    )
            }
        }
        lifecycleInConnection(connection, request.workflowName, request.workflowId)?.let { return it }
        throw safePersistenceFailure(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.COMPARE_AND_SET,
            PersistenceFailureCode.CONFLICT,
        )
    }

    internal fun lifecycleInConnection(
        connection: java.sql.Connection,
        workflowName: String,
        workflowId: String,
    ): WorkflowLifecycleState? =
        connection.prepareStatement(lifecycleSelectSql()).use { statement ->
            statement.setString(1, workflowName)
            statement.setString(2, workflowId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    null
                } else {
                    WorkflowLifecycleRow(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        lifecycleStatus = resultSet.getString(table.lifecycleStatusColumn),
                        revision = resultSet.getLong(table.revisionColumn),
                        checkpointGeneration = resultSet.getString(table.checkpointGenerationColumn),
                        cancelledAtEpochMillis = resultSet.getObject(table.cancelledAtEpochMillisColumn) as? Long,
                        cancelledBy = resultSet.getString(table.cancelledByColumn),
                        cancellationReason = resultSet.getString(table.cancellationReasonColumn),
                    ).toState()
                }
            }
        }

    internal fun load(
        connection: java.sql.Connection,
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint? =
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
            expectedGeneration = null,
        )
        val persisted =
            checkpoint.copy(
                revision = 1,
                checkpointGeneration = newCheckpointGeneration(),
            )
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
                    expectedGeneration = null,
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
        val persisted =
            checkpoint.copy(
                revision = expectedRevision + 1,
                checkpointGeneration = checkpoint.checkpointGeneration ?: newCheckpointGeneration(),
            )
        connection
            .prepareStatement(updateSql(checkpoint.checkpointGeneration != null))
            .use { statement ->
                statement.setInt(1, persisted.nextStepIndex)
                statement.setInt(2, persisted.stepExecutions)
                statement.setString(3, persisted.lastCompletedStepName)
                statement.setString(4, persisted.statePayload)
                statement.setLong(5, persisted.revision)
                statement.setString(6, encodeMetadata(persisted.metadata))
                statement.setLong(7, persisted.savedAtEpochMillis)
                statement.setString(8, encodeRecoveryState(persisted.recoveryState))
                statement.setString(9, persisted.checkpointGeneration)
                statement.setString(10, checkpoint.workflowName)
                statement.setString(11, checkpoint.workflowId)
                statement.setLong(12, expectedRevision)
                if (checkpoint.checkpointGeneration != null) {
                    statement.setString(13, checkpoint.checkpointGeneration)
                }
                val updated = statement.executeUpdate()
                if (updated == 0) {
                    val existing = load(connection, checkpoint.workflowName, checkpoint.workflowId)
                    // Preconditions still hold, and yet nothing was written: the lifecycle guard refused it, which
                    // only happens when the run is cancelled. A cancelled run is not a run whose checkpoint moves.
                    val preconditionsStillHold =
                        existing != null &&
                            existing.revision == expectedRevision &&
                            (
                                checkpoint.checkpointGeneration == null ||
                                    existing.checkpointGeneration == checkpoint.checkpointGeneration
                            )
                    if (preconditionsStillHold) {
                        throw safePersistenceFailure(
                            PersistenceResourceKind.CHECKPOINT,
                            PersistenceOperation.SAVE,
                            PersistenceFailureCode.CONFLICT,
                        )
                    }
                    validateExpectedRevision(
                        workflowName = checkpoint.workflowName,
                        workflowId = checkpoint.workflowId,
                        existing = existing,
                        expectedRevision = expectedRevision,
                        expectedGeneration = checkpoint.checkpointGeneration,
                    )
                }
            }
        return persisted
    }

    private fun insertSql(): String =
        """
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
            ${table.recoveryStateColumn},
            ${table.checkpointGenerationColumn}
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    private fun updateSql(generationAware: Boolean): String {
        val live = "\n            AND ${table.lifecycleStatusColumn} IS NULL"
        return """
            UPDATE ${table.tableName}
            SET
                ${table.nextStepIndexColumn} = ?,
                ${table.stepExecutionsColumn} = ?,
                ${table.lastCompletedStepNameColumn} = ?,
                ${table.statePayloadColumn} = ?,
                ${table.revisionColumn} = ?,
                ${table.metadataColumn} = ?,
                ${table.savedAtEpochMillisColumn} = ?,
                ${table.recoveryStateColumn} = ?,
                ${table.checkpointGenerationColumn} = ?
            WHERE ${table.workflowNameColumn} = ?
                AND ${table.workflowIdColumn} = ?
                AND ${table.revisionColumn} = ?
                AND ${if (generationAware) "${table.checkpointGenerationColumn} = ?" else "${table.checkpointGenerationColumn} IS NULL"}$live
            """.trimIndent()
    }

    /**
     * Admission is the fence: one conditional write that only succeeds while the run is not cancelled. It is
     * ordered against cancellation by the same row, so a cancellation that commits first refuses every later
     * admission, and an admission that commits first makes the cancellation uncertain.
     */
    private fun admitSql(generationAware: Boolean): String =
        """
        UPDATE ${table.tableName}
        SET ${table.admissionSequenceColumn} = ${table.admissionSequenceColumn} + 1
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
            AND ${table.revisionColumn} = ?
            AND ${if (generationAware) "${table.checkpointGenerationColumn} = ?" else "${table.checkpointGenerationColumn} IS NULL"}
            AND ${table.lifecycleStatusColumn} IS NULL
        """.trimIndent()

    private fun admissionSequenceSelectSql(): String =
        """
        SELECT ${table.admissionSequenceColumn}
        FROM ${table.tableName}
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
        """.trimIndent()

    private fun cancelSql(generationAware: Boolean): String =
        """
        UPDATE ${table.tableName}
        SET
            ${table.lifecycleStatusColumn} =
                CASE WHEN ${table.admissionSequenceColumn} > 0 THEN ? ELSE ? END,
            ${table.cancelledAtEpochMillisColumn} = ?,
            ${table.cancelledByColumn} = ?,
            ${table.cancellationReasonColumn} = ?,
            ${table.revisionColumn} = ${table.revisionColumn} + 1
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
            AND ${table.revisionColumn} = ?
            AND ${if (generationAware) "${table.checkpointGenerationColumn} = ?" else "${table.checkpointGenerationColumn} IS NULL"}
            AND ${table.lifecycleStatusColumn} IS NULL
        """.trimIndent()

    private fun lifecycleSelectSql(): String =
        """
        SELECT
            ${table.revisionColumn},
            ${table.checkpointGenerationColumn},
            ${table.lifecycleStatusColumn},
            ${table.cancelledAtEpochMillisColumn},
            ${table.cancelledByColumn},
            ${table.cancellationReasonColumn}
        FROM ${table.tableName}
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
        """.trimIndent()

    private fun selectSql(): String =
        """
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
            ${table.recoveryStateColumn},
            ${table.checkpointGenerationColumn}
        FROM ${table.tableName}
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
        """.trimIndent()

    private fun listSql(): String =
        """
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
            ${table.recoveryStateColumn},
            ${table.checkpointGenerationColumn}
        FROM ${table.tableName}
        ORDER BY ${table.workflowNameColumn}, ${table.workflowIdColumn}
        """.trimIndent()

    private fun deleteSql(
        revisionAware: Boolean,
        generationAware: Boolean,
    ): String {
        val live = "\n            AND ${table.lifecycleStatusColumn} IS NULL"
        return if (revisionAware) {
            """
            DELETE FROM ${table.tableName}
            WHERE ${table.workflowNameColumn} = ?
                AND ${table.workflowIdColumn} = ?
                AND ${table.revisionColumn} = ?
                AND ${if (generationAware) "${table.checkpointGenerationColumn} = ?" else "${table.checkpointGenerationColumn} IS NULL"}$live
            """.trimIndent()
        } else {
            """
            DELETE FROM ${table.tableName}
            WHERE ${table.workflowNameColumn} = ?
                AND ${table.workflowIdColumn} = ?$live
            """.trimIndent()
        }
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
        setString(10, encodeRecoveryState(checkpoint.recoveryState))
        setString(11, checkpoint.checkpointGeneration)
    }

    private fun java.sql.ResultSet.toCheckpoint(): WorkflowCheckpoint {
        val metadataPayload = getString(table.metadataColumn)
        val recoveryPayload = getString(table.recoveryStateColumn)
        return try {
            WorkflowCheckpoint(
                workflowName = getString(table.workflowNameColumn),
                workflowId = getString(table.workflowIdColumn),
                nextStepIndex = getInt(table.nextStepIndexColumn),
                stepExecutions = getInt(table.stepExecutionsColumn),
                lastCompletedStepName = getString(table.lastCompletedStepNameColumn),
                statePayload = getString(table.statePayloadColumn),
                revision = getLong(table.revisionColumn),
                metadata = decodeMetadata(metadataPayload),
                savedAtEpochMillis = getLong(table.savedAtEpochMillisColumn),
                recoveryState = decodeRecoveryState(recoveryPayload),
                checkpointGeneration = getString(table.checkpointGenerationColumn),
            )
        } catch (error: CorruptCheckpointException) {
            throw error
        } catch (error: Throwable) {
            throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", metadataPayload ?: recoveryPayload)
        }
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
    val checkpointGenerationColumn: String = "checkpoint_generation",
    val lifecycleStatusColumn: String = "lifecycle_status",
    val cancelledAtEpochMillisColumn: String = "cancelled_at_epoch_millis",
    val cancelledByColumn: String = "cancelled_by",
    val cancellationReasonColumn: String = "cancellation_reason",
    val admissionSequenceColumn: String = "admission_seq",
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
        requireValidSqlIdentifier(checkpointGenerationColumn, "JdbcWorkflowCheckpointTable.checkpointGenerationColumn")
        requireValidSqlIdentifier(lifecycleStatusColumn, LABEL_LIFECYCLESTATUSCOLUMN)
        requireValidSqlIdentifier(
            cancelledAtEpochMillisColumn,
            "JdbcWorkflowCheckpointTable.cancelledAtEpochMillisColumn",
        )
        requireValidSqlIdentifier(cancelledByColumn, LABEL_CANCELLEDBYCOLUMN)
        requireValidSqlIdentifier(cancellationReasonColumn, LABEL_CANCELLATIONREASONCOLUMN)
        requireValidSqlIdentifier(admissionSequenceColumn, LABEL_ADMISSIONSEQUENCECOLUMN)
    }
}

internal fun encodeMetadata(metadata: Map<String, String>): String {
    val properties = Properties()
    metadata.forEach { (key, value) ->
        properties[base64Encode(key)] = base64Encode(value)
    }
    return StringWriter()
        .also { writer ->
            properties.store(writer, "Tramai workflow checkpoint metadata")
        }.toString()
}

internal fun decodeMetadata(payload: String?): Map<String, String> =
    try {
        if (payload.isNullOrBlank()) {
            emptyMap()
        } else {
            val properties = Properties().apply { load(payload.reader()) }
            properties.stringPropertyNames().associate { encodedKey ->
                base64Decode(encodedKey) to base64Decode(properties.getProperty(encodedKey))
            }
        }
    } catch (error: CorruptCheckpointException) {
        throw error
    } catch (error: Throwable) {
        throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", payload)
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

private const val LABEL_LIFECYCLESTATUSCOLUMN = "JdbcWorkflowCheckpointTable.lifecycleStatusColumn"
private const val LABEL_CANCELLEDBYCOLUMN = "JdbcWorkflowCheckpointTable.cancelledByColumn"
private const val LABEL_CANCELLATIONREASONCOLUMN = "JdbcWorkflowCheckpointTable.cancellationReasonColumn"
private const val LABEL_ADMISSIONSEQUENCECOLUMN = "JdbcWorkflowCheckpointTable.admissionSequenceColumn"
