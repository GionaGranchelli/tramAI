package dev.tramai.orchestration

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

internal data class JdbcWorkflowEffectEvidenceTable(
    val tableName: String = "tramai_workflow_effect_evidence",
)

/**
 * JDBC implementation of [WorkflowEffectEvidenceRecordStore].
 */
internal class JdbcWorkflowEffectEvidenceRecordStore(
    private val dataSource: DataSource,
    private val table: JdbcWorkflowEffectEvidenceTable = JdbcWorkflowEffectEvidenceTable(),
    observer: PersistenceFailureDiagnosticObserver = NoOpPersistenceFailureDiagnosticObserver,
) : WorkflowEffectEvidenceRecordStore {
    var persistenceFailureDiagnosticObserver: PersistenceFailureDiagnosticObserver = observer
        internal set

    fun createSchemaSql(): List<String> =
        listOf(
            """
            CREATE TABLE IF NOT EXISTS ${table.tableName} (
                workflow_name VARCHAR(255) NOT NULL,
                workflow_id VARCHAR(255) NOT NULL,
                checkpoint_generation VARCHAR(255) NOT NULL,
                admission_seq BIGINT NOT NULL,
                step_name VARCHAR(255) NOT NULL,
                step_attempt_id VARCHAR(255) NOT NULL,
                effect_kind VARCHAR(64) NOT NULL,
                state VARCHAR(32) NOT NULL,
                created_at_epoch_millis BIGINT NOT NULL,
                completed_at_epoch_millis BIGINT,
                diagnostic_summary VARCHAR(1024),
                PRIMARY KEY (workflow_name, workflow_id, checkpoint_generation, admission_seq)
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS idx_${table.tableName}_run ON ${table.tableName} (
                workflow_name, workflow_id, admission_seq ASC
            )
            """.trimIndent(),
        )

    override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
        persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT,
            PersistenceOperation.SAVE,
            persistenceFailureDiagnosticObserver,
        ) {
            executeJdbcCancellable(dataSource, transactional = true) { conn ->
                val sql =
                    """
                    INSERT INTO ${table.tableName} (
                        workflow_name, workflow_id, checkpoint_generation, admission_seq,
                        step_name, step_attempt_id, effect_kind, state,
                        created_at_epoch_millis, completed_at_epoch_millis, diagnostic_summary
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                try {
                    conn.prepareStatement(sql).use { stmt ->
                        bindInsert(stmt, record)
                        stmt.executeUpdate()
                    }
                } catch (ex: SQLException) {
                    throw IllegalStateException("Failed to record evidence: duplicate key or database error", ex)
                }
                record
            }
        }

    override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
        persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT,
            PersistenceOperation.SAVE,
            persistenceFailureDiagnosticObserver,
        ) {
            executeJdbcCancellable(dataSource, transactional = true) { conn ->
                val existing =
                    selectForUpdate(conn, record.key)
                        ?: throw IllegalStateException("Evidence record does not exist for key: ${record.key}")
                validateTransition(existing, record)

                val sql =
                    """
                    UPDATE ${table.tableName} SET
                        state = ?,
                        completed_at_epoch_millis = ?,
                        diagnostic_summary = ?
                    WHERE workflow_name = ?
                      AND workflow_id = ?
                      AND checkpoint_generation = ?
                      AND admission_seq = ?
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, record.state.name)
                    if (record.completedAtEpochMillis != null) {
                        stmt.setLong(2, record.completedAtEpochMillis)
                    } else {
                        stmt.setNull(2, java.sql.Types.BIGINT)
                    }
                    stmt.setString(3, record.diagnosticSummary)
                    stmt.setString(4, record.key.workflowName)
                    stmt.setString(5, record.key.workflowId)
                    stmt.setString(6, record.key.checkpointGeneration)
                    stmt.setLong(7, record.key.admissionSeq)
                    val rows = stmt.executeUpdate()
                    check(rows == 1) { "Expected 1 row updated, got $rows" }
                }
                record
            }
        }

    override suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord? =
        persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT,
            PersistenceOperation.LOAD,
            persistenceFailureDiagnosticObserver,
        ) {
            executeJdbcCancellable(dataSource) { conn ->
                val sql =
                    """
                    SELECT workflow_name, workflow_id, checkpoint_generation, admission_seq,
                           step_name, step_attempt_id, effect_kind, state,
                           created_at_epoch_millis, completed_at_epoch_millis, diagnostic_summary
                    FROM ${table.tableName}
                    WHERE workflow_name = ?
                      AND workflow_id = ?
                      AND checkpoint_generation = ?
                      AND admission_seq = ?
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, key.workflowName)
                    stmt.setString(2, key.workflowId)
                    stmt.setString(3, key.checkpointGeneration)
                    stmt.setLong(4, key.admissionSeq)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) readRow(rs) else null
                    }
                }
            }
        }

    override suspend fun listEvidence(
        workflowName: String,
        workflowId: String,
    ): List<WorkflowEffectEvidenceRecord> =
        persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT,
            PersistenceOperation.LIST,
            persistenceFailureDiagnosticObserver,
        ) {
            executeJdbcCancellable(dataSource) { conn ->
                val sql =
                    """
                    SELECT workflow_name, workflow_id, checkpoint_generation, admission_seq,
                           step_name, step_attempt_id, effect_kind, state,
                           created_at_epoch_millis, completed_at_epoch_millis, diagnostic_summary
                    FROM ${table.tableName}
                    WHERE workflow_name = ? AND workflow_id = ?
                    ORDER BY admission_seq ASC
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, workflowName)
                    stmt.setString(2, workflowId)
                    stmt.executeQuery().use { rs ->
                        val result = mutableListOf<WorkflowEffectEvidenceRecord>()
                        while (rs.next()) {
                            result += readRow(rs)
                        }
                        result
                    }
                }
            }
        }

    private fun selectForUpdate(
        conn: Connection,
        key: WorkflowEffectEvidenceKey,
    ): WorkflowEffectEvidenceRecord? {
        val sql =
            """
            SELECT workflow_name, workflow_id, checkpoint_generation, admission_seq,
                   step_name, step_attempt_id, effect_kind, state,
                   created_at_epoch_millis, completed_at_epoch_millis, diagnostic_summary
            FROM ${table.tableName}
            WHERE workflow_name = ?
              AND workflow_id = ?
              AND checkpoint_generation = ?
              AND admission_seq = ?
            FOR UPDATE
            """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, key.workflowName)
            stmt.setString(2, key.workflowId)
            stmt.setString(3, key.checkpointGeneration)
            stmt.setLong(4, key.admissionSeq)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) readRow(rs) else null
            }
        }
    }

    private fun bindInsert(
        stmt: PreparedStatement,
        record: WorkflowEffectEvidenceRecord,
    ) {
        stmt.setString(1, record.key.workflowName)
        stmt.setString(2, record.key.workflowId)
        stmt.setString(3, record.key.checkpointGeneration)
        stmt.setLong(4, record.key.admissionSeq)
        stmt.setString(5, record.stepName)
        stmt.setString(6, record.stepAttemptId)
        stmt.setString(7, record.effectKind)
        stmt.setString(8, record.state.name)
        stmt.setLong(9, record.createdAtEpochMillis)
        if (record.completedAtEpochMillis != null) {
            stmt.setLong(10, record.completedAtEpochMillis)
        } else {
            stmt.setNull(10, java.sql.Types.BIGINT)
        }
        stmt.setString(11, record.diagnosticSummary)
    }

    private fun readRow(rs: ResultSet): WorkflowEffectEvidenceRecord {
        val completedAt = rs.getLong("completed_at_epoch_millis")
        val completedAtOrNull = if (rs.wasNull()) null else completedAt
        return WorkflowEffectEvidenceRecord(
            key =
                WorkflowEffectEvidenceKey(
                    workflowName = rs.getString("workflow_name"),
                    workflowId = rs.getString("workflow_id"),
                    checkpointGeneration = rs.getString("checkpoint_generation"),
                    admissionSeq = rs.getLong("admission_seq"),
                ),
            stepName = rs.getString("step_name"),
            stepAttemptId = rs.getString("step_attempt_id"),
            effectKind = rs.getString("effect_kind"),
            state = WorkflowEffectEvidenceState.valueOf(rs.getString("state")),
            createdAtEpochMillis = rs.getLong("created_at_epoch_millis"),
            completedAtEpochMillis = completedAtOrNull,
            diagnosticSummary = rs.getString("diagnostic_summary"),
        )
    }

    private fun validateTransition(
        existing: WorkflowEffectEvidenceRecord,
        updated: WorkflowEffectEvidenceRecord,
    ) {
        check(existing.key == updated.key) {
            "Evidence key is immutable: expected ${existing.key}, got ${updated.key}"
        }
        check(existing.stepName == updated.stepName) {
            "stepName is immutable: expected '${existing.stepName}', got '${updated.stepName}'"
        }
        check(existing.stepAttemptId == updated.stepAttemptId) {
            "stepAttemptId is immutable: expected '${existing.stepAttemptId}', got '${updated.stepAttemptId}'"
        }
        check(existing.effectKind == updated.effectKind) {
            "effectKind is immutable: expected '${existing.effectKind}', got '${updated.effectKind}'"
        }
        check(existing.createdAtEpochMillis == updated.createdAtEpochMillis) {
            "createdAtEpochMillis is immutable"
        }

        if (existing.state == updated.state) return

        when (existing.state) {
            WorkflowEffectEvidenceState.ADMITTED -> {
                check(
                    updated.state == WorkflowEffectEvidenceState.DISPATCHED ||
                        updated.state == WorkflowEffectEvidenceState.UNCERTAIN,
                ) {
                    "Invalid transition from ADMITTED to ${updated.state}"
                }
            }

            WorkflowEffectEvidenceState.DISPATCHED -> {
                check(
                    updated.state == WorkflowEffectEvidenceState.COMPLETED ||
                        updated.state == WorkflowEffectEvidenceState.UNCERTAIN,
                ) {
                    "Invalid transition from DISPATCHED to ${updated.state}"
                }
            }

            WorkflowEffectEvidenceState.COMPLETED -> {
                error("Terminal state COMPLETED cannot transition to ${updated.state}")
            }

            WorkflowEffectEvidenceState.UNCERTAIN -> {
                error("Terminal state UNCERTAIN cannot transition to ${updated.state}")
            }
        }
    }
}
