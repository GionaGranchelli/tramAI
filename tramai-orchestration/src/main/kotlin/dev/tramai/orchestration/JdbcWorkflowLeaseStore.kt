package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import java.sql.SQLException
import javax.sql.DataSource

/**
 * JDBC-backed lease store for multi-node workflow ownership.
 *
 * Applications are responsible for supplying a JDBC driver and creating the target table.
 */
class JdbcWorkflowLeaseStore(
    internal val dataSource: DataSource,
    internal val table: JdbcWorkflowLeaseTable = JdbcWorkflowLeaseTable(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : WorkflowLeaseStore, WorkflowLeaseCheckpointFence {
    var persistenceFailureDiagnosticObserver: PersistenceFailureDiagnosticObserver =
        NoOpPersistenceFailureDiagnosticObserver
        internal set

    constructor(
        dataSource: DataSource,
        table: JdbcWorkflowLeaseTable,
        clockMillis: () -> Long,
        observer: PersistenceFailureDiagnosticObserver,
    ) : this(dataSource, table, clockMillis) {
        persistenceFailureDiagnosticObserver = observer
    }

    override suspend fun currentLease(
        workflowName: String,
        workflowId: String,
    ): WorkflowLease? = persistenceBoundary(
        PersistenceResourceKind.LEASE, PersistenceOperation.LOAD, persistenceFailureDiagnosticObserver,
    ) {
        val lease = loadLease(workflowName, workflowId)
        lease?.takeUnless(::isExpired)
    }
    override suspend fun claim(
        workflowName: String,
        workflowId: String,
        ownerId: String,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease {
        validateLeaseIdentityInput(workflowName, workflowId, ownerId, leaseDurationMillis)
        return persistenceBoundary(
            PersistenceResourceKind.LEASE, PersistenceOperation.CLAIM, persistenceFailureDiagnosticObserver,
        ) {
            val lease = newLease(
                workflowName = workflowName,
                workflowId = workflowId,
                ownerId = ownerId,
                checkpointRevision = checkpointRevision,
                leaseDurationMillis = leaseDurationMillis,
            )
            val existing = loadLease(workflowName, workflowId)
            when {
                existing == null -> insertLease(lease)
                !isExpired(existing) -> throw activeLeaseConflict()
                else -> replaceExpiredLease(
                    lease = lease,
                    previous = existing,
                )
            }
        }
    }
    override suspend fun renew(
        lease: WorkflowLease,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease {
        validateLeaseToken(lease)
        require(leaseDurationMillis > 0) { "leaseDurationMillis must be greater than zero" }
        return persistenceBoundary(
            PersistenceResourceKind.LEASE, PersistenceOperation.RENEW, persistenceFailureDiagnosticObserver,
        ) {
            val now = clockMillis()
            val renewedExpiry = now + leaseDurationMillis
            executeJdbcCancellable(dataSource) { conn ->
                conn.prepareStatement(renewSql()).use { statement ->
                    if (checkpointRevision == null) {
                        statement.setNull(1, java.sql.Types.BIGINT)
                    } else {
                        statement.setLong(1, checkpointRevision)
                    }
                    statement.setLong(2, renewedExpiry)
                    statement.setString(3, lease.workflowName)
                    statement.setString(4, lease.workflowId)
                    statement.setString(5, lease.leaseId)
                    statement.setString(6, lease.ownerId)
                    statement.setLong(7, now)
                    val updated = statement.executeUpdate()
                    if (updated == 0) {
                        val existing = loadLease(conn, lease.workflowName, lease.workflowId)
                        throw renewalConflict()
                    }
                }
                // The durable row is authoritative: the caller's lease object
                // is a capability snapshot, never writable lease metadata.
                loadLease(conn, lease.workflowName, lease.workflowId)
                    ?: throw renewalConflict()
            }
        }
    }
    override suspend fun release(lease: WorkflowLease) {
        validateLeaseToken(lease)
        persistenceBoundary(PersistenceResourceKind.LEASE, PersistenceOperation.RELEASE, persistenceFailureDiagnosticObserver) { executeJdbcCancellable(dataSource) { conn ->
            conn.prepareStatement(releaseSql()).use { statement ->
                statement.setString(1, lease.workflowName)
                statement.setString(2, lease.workflowId)
                statement.setString(3, lease.leaseId)
                statement.setString(4, lease.ownerId)
                val deleted = statement.executeUpdate()
                if (deleted == 0) {
                    val existing = loadLease(conn, lease.workflowName, lease.workflowId) ?: return@executeJdbcCancellable
                    if (isExpired(existing)) {
                        deleteExpiredLease(conn, existing)
                        return@executeJdbcCancellable
                    }
                    throw releaseConflict()
                }
            }
        } }
    }

    override suspend fun saveCheckpointIfLeaseOwner(
        checkpointStore: WorkflowCheckpointStore,
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
        expectedLease: WorkflowLease,
    ): WorkflowCheckpoint {
        validateFenceIdentity(expectedLease, checkpoint.workflowName, checkpoint.workflowId)
        // Caller/framework preconditions stay OUTSIDE the boundary: a wrong
        // store type or DataSource mismatch is a caller error (IllegalArgumentException),
        // not a persistence failure (the P2-3 finding). Both fences must agree.
        val jdbcCheckpointStore = checkpointStore as? JdbcWorkflowCheckpointStore
            ?: throw unsupportedFence(checkpointStore)
        require(jdbcCheckpointStore.dataSource === dataSource) {
            "JdbcWorkflowLeaseStore can only fence JdbcWorkflowCheckpointStore instances that share the same DataSource"
        }
        return persistenceBoundary(
            PersistenceResourceKind.LEASE,
            PersistenceOperation.SAVE,
            persistenceFailureDiagnosticObserver,
            checkpointDiagnosticObserver = jdbcCheckpointStore.persistenceFailureDiagnosticObserver,
        ) {
            executeJdbcCancellable(dataSource, transactional = true) { conn ->
                lockLeaseRow(conn, expectedLease)
                // Checkpoint DML belongs to the checkpoint store's diagnostic
                // channel, not the lease store's: mark the raw failure so the outer
                // lease boundary routes it to jdbcCheckpointStore's observer with
                // resourceKind=CHECKPOINT. Lease-row/fence failures stay on the
                // lease channel (exactly one event per failing phase).
                try {
                    jdbcCheckpointStore.saveInConnection(conn, checkpoint, expectedRevision)
                } catch (error: Throwable) {
                    error.rethrowIfCancellation()
                    throw CheckpointDmlFailure(error)
                }
            }
        }
    }

    override suspend fun deleteCheckpointIfLeaseOwner(
        checkpointStore: WorkflowCheckpointStore,
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
        expectedLease: WorkflowLease,
        expectedGeneration: String?,
    ) {
        validateFenceIdentity(expectedLease, workflowName, workflowId)
        val jdbcCheckpointStore = checkpointStore as? JdbcWorkflowCheckpointStore
            ?: throw unsupportedFence(checkpointStore)
        require(jdbcCheckpointStore.dataSource === dataSource) {
            "JdbcWorkflowLeaseStore can only fence JdbcWorkflowCheckpointStore instances that share the same DataSource"
        }
        persistenceBoundary(
            PersistenceResourceKind.LEASE,
            PersistenceOperation.DELETE,
            persistenceFailureDiagnosticObserver,
            checkpointDiagnosticObserver = (checkpointStore as? JdbcWorkflowCheckpointStore)?.persistenceFailureDiagnosticObserver,
        ) { executeJdbcCancellable(dataSource, transactional = true) { conn ->
            lockLeaseRow(conn, expectedLease)
            // Same diagnostic-ownership split as saveCheckpointIfLeaseOwner:
            // checkpoint DML failures go to the checkpoint store's observer.
            try {
                jdbcCheckpointStore.deleteInConnection(
                    conn,
                    workflowName,
                    workflowId,
                    expectedRevision,
                    expectedGeneration,
                )
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                throw CheckpointDmlFailure(error)
            }
        } }
    }
    fun createTableSql(): String = """
        CREATE TABLE ${table.tableName} (
            ${table.workflowNameColumn} VARCHAR(255) NOT NULL,
            ${table.workflowIdColumn} VARCHAR(255) NOT NULL,
            ${table.leaseIdColumn} VARCHAR(255) NOT NULL,
            ${table.ownerIdColumn} VARCHAR(255) NOT NULL,
            ${table.checkpointRevisionColumn} BIGINT NULL,
            ${table.acquiredAtEpochMillisColumn} BIGINT NOT NULL,
            ${table.expiresAtEpochMillisColumn} BIGINT NOT NULL,
            PRIMARY KEY (${table.workflowNameColumn}, ${table.workflowIdColumn})
        )
    """.trimIndent()
    private suspend fun insertLease(lease: WorkflowLease): WorkflowLease {
        executeJdbcCancellable(dataSource) { conn ->
            try {
                conn.prepareStatement(insertSql()).use { statement ->
                    statement.bindLease(lease)
                    statement.executeUpdate()
                }
            } catch (error: SQLException) {
                val current = loadLease(conn, lease.workflowName, lease.workflowId)
                if (current != null && !isExpired(current)) {
                    throw activeLeaseConflict()
                }
                throw error
            }
        }
        return lease
    }
    private suspend fun replaceExpiredLease(
        lease: WorkflowLease,
        previous: WorkflowLease,
    ): WorkflowLease {
        executeJdbcCancellable(dataSource) { conn ->
            conn.prepareStatement(replaceExpiredSql()).use { statement ->
                statement.setString(1, lease.leaseId)
                statement.setString(2, lease.ownerId)
                if (lease.checkpointRevision == null) {
                    statement.setNull(3, java.sql.Types.BIGINT)
                } else {
                    statement.setLong(3, lease.checkpointRevision)
                }
                statement.setLong(4, lease.acquiredAtEpochMillis)
                statement.setLong(5, lease.expiresAtEpochMillis)
                statement.setString(6, previous.workflowName)
                statement.setString(7, previous.workflowId)
                statement.setString(8, previous.leaseId)
                statement.setString(9, previous.ownerId)
                statement.setLong(10, clockMillis())
                val updated = statement.executeUpdate()
                if (updated == 0) {
                    val current = loadLease(conn, lease.workflowName, lease.workflowId)
                    if (current != null && !isExpired(current)) {
                        throw activeLeaseConflict()
                    }
                    if (current == null) {
                        // The expired predecessor row was removed concurrently
                        // (e.g. a no-op release of the already-expired lease).
                        // The key is now free: the claim legally wins by
                        // inserting the new lease, not by reporting conflict.
                        try {
                            conn.prepareStatement(insertSql()).use { insert ->
                                insert.bindLease(lease)
                                insert.executeUpdate()
                            }
                        } catch (error: SQLException) {
                            val raced = loadLease(conn, lease.workflowName, lease.workflowId)
                            if (raced != null && !isExpired(raced)) {
                                throw activeLeaseConflict()
                            }
                            throw error
                        }
                        return@executeJdbcCancellable
                    }
                    throw safePersistenceFailure(PersistenceResourceKind.LEASE, PersistenceOperation.CLAIM, PersistenceFailureCode.CONFLICT)
                }
            }
        }
        return lease
    }
    private suspend fun loadLease(
        workflowName: String,
        workflowId: String,
    ): WorkflowLease? = executeJdbcCancellable(dataSource) { conn ->
        loadLease(conn, workflowName, workflowId)
    }

    private fun loadLease(
        connection: java.sql.Connection,
        workflowName: String,
        workflowId: String,
    ): WorkflowLease? = try {
        connection.prepareStatement(selectSql()).use { statement ->
            statement.setString(1, workflowName)
            statement.setString(2, workflowId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    null
                } else {
                    resultSet.toLease()
                }
            }
        }
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        throw LeaseReadPhaseFailure(error)
    }
    private suspend fun deleteExpiredLease(lease: WorkflowLease) {
        executeJdbcCancellable(dataSource) { conn ->
            conn.prepareStatement(deleteExpiredSql()).use { statement ->
                statement.setString(1, lease.workflowName)
                statement.setString(2, lease.workflowId)
                statement.setLong(3, clockMillis())
                statement.executeUpdate()
            }
        }
    }

    private fun deleteExpiredLease(
        connection: java.sql.Connection,
        lease: WorkflowLease,
    ) {
        connection.prepareStatement(deleteExpiredSql()).use { statement ->
            statement.setString(1, lease.workflowName)
            statement.setString(2, lease.workflowId)
            statement.setLong(3, clockMillis())
            statement.executeUpdate()
        }
    }

    private fun lockLeaseRow(
        connection: java.sql.Connection,
        expectedLease: WorkflowLease,
    ) {
        // A genuine row lock, never a state-mutating UPDATE: the fence must
        // not write expectedLease.checkpointRevision into the durable lease
        // merely as a side effect of checking ownership.
        connection.prepareStatement(lockLeaseSql()).use { statement ->
            statement.setString(1, expectedLease.workflowName)
            statement.setString(2, expectedLease.workflowId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    throw safeStaleWorkflowLeaseFailure()
                }
                val leaseId = resultSet.getString(table.leaseIdColumn)
                val ownerId = resultSet.getString(table.ownerIdColumn)
                val expiresAt = resultSet.getLong(table.expiresAtEpochMillisColumn)
                if (leaseId != expectedLease.leaseId ||
                    ownerId != expectedLease.ownerId ||
                    expiresAt <= clockMillis()
                ) {
                    throw safeStaleWorkflowLeaseFailure()
                }
            }
        }
    }

    private fun unsupportedFence(checkpointStore: WorkflowCheckpointStore): IllegalArgumentException = IllegalArgumentException(
        "JdbcWorkflowLeaseStore can only fence JdbcWorkflowCheckpointStore instances, not ${checkpointStore::class.qualifiedName}",
    )
    private fun activeLeaseConflict(): RuntimeException = safePersistenceFailure(PersistenceResourceKind.LEASE, PersistenceOperation.CLAIM, PersistenceFailureCode.CONFLICT)
    private fun renewalConflict(): RuntimeException = safePersistenceFailure(PersistenceResourceKind.LEASE, PersistenceOperation.RENEW, PersistenceFailureCode.CONFLICT)
    private fun releaseConflict(): RuntimeException = safePersistenceFailure(PersistenceResourceKind.LEASE, PersistenceOperation.RELEASE, PersistenceFailureCode.CONFLICT)
    private fun newLease(
        workflowName: String,
        workflowId: String,
        ownerId: String,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease {
        val now = clockMillis()
        return WorkflowLease(
            workflowName = workflowName,
            workflowId = workflowId,
            leaseId = java.util.UUID.randomUUID().toString(),
            ownerId = ownerId,
            checkpointRevision = checkpointRevision,
            acquiredAtEpochMillis = now,
            expiresAtEpochMillis = now + leaseDurationMillis,
        )
    }
    private fun isExpired(lease: WorkflowLease): Boolean = clockMillis() >= lease.expiresAtEpochMillis
    private fun insertSql(): String = """
        INSERT INTO ${table.tableName} (
            ${table.workflowNameColumn},
            ${table.workflowIdColumn},
            ${table.leaseIdColumn},
            ${table.ownerIdColumn},
            ${table.checkpointRevisionColumn},
            ${table.acquiredAtEpochMillisColumn},
            ${table.expiresAtEpochMillisColumn}
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
    """.trimIndent()
    private fun replaceExpiredSql(): String = """
        UPDATE ${table.tableName}
        SET
            ${table.leaseIdColumn} = ?,
            ${table.ownerIdColumn} = ?,
            ${table.checkpointRevisionColumn} = ?,
            ${table.acquiredAtEpochMillisColumn} = ?,
            ${table.expiresAtEpochMillisColumn} = ?
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
            AND ${table.leaseIdColumn} = ?
            AND ${table.ownerIdColumn} = ?
            AND ${table.expiresAtEpochMillisColumn} <= ?
    """.trimIndent()
    private fun renewSql(): String = """
        UPDATE ${table.tableName}
        SET
            ${table.checkpointRevisionColumn} = ?,
            ${table.expiresAtEpochMillisColumn} = ?
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
            AND ${table.leaseIdColumn} = ?
            AND ${table.ownerIdColumn} = ?
            AND ${table.expiresAtEpochMillisColumn} > ?
    """.trimIndent()
    private fun lockLeaseSql(): String = """
        SELECT
            ${table.leaseIdColumn},
            ${table.ownerIdColumn},
            ${table.expiresAtEpochMillisColumn}
        FROM ${table.tableName}
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
        FOR UPDATE
    """.trimIndent()
    private fun releaseSql(): String = """
        DELETE FROM ${table.tableName}
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
            AND ${table.leaseIdColumn} = ?
            AND ${table.ownerIdColumn} = ?
    """.trimIndent()
    private fun deleteExpiredSql(): String = """
        DELETE FROM ${table.tableName}
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
            AND ${table.expiresAtEpochMillisColumn} <= ?
    """.trimIndent()
    private fun selectSql(): String = """
        SELECT
            ${table.workflowNameColumn},
            ${table.workflowIdColumn},
            ${table.leaseIdColumn},
            ${table.ownerIdColumn},
            ${table.checkpointRevisionColumn},
            ${table.acquiredAtEpochMillisColumn},
            ${table.expiresAtEpochMillisColumn}
        FROM ${table.tableName}
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
    """.trimIndent()
    private fun java.sql.PreparedStatement.bindLease(lease: WorkflowLease) {
        setString(1, lease.workflowName)
        setString(2, lease.workflowId)
        setString(3, lease.leaseId)
        setString(4, lease.ownerId)
        if (lease.checkpointRevision == null) {
            setNull(5, java.sql.Types.BIGINT)
        } else {
            setLong(5, lease.checkpointRevision)
        }
        setLong(6, lease.acquiredAtEpochMillis)
        setLong(7, lease.expiresAtEpochMillis)
    }
    private fun java.sql.ResultSet.toLease(): WorkflowLease = WorkflowLease(
        workflowName = getString(table.workflowNameColumn),
        workflowId = getString(table.workflowIdColumn),
        leaseId = getString(table.leaseIdColumn),
        ownerId = getString(table.ownerIdColumn),
        checkpointRevision = getLong(table.checkpointRevisionColumn).takeUnless { wasNull() },
        acquiredAtEpochMillis = getLong(table.acquiredAtEpochMillisColumn),
        expiresAtEpochMillis = getLong(table.expiresAtEpochMillisColumn),
    )
}
data class JdbcWorkflowLeaseTable(
    val tableName: String = "tramai_workflow_lease",
    val workflowNameColumn: String = "workflow_name",
    val workflowIdColumn: String = "workflow_id",
    val leaseIdColumn: String = "lease_id",
    val ownerIdColumn: String = "owner_id",
    val checkpointRevisionColumn: String = "checkpoint_revision",
    val acquiredAtEpochMillisColumn: String = "acquired_at_epoch_millis",
    val expiresAtEpochMillisColumn: String = "expires_at_epoch_millis",
) {
    init {
        requireValidSqlIdentifier(tableName, "JdbcWorkflowLeaseTable.tableName")
        requireValidSqlIdentifier(workflowNameColumn, "JdbcWorkflowLeaseTable.workflowNameColumn")
        requireValidSqlIdentifier(workflowIdColumn, "JdbcWorkflowLeaseTable.workflowIdColumn")
        requireValidSqlIdentifier(leaseIdColumn, "JdbcWorkflowLeaseTable.leaseIdColumn")
        requireValidSqlIdentifier(ownerIdColumn, "JdbcWorkflowLeaseTable.ownerIdColumn")
        requireValidSqlIdentifier(checkpointRevisionColumn, "JdbcWorkflowLeaseTable.checkpointRevisionColumn")
        requireValidSqlIdentifier(acquiredAtEpochMillisColumn, "JdbcWorkflowLeaseTable.acquiredAtEpochMillisColumn")
        requireValidSqlIdentifier(expiresAtEpochMillisColumn, "JdbcWorkflowLeaseTable.expiresAtEpochMillisColumn")
    }
}
