package dev.tramai.orchestration

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import javax.sql.DataSource

private const val ATTEMPT_SEQUENCE_COLUMN = "attempt_sequence"
private const val SEQUENCE_TABLE = "tramai_attempt_sequence"

class JdbcStepAttemptRecordStore(
    private val dataSource: DataSource,
    private val table: JdbcStepAttemptTable = JdbcStepAttemptTable(),
) : StepAttemptRecordStore {
    var persistenceFailureDiagnosticObserver: PersistenceFailureDiagnosticObserver =
        NoOpPersistenceFailureDiagnosticObserver
        internal set

    constructor(
        dataSource: DataSource,
        table: JdbcStepAttemptTable,
        observer: PersistenceFailureDiagnosticObserver,
    ) : this(dataSource, table) {
        persistenceFailureDiagnosticObserver = observer
    }

    override suspend fun recordStepAttempt(record: StepAttemptRecord): StepAttemptRecord {
        record.requirePersistableIdentity()
        return persistenceBoundary(PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.SAVE, persistenceFailureDiagnosticObserver, ::classifyStepAttemptFailure) {
            executeJdbcCancellable(dataSource, transactional = true) { conn ->
                if (update(conn, record, expected = null) == 0) {
                    val savepoint = conn.setSavepoint()
                    try {
                        insert(conn, record, allocateSequence(conn, record.runId))
                    } catch (error: SQLException) {
                        // A concurrent writer inserted the identity between our UPDATE and the
                        // INSERT. Roll back to the savepoint so the failed INSERT cannot abort
                        // the transaction on strict databases (PostgreSQL), then retry the
                        // update — it preserves the winner's sequence.
                        conn.rollback(savepoint)
                        if (update(conn, record, expected = null) == 0) throw error
                    }
                }
                record
            }
        }
    }

    override suspend fun updateStepAttempt(record: StepAttemptRecord): StepAttemptRecord {
        record.requirePersistableIdentity()
        return persistenceBoundary(PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.SAVE, persistenceFailureDiagnosticObserver, ::classifyStepAttemptFailure) {
            executeJdbcCancellable(dataSource, transactional = true) { conn ->
                if (update(conn, record, expected = null) == 0) {
                    if (!exists(conn, record)) {
                        throw IllegalStateException("Step attempt does not exist")
                    }
                    // A concurrent recordStepAttempt inserted the row between our UPDATE and the
                    // existence check — retry so the update is not silently dropped.
                    update(conn, record, expected = null)
                }
                record
            }
        }
    }

    override suspend fun compareAndSetStepAttempt(
        expected: StepAttemptRecord,
        updated: StepAttemptRecord,
    ): Boolean {
        if (expected.key() != updated.key()) return false
        updated.requirePersistableIdentity()
        return persistenceBoundary(PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.COMPARE_AND_SET, persistenceFailureDiagnosticObserver, ::classifyStepAttemptFailure) {
            executeJdbcCancellable(dataSource, transactional = true) { conn ->
                update(conn, updated, expected) > 0
            }
        }
    }

    override suspend fun latestStepAttempt(runId: String, stepName: String): StepAttemptRecord? {
        require(runId.isNotBlank() && stepName.isNotBlank()) { "Step-attempt runId and stepName must not be blank" }
        return persistenceBoundary(PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.LOAD, persistenceFailureDiagnosticObserver, ::classifyStepAttemptFailure) {
            executeJdbcCancellable(dataSource) { conn ->
                conn.prepareStatement(latestSql()).use { statement ->
                statement.setString(1, runId)
                statement.setString(2, stepName)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toVerifiedRecord() else null
                }
                }
            }
        }
    }

    override suspend fun listStepAttempts(runId: String): List<StepAttemptRecord> {
        require(runId.isNotBlank()) { "Step-attempt runId must not be blank" }
        return persistenceBoundary(PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.LIST, persistenceFailureDiagnosticObserver, ::classifyStepAttemptFailure) {
            executeJdbcCancellable(dataSource) { conn ->
                conn.prepareStatement(listSql()).use { statement ->
                statement.setString(1, runId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.toVerifiedRecord())
                    }
                }
                }
            }
        }
    }

    fun createTableSql(): String = """
        CREATE TABLE ${table.tableName} (
            ${table.runIdColumn} VARCHAR(255) NOT NULL,
            ${table.stepNameColumn} VARCHAR(255) NOT NULL,
            ${table.attemptIdColumn} VARCHAR(255) NOT NULL,
            ${table.workerIdColumn} VARCHAR(255) NOT NULL,
            ${table.leaseTokenColumn} VARCHAR(255) NOT NULL,
            ${table.statusColumn} VARCHAR(64) NOT NULL,
            ${table.startedAtColumn} BIGINT NOT NULL,
            ${table.completedAtColumn} BIGINT NULL,
            ${table.idempotencyKeyColumn} VARCHAR(1024) NULL,
            ${table.replayPolicyColumn} VARCHAR(64) NOT NULL,
            ${table.inputFingerprintColumn} VARCHAR(255) NULL,
            ${table.outputSummaryColumn} TEXT NULL,
            ${table.resolutionReasonColumn} TEXT NULL,
            ${table.resolutionAtEpochMillisColumn} BIGINT NULL,
            ${table.resolutionActionColumn} VARCHAR(64) NULL,
            ${table.approvedIdempotencyKeyColumn} VARCHAR(1024) NULL,
            $ATTEMPT_SEQUENCE_COLUMN BIGINT NOT NULL,
            ${table.recordSchemaVersionColumn} VARCHAR(16) NOT NULL,
            ${table.recordHashColumn} VARCHAR(64) NOT NULL,
            PRIMARY KEY (${table.runIdColumn}, ${table.stepNameColumn}, ${table.attemptIdColumn})
        );
        CREATE TABLE $SEQUENCE_TABLE (
            run_id VARCHAR(255) NOT NULL PRIMARY KEY,
            next_value BIGINT NOT NULL
        )
    """.trimIndent()

    private fun insert(conn: Connection, record: StepAttemptRecord, sequence: Long) {
        conn.prepareStatement(insertSql()).use { statement ->
            statement.bindAll(record, sequence, 1)
            statement.executeUpdate()
        }
    }

    private fun update(conn: Connection, record: StepAttemptRecord, expected: StepAttemptRecord?): Int {
        val sequence = readSequence(conn, record.runId, record.stepName, record.attemptId) ?: return 0
        return conn.prepareStatement(updateSql(expected != null)).use { statement ->
            var index = statement.bindMutable(record, sequence, 1)
            statement.setString(index++, record.runId)
            statement.setString(index++, record.stepName)
            statement.setString(index++, record.attemptId)
            if (expected != null) statement.setString(index, StepAttemptRecordCodec.fingerprint(expected, sequence))
            statement.executeUpdate()
        }
    }

    private fun allocateSequence(conn: Connection, runId: String): Long {
        val updated = conn.prepareStatement(
            "UPDATE $SEQUENCE_TABLE SET next_value = next_value + 1 WHERE run_id = ?",
        ).use { statement ->
            statement.setString(1, runId)
            statement.executeUpdate()
        }
        if (updated == 0) {
            val savepoint = conn.setSavepoint()
            try {
                conn.prepareStatement(
                    "INSERT INTO $SEQUENCE_TABLE (run_id, next_value) VALUES (?, 1)",
                ).use { statement ->
                    statement.setString(1, runId)
                    statement.executeUpdate()
                }
            } catch (_: SQLException) {
                // A racing first-writer inserted the run row. Roll back to the savepoint so
                // the failed INSERT cannot abort the transaction on strict databases
                // (PostgreSQL), then increment the winner's row.
                conn.rollback(savepoint)
                conn.prepareStatement(
                    "UPDATE $SEQUENCE_TABLE SET next_value = next_value + 1 WHERE run_id = ?",
                ).use { statement ->
                    statement.setString(1, runId)
                    statement.executeUpdate()
                }
            }
        }
        return conn.prepareStatement("SELECT next_value FROM $SEQUENCE_TABLE WHERE run_id = ?").use { statement ->
            statement.setString(1, runId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getLong(1)
                else throw IllegalStateException("Step-attempt sequence does not exist")
            }
        }
    }

    private fun readSequence(conn: Connection, runId: String, stepName: String, attemptId: String): Long? =
        conn.prepareStatement(
            "SELECT $ATTEMPT_SEQUENCE_COLUMN FROM ${table.tableName} " +
                "WHERE ${table.runIdColumn} = ? AND ${table.stepNameColumn} = ? AND ${table.attemptIdColumn} = ?",
        ).use { statement ->
            statement.setString(1, runId)
            statement.setString(2, stepName)
            statement.setString(3, attemptId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.nullableLong(ATTEMPT_SEQUENCE_COLUMN)
                else null
            }
        }

    private fun exists(conn: Connection, record: StepAttemptRecord): Boolean =
        conn.prepareStatement(existsSql()).use { statement ->
            statement.setString(1, record.runId)
            statement.setString(2, record.stepName)
            statement.setString(3, record.attemptId)
            statement.executeQuery().use(ResultSet::next)
        }

    private fun insertSql(): String = """
        INSERT INTO ${table.tableName} (${allColumns()})
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent()

    private fun updateSql(compareHash: Boolean): String = buildString {
        append("UPDATE ${table.tableName} SET ")
        append(mutableColumns().joinToString(", ") { "$it = ?" })
        append(" WHERE ${table.runIdColumn} = ? AND ${table.stepNameColumn} = ? AND ${table.attemptIdColumn} = ?")
        if (compareHash) append(" AND ${table.recordHashColumn} = ?")
    }

    private fun existsSql(): String = """
        SELECT ${table.attemptIdColumn} FROM ${table.tableName}
        WHERE ${table.runIdColumn} = ? AND ${table.stepNameColumn} = ? AND ${table.attemptIdColumn} = ?
    """.trimIndent()

    private fun listSql(): String = """
        SELECT ${allColumns()} FROM ${table.tableName}
        WHERE ${table.runIdColumn} = ?
        ORDER BY ${table.startedAtColumn}, ${table.stepNameColumn}, $ATTEMPT_SEQUENCE_COLUMN, ${table.attemptIdColumn}
    """.trimIndent()

    private fun latestSql(): String = """
        SELECT ${allColumns()} FROM ${table.tableName}
        WHERE ${table.runIdColumn} = ? AND ${table.stepNameColumn} = ?
        ORDER BY ${table.startedAtColumn} DESC, $ATTEMPT_SEQUENCE_COLUMN DESC, ${table.attemptIdColumn} DESC
        LIMIT 1
    """.trimIndent()

    private fun allColumns(): String = listOf(
        table.runIdColumn,
        table.stepNameColumn,
        table.attemptIdColumn,
        *mutableColumns().toTypedArray(),
    ).joinToString(", ")

    private fun mutableColumns(): List<String> = listOf(
        ATTEMPT_SEQUENCE_COLUMN,
        table.workerIdColumn,
        table.leaseTokenColumn,
        table.statusColumn,
        table.startedAtColumn,
        table.completedAtColumn,
        table.idempotencyKeyColumn,
        table.replayPolicyColumn,
        table.inputFingerprintColumn,
        table.outputSummaryColumn,
        table.resolutionReasonColumn,
        table.resolutionAtEpochMillisColumn,
        table.resolutionActionColumn,
        table.approvedIdempotencyKeyColumn,
        table.recordSchemaVersionColumn,
        table.recordHashColumn,
    )

    private fun PreparedStatement.bindAll(record: StepAttemptRecord, sequence: Long, start: Int) {
        setString(start, record.runId)
        setString(start + 1, record.stepName)
        setString(start + 2, record.attemptId)
        bindMutable(record, sequence, start + 3)
    }

    private fun PreparedStatement.bindMutable(record: StepAttemptRecord, sequence: Long, start: Int): Int {
        var index = start
        setLong(index++, sequence)
        setString(index++, record.workerId)
        setString(index++, record.leaseToken)
        setString(index++, record.status.name)
        setLong(index++, record.startedAt)
        setNullableLong(index++, record.completedAt)
        setNullableString(index++, record.idempotencyKey)
        setString(index++, record.replayPolicy.name)
        setNullableString(index++, record.inputFingerprint)
        setNullableString(index++, record.outputSummary)
        setNullableString(index++, record.resolutionReason)
        setNullableLong(index++, record.resolutionAtEpochMillis)
        setNullableString(index++, record.resolutionAction?.name)
        setNullableString(index++, record.approvedIdempotencyKey)
        setString(index++, StepAttemptRecordCodec.SCHEMA_VERSION)
        setString(index++, StepAttemptRecordCodec.fingerprint(record, sequence))
        return index
    }

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
    }

    private fun ResultSet.toVerifiedRecord(): StepAttemptRecord {
        val record = try {
            StepAttemptRecord(
                runId = getString(table.runIdColumn) ?: corruptColumn(table.runIdColumn),
                stepName = getString(table.stepNameColumn) ?: corruptColumn(table.stepNameColumn),
                attemptId = getString(table.attemptIdColumn) ?: corruptColumn(table.attemptIdColumn),
                workerId = getString(table.workerIdColumn) ?: corruptColumn(table.workerIdColumn),
                leaseToken = getString(table.leaseTokenColumn) ?: corruptColumn(table.leaseTokenColumn),
                status = strictEnum(getString(table.statusColumn), "StepAttemptStatus", StepAttemptStatus.entries),
                startedAt = nullableLong(table.startedAtColumn) ?: corruptColumn(table.startedAtColumn),
                completedAt = nullableLong(table.completedAtColumn),
                idempotencyKey = getString(table.idempotencyKeyColumn),
                replayPolicy = strictEnum(getString(table.replayPolicyColumn), "ReplayPolicy", ReplayPolicy.entries),
                inputFingerprint = getString(table.inputFingerprintColumn),
                outputSummary = getString(table.outputSummaryColumn),
                resolutionReason = getString(table.resolutionReasonColumn),
                resolutionAtEpochMillis = nullableLong(table.resolutionAtEpochMillisColumn),
                resolutionAction = getString(table.resolutionActionColumn)?.let(::decodeResolutionAction),
                approvedIdempotencyKey = getString(table.approvedIdempotencyKeyColumn),
            )
        } catch (error: CorruptStepAttemptException) {
            throw error
        } catch (error: Exception) {
            throw CorruptStepAttemptException("Persisted step-attempt record is invalid", "JDBC storage", error)
        }
        val storedVersion = getString(table.recordSchemaVersionColumn)
            ?: throw CorruptStepAttemptException("Persisted step-attempt record is invalid", table.recordSchemaVersionColumn)
        if (storedVersion != StepAttemptRecordCodec.SCHEMA_VERSION) {
            throw CorruptStepAttemptException("Unsupported step-attempt schema version", storedVersion)
        }
        val sequence = try {
            nullableLong(ATTEMPT_SEQUENCE_COLUMN) ?: corruptColumn(ATTEMPT_SEQUENCE_COLUMN)
        } catch (error: CorruptStepAttemptException) {
            throw error
        } catch (error: Exception) {
            throw CorruptStepAttemptException("Persisted step-attempt record is invalid", "JDBC storage", error)
        }
        val storedHash = getString(table.recordHashColumn)
            ?: throw CorruptStepAttemptException("Persisted step-attempt record is invalid", table.recordHashColumn)
        StepAttemptRecordCodec.requireValidFingerprint(record, sequence, storedHash, "JDBC storage")
        return record
    }

    private fun corruptColumn(column: String): Nothing =
        throw CorruptStepAttemptException("Persisted step-attempt record is invalid", column)

    private fun ResultSet.nullableLong(column: String): Long? = getObject(column)?.let { value ->
        (value as? Number)?.toLong()
            ?: throw CorruptStepAttemptException("Persisted step-attempt record is invalid", value.toString())
    }

    private fun <E : Enum<E>> strictEnum(value: String?, label: String, entries: List<E>): E =
        entries.firstOrNull { it.name == value }
            ?: throw CorruptStepAttemptException("Unknown $label", value)
}

data class JdbcStepAttemptTable(
    val tableName: String = "tramai_workflow_step_attempt",
    val runIdColumn: String = "run_id",
    val stepNameColumn: String = "step_name",
    val attemptIdColumn: String = "attempt_id",
    val workerIdColumn: String = "worker_id",
    val leaseTokenColumn: String = "lease_token",
    val statusColumn: String = "status",
    val startedAtColumn: String = "started_at",
    val completedAtColumn: String = "completed_at",
    val idempotencyKeyColumn: String = "idempotency_key",
    val replayPolicyColumn: String = "replay_policy",
    val inputFingerprintColumn: String = "input_fingerprint",
    val outputSummaryColumn: String = "output_summary",
    val resolutionReasonColumn: String = "resolution_reason",
    val resolutionAtEpochMillisColumn: String = "resolution_at_epoch_millis",
    val resolutionActionColumn: String = "resolution_action",
    val approvedIdempotencyKeyColumn: String = "approved_idempotency_key",
    val recordSchemaVersionColumn: String = "record_schema_version",
    val recordHashColumn: String = "record_hash",
) {
    init {
        mapOf(
            "tableName" to tableName,
            "runIdColumn" to runIdColumn,
            "stepNameColumn" to stepNameColumn,
            "attemptIdColumn" to attemptIdColumn,
            "workerIdColumn" to workerIdColumn,
            "leaseTokenColumn" to leaseTokenColumn,
            "statusColumn" to statusColumn,
            "startedAtColumn" to startedAtColumn,
            "completedAtColumn" to completedAtColumn,
            "idempotencyKeyColumn" to idempotencyKeyColumn,
            "replayPolicyColumn" to replayPolicyColumn,
            "inputFingerprintColumn" to inputFingerprintColumn,
            "outputSummaryColumn" to outputSummaryColumn,
            "resolutionReasonColumn" to resolutionReasonColumn,
            "resolutionAtEpochMillisColumn" to resolutionAtEpochMillisColumn,
            "resolutionActionColumn" to resolutionActionColumn,
            "approvedIdempotencyKeyColumn" to approvedIdempotencyKeyColumn,
            "recordSchemaVersionColumn" to recordSchemaVersionColumn,
            "recordHashColumn" to recordHashColumn,
        ).forEach { (name, identifier) ->
            requireValidSqlIdentifier(identifier, "JdbcStepAttemptTable.$name")
        }
    }
}

private data class JdbcAttemptKey(val runId: String, val stepName: String, val attemptId: String)

private fun StepAttemptRecord.key(): JdbcAttemptKey = JdbcAttemptKey(runId, stepName, attemptId)
