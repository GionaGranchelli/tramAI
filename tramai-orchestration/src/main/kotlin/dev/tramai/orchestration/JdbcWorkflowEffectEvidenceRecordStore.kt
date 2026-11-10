package dev.tramai.orchestration

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

internal data class JdbcWorkflowEffectEvidenceTable(
    val tableName: String = "tramai_workflow_effect_evidence",
) {
    init {
        requireValidSqlIdentifier(tableName, "JdbcWorkflowEffectEvidenceTable.tableName")
    }
}

private const val PARAM_1 = 1
private const val PARAM_2 = 2
private const val PARAM_3 = 3
private const val PARAM_4 = 4
private const val PARAM_5 = 5
private const val PARAM_6 = 6
private const val PARAM_7 = 7
private const val PARAM_8 = 8
private const val PARAM_9 = 9
private const val PARAM_10 = 10
private const val PARAM_11 = 11
private const val PARAM_12 = 12
private const val PARAM_13 = 13
private const val PARAM_14 = 14

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
                step_attempt_id VARCHAR(255),
                effect_kind VARCHAR(64) NOT NULL,
                state VARCHAR(32) NOT NULL,
                created_at_epoch_millis BIGINT NOT NULL,
                completed_at_epoch_millis BIGINT,
                diagnostic_summary VARCHAR(1024),
                parent_kind VARCHAR(32) NOT NULL DEFAULT 'STEP_ATTEMPT',
                parent_id VARCHAR(512) NOT NULL,
                parent_version BIGINT,
                PRIMARY KEY (workflow_name, workflow_id, checkpoint_generation, admission_seq)
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS idx_${table.tableName}_run ON ${table.tableName} (
                workflow_name, workflow_id, admission_seq ASC
            )
            """.trimIndent(),
        )

    fun migrationPhase1Sql(): List<String> =
        listOf(
            "ALTER TABLE ${table.tableName} " +
                "ADD COLUMN parent_kind VARCHAR(32) NOT NULL DEFAULT 'STEP_ATTEMPT'",
            "ALTER TABLE ${table.tableName} ADD COLUMN parent_id VARCHAR(512) NULL",
            "ALTER TABLE ${table.tableName} ADD COLUMN parent_version BIGINT NULL",
            "ALTER TABLE ${table.tableName} ALTER COLUMN step_attempt_id DROP NOT NULL",
        )

    fun migrationPhase2BackfillSql(): String =
        """
        UPDATE ${table.tableName}
        SET parent_id = step_attempt_id
        WHERE parent_id IS NULL AND parent_kind = 'STEP_ATTEMPT'
        """.trimIndent()

    fun migrationPhase4EnforceConstraintSql(): String =
        "ALTER TABLE ${table.tableName} " +
            "ALTER COLUMN parent_id SET NOT NULL"

    override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.SAVE,
            persistenceFailureDiagnosticObserver,
        ) {
            executeJdbcCancellable(dataSource, transactional = true) { conn ->
                val sql =
                    """
                    INSERT INTO ${table.tableName} (
                        workflow_name, workflow_id, checkpoint_generation, admission_seq,
                        step_name, step_attempt_id, effect_kind, state,
                        created_at_epoch_millis, completed_at_epoch_millis, diagnostic_summary,
                        parent_kind, parent_id, parent_version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.SAVE,
            persistenceFailureDiagnosticObserver,
        ) {
            executeJdbcCancellable(dataSource, transactional = true) { conn ->
                val existing =
                    checkNotNull(selectForUpdate(conn, record.key)) {
                        "Evidence record does not exist for key: ${record.key}"
                    }
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
                    stmt.setString(PARAM_1, record.state.name)
                    if (record.completedAtEpochMillis != null) {
                        stmt.setLong(PARAM_2, record.completedAtEpochMillis)
                    } else {
                        stmt.setNull(PARAM_2, java.sql.Types.BIGINT)
                    }
                    stmt.setString(PARAM_3, record.diagnosticSummary)
                    stmt.setString(PARAM_4, record.key.workflowName)
                    stmt.setString(PARAM_5, record.key.workflowId)
                    stmt.setString(PARAM_6, record.key.checkpointGeneration)
                    stmt.setLong(PARAM_7, record.key.admissionSeq)
                    val rows = stmt.executeUpdate()
                    check(rows == 1) { "Expected 1 row updated, got $rows" }
                }
                record
            }
        }

    override suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord? =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.LOAD,
            persistenceFailureDiagnosticObserver,
        ) {
            executeJdbcCancellable(dataSource) { conn ->
                val sql =
                    """
                    SELECT workflow_name, workflow_id, checkpoint_generation, admission_seq,
                           step_name, step_attempt_id, effect_kind, state,
                           created_at_epoch_millis, completed_at_epoch_millis, diagnostic_summary,
                           parent_kind, parent_id, parent_version
                    FROM ${table.tableName}
                    WHERE workflow_name = ?
                      AND workflow_id = ?
                      AND checkpoint_generation = ?
                      AND admission_seq = ?
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(PARAM_1, key.workflowName)
                    stmt.setString(PARAM_2, key.workflowId)
                    stmt.setString(PARAM_3, key.checkpointGeneration)
                    stmt.setLong(PARAM_4, key.admissionSeq)
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
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.LIST,
            persistenceFailureDiagnosticObserver,
        ) {
            executeJdbcCancellable(dataSource) { conn ->
                val sql =
                    """
                    SELECT workflow_name, workflow_id, checkpoint_generation, admission_seq,
                           step_name, step_attempt_id, effect_kind, state,
                           created_at_epoch_millis, completed_at_epoch_millis, diagnostic_summary,
                           parent_kind, parent_id, parent_version
                    FROM ${table.tableName}
                    WHERE workflow_name = ? AND workflow_id = ?
                    ORDER BY admission_seq ASC
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(PARAM_1, workflowName)
                    stmt.setString(PARAM_2, workflowId)
                    stmt.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(readRow(rs))
                            }
                        }
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
                   created_at_epoch_millis, completed_at_epoch_millis, diagnostic_summary,
                   parent_kind, parent_id, parent_version
            FROM ${table.tableName}
            WHERE workflow_name = ?
              AND workflow_id = ?
              AND checkpoint_generation = ?
              AND admission_seq = ?
            FOR UPDATE
            """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(PARAM_1, key.workflowName)
            stmt.setString(PARAM_2, key.workflowId)
            stmt.setString(PARAM_3, key.checkpointGeneration)
            stmt.setLong(PARAM_4, key.admissionSeq)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) readRow(rs) else null
            }
        }
    }
}

private fun bindInsert(
    stmt: PreparedStatement,
    record: WorkflowEffectEvidenceRecord,
) {
    stmt.setString(PARAM_1, record.key.workflowName)
    stmt.setString(PARAM_2, record.key.workflowId)
    stmt.setString(PARAM_3, record.key.checkpointGeneration)
    stmt.setLong(PARAM_4, record.key.admissionSeq)
    stmt.setString(PARAM_5, record.stepName)
    when (val parent = record.parent) {
        is ParentProvenance.StepAttempt -> {
            stmt.setString(PARAM_6, parent.stepAttemptId)
            stmt.setString(PARAM_7, record.effectKind)
            stmt.setString(PARAM_8, record.state.name)
            stmt.setLong(PARAM_9, record.createdAtEpochMillis)
            if (record.completedAtEpochMillis != null) {
                stmt.setLong(PARAM_10, record.completedAtEpochMillis)
            } else {
                stmt.setNull(PARAM_10, java.sql.Types.BIGINT)
            }
            stmt.setString(PARAM_11, record.diagnosticSummary)
            stmt.setString(PARAM_12, "STEP_ATTEMPT")
            stmt.setString(PARAM_13, parent.stepAttemptId)
            stmt.setNull(PARAM_14, java.sql.Types.BIGINT)
        }

        is ParentProvenance.ApprovalContinuation -> {
            stmt.setNull(PARAM_6, java.sql.Types.VARCHAR)
            stmt.setString(PARAM_7, record.effectKind)
            stmt.setString(PARAM_8, record.state.name)
            stmt.setLong(PARAM_9, record.createdAtEpochMillis)
            if (record.completedAtEpochMillis != null) {
                stmt.setLong(PARAM_10, record.completedAtEpochMillis)
            } else {
                stmt.setNull(PARAM_10, java.sql.Types.BIGINT)
            }
            stmt.setString(PARAM_11, record.diagnosticSummary)
            stmt.setString(PARAM_12, "APPROVAL_CONTINUATION")
            stmt.setString(PARAM_13, parent.approvalId)
            stmt.setLong(PARAM_14, parent.continuationVersion)
        }
    }
}

private fun readRow(rs: ResultSet): WorkflowEffectEvidenceRecord {
    val completedAt = rs.getLong("completed_at_epoch_millis")
    val completedAtOrNull = if (rs.wasNull()) null else completedAt
    val stepName = rs.getString("step_name")
    val stepAttemptId = rs.getString("step_attempt_id")
    val parent = resolveEvidenceParent(rs, stepName, stepAttemptId)

    return WorkflowEffectEvidenceRecord(
        key =
            WorkflowEffectEvidenceKey(
                workflowName = rs.getString("workflow_name"),
                workflowId = rs.getString("workflow_id"),
                checkpointGeneration = rs.getString("checkpoint_generation"),
                admissionSeq = rs.getLong("admission_seq"),
            ),
        parent = parent,
        effectKind = rs.getString("effect_kind"),
        state = WorkflowEffectEvidenceState.valueOf(rs.getString("state")),
        createdAtEpochMillis = rs.getLong("created_at_epoch_millis"),
        completedAtEpochMillis = completedAtOrNull,
        diagnosticSummary = rs.getString("diagnostic_summary"),
    )
}

private fun resolveEvidenceParent(
    rs: ResultSet,
    stepName: String,
    stepAttemptId: String?,
): ParentProvenance {
    val parentKind =
        try {
            rs.getString("parent_kind")
        } catch (
            @Suppress("SwallowedException") ex: SQLException,
        ) {
            null
        } ?: "STEP_ATTEMPT"

    val parentId =
        try {
            rs.getString("parent_id")
        } catch (
            @Suppress("SwallowedException") ex: SQLException,
        ) {
            null
        }

    val parentVersion =
        try {
            val v = rs.getLong("parent_version")
            if (rs.wasNull()) null else v
        } catch (
            @Suppress("SwallowedException") ex: SQLException,
        ) {
            null
        }

    return when (parentKind) {
        "APPROVAL_CONTINUATION" -> {
            check(stepAttemptId == null) {
                "Contradictory parent provenance: APPROVAL_CONTINUATION record " +
                    "has non-null step_attempt_id '$stepAttemptId'"
            }
            checkNotNull(parentId) { "parent_id must not be null for APPROVAL_CONTINUATION" }
            checkNotNull(parentVersion) { "parent_version must not be null for APPROVAL_CONTINUATION" }
            ParentProvenance.ApprovalContinuation(
                approvalId = parentId,
                continuationVersion = parentVersion,
                toolName = stepName,
            )
        }

        "STEP_ATTEMPT" -> {
            if (parentId != null && stepAttemptId != null) {
                check(parentId == stepAttemptId) {
                    "Contradictory STEP_ATTEMPT parentage: parent_id '$parentId' != step_attempt_id '$stepAttemptId'"
                }
            }
            val effectiveAttemptId = parentId ?: stepAttemptId
            checkNotNull(effectiveAttemptId) { "Both parent_id and step_attempt_id are null for STEP_ATTEMPT" }
            ParentProvenance.StepAttempt(
                stepName = stepName,
                stepAttemptId = effectiveAttemptId,
            )
        }

        else -> {
            error("Unknown parent_kind '$parentKind'")
        }
    }
}

private fun validateTransition(
    existing: WorkflowEffectEvidenceRecord,
    updated: WorkflowEffectEvidenceRecord,
) {
    check(existing.key == updated.key) {
        "Evidence key is immutable: expected ${existing.key}, got ${updated.key}"
    }
    check(existing.parent == updated.parent) {
        "parent is immutable: expected '${existing.parent}', got '${updated.parent}'"
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

    if (existing.state == updated.state) {
        check(existing == updated) {
            "Evidence record in state ${existing.state} is immutable: fields cannot be altered"
        }
        return
    }

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
