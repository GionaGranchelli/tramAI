package dev.tramai.orchestration
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
    override suspend fun currentLease(
        workflowName: String,
        workflowId: String,
    ): WorkflowLease? {
        val lease = loadLease(workflowName, workflowId) ?: return null
        return lease.takeUnless(::isExpired)
    }
    override suspend fun claim(
        workflowName: String,
        workflowId: String,
        ownerId: String,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease {
        val lease = newLease(
            workflowName = workflowName,
            workflowId = workflowId,
            ownerId = ownerId,
            checkpointRevision = checkpointRevision,
            leaseDurationMillis = leaseDurationMillis,
        )
        val existing = loadLease(workflowName, workflowId)
        return when {
            existing == null -> insertLease(lease)
            !isExpired(existing) -> throw activeLeaseConflict(existing)
            else -> replaceExpiredLease(
                lease = lease,
                previous = existing,
            )
        }
    }
    override suspend fun renew(
        lease: WorkflowLease,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease {
        val now = clockMillis()
        val renewed = lease.copy(
            checkpointRevision = checkpointRevision,
            expiresAtEpochMillis = now + leaseDurationMillis,
        )
        dataSource.connection.use { connection ->
            connection.prepareStatement(renewSql()).use { statement ->
                if (checkpointRevision == null) {
                    statement.setNull(1, java.sql.Types.BIGINT)
                } else {
                    statement.setLong(1, checkpointRevision)
                }
                statement.setLong(2, renewed.expiresAtEpochMillis)
                statement.setString(3, lease.workflowName)
                statement.setString(4, lease.workflowId)
                statement.setString(5, lease.leaseId)
                statement.setString(6, lease.ownerId)
                statement.setLong(7, now)
                val updated = statement.executeUpdate()
                if (updated == 0) {
                    val existing = loadLease(lease.workflowName, lease.workflowId)
                    throw renewalConflict(
                        attempted = lease,
                        existing = existing,
                    )
                }
            }
        }
        return renewed
    }
    override suspend fun release(lease: WorkflowLease) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(releaseSql()).use { statement ->
                statement.setString(1, lease.workflowName)
                statement.setString(2, lease.workflowId)
                statement.setString(3, lease.leaseId)
                statement.setString(4, lease.ownerId)
                val deleted = statement.executeUpdate()
                if (deleted == 0) {
                    val existing = loadLease(lease.workflowName, lease.workflowId) ?: return
                    if (isExpired(existing)) {
                        deleteExpiredLease(existing)
                        return
                    }
                    throw releaseConflict(
                        attempted = lease,
                        existing = existing,
                    )
                }
            }
        }
    }

    override suspend fun saveCheckpointIfLeaseOwner(
        checkpointStore: WorkflowCheckpointStore,
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
        expectedLease: WorkflowLease,
    ): WorkflowCheckpoint {
        val jdbcCheckpointStore = checkpointStore as? JdbcWorkflowCheckpointStore
            ?: throw unsupportedFence(checkpointStore)
        require(jdbcCheckpointStore.dataSource === dataSource) {
            "JdbcWorkflowLeaseStore can only fence JdbcWorkflowCheckpointStore instances that share the same DataSource"
        }
        return withTransaction { connection ->
            lockLeaseRow(connection, expectedLease)
            jdbcCheckpointStore.saveInConnection(connection, checkpoint, expectedRevision)
        }
    }

    override suspend fun deleteCheckpointIfLeaseOwner(
        checkpointStore: WorkflowCheckpointStore,
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
        expectedLease: WorkflowLease,
    ) {
        val jdbcCheckpointStore = checkpointStore as? JdbcWorkflowCheckpointStore
            ?: throw unsupportedFence(checkpointStore)
        require(jdbcCheckpointStore.dataSource === dataSource) {
            "JdbcWorkflowLeaseStore can only fence JdbcWorkflowCheckpointStore instances that share the same DataSource"
        }
        withTransaction { connection ->
            lockLeaseRow(connection, expectedLease)
            jdbcCheckpointStore.deleteInConnection(connection, workflowName, workflowId, expectedRevision)
        }
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
        dataSource.connection.use { connection ->
            try {
                connection.prepareStatement(insertSql()).use { statement ->
                    statement.bindLease(lease)
                    statement.executeUpdate()
                }
            } catch (error: SQLException) {
                val current = loadLease(lease.workflowName, lease.workflowId)
                if (current != null && !isExpired(current)) {
                    throw activeLeaseConflict(current)
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
        dataSource.connection.use { connection ->
            connection.prepareStatement(replaceExpiredSql()).use { statement ->
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
                    val current = loadLease(lease.workflowName, lease.workflowId)
                    if (current != null && !isExpired(current)) {
                        throw activeLeaseConflict(current)
                    }
                    throw WorkflowLeaseConflictException(
                        "Workflow '${lease.workflowName}' and workflowId='${lease.workflowId}' could not replace its expired lease atomically",
                    )
                }
            }
        }
        return lease
    }
    private suspend fun loadLease(
        workflowName: String,
        workflowId: String,
    ): WorkflowLease? = dataSource.connection.use { connection ->
        loadLease(connection, workflowName, workflowId)
    }

    private fun loadLease(
        connection: java.sql.Connection,
        workflowName: String,
        workflowId: String,
    ): WorkflowLease? = connection.prepareStatement(selectSql()).use { statement ->
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
    private suspend fun deleteExpiredLease(lease: WorkflowLease) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(deleteExpiredSql()).use { statement ->
                statement.setString(1, lease.workflowName)
                statement.setString(2, lease.workflowId)
                statement.setLong(3, clockMillis())
                statement.executeUpdate()
            }
        }
    }

    private fun lockLeaseRow(
        connection: java.sql.Connection,
        expectedLease: WorkflowLease,
    ) {
        connection.prepareStatement(lockLeaseSql()).use { statement ->
            if (expectedLease.checkpointRevision == null) {
                statement.setNull(1, java.sql.Types.BIGINT)
            } else {
                statement.setLong(1, expectedLease.checkpointRevision)
            }
            statement.setString(2, expectedLease.workflowName)
            statement.setString(3, expectedLease.workflowId)
            statement.setString(4, expectedLease.leaseId)
            statement.setString(5, expectedLease.ownerId)
            statement.setLong(6, clockMillis())
            val updated = statement.executeUpdate()
            if (updated == 0) {
                val existing = loadLease(connection, expectedLease.workflowName, expectedLease.workflowId)
                throw when {
                    existing == null || isExpired(existing) -> StaleWorkflowLeaseException(
                        "Workflow '${expectedLease.workflowName}' and workflowId='${expectedLease.workflowId}' lease '${expectedLease.leaseId}' is no longer active",
                    )
                    else -> StaleWorkflowLeaseException(
                        "Workflow '${expectedLease.workflowName}' and workflowId='${expectedLease.workflowId}' is now fenced by lease '${existing.leaseId}' owned by '${existing.ownerId}'",
                    )
                }
            }
        }
    }

    private fun <T> withTransaction(block: (java.sql.Connection) -> T): T = dataSource.connection.use { connection ->
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun unsupportedFence(checkpointStore: WorkflowCheckpointStore): IllegalArgumentException = IllegalArgumentException(
        "JdbcWorkflowLeaseStore can only fence JdbcWorkflowCheckpointStore instances, not ${checkpointStore::class.qualifiedName}",
    )
    private fun activeLeaseConflict(existing: WorkflowLease): WorkflowLeaseConflictException =
        WorkflowLeaseConflictException(
            "Workflow '${existing.workflowName}' and workflowId='${existing.workflowId}' is already leased by owner '${existing.ownerId}' until ${existing.expiresAtEpochMillis}",
        )
    private fun renewalConflict(
        attempted: WorkflowLease,
        existing: WorkflowLease?,
    ): WorkflowLeaseConflictException = when {
        existing == null -> WorkflowLeaseConflictException(
            "Workflow '${attempted.workflowName}' and workflowId='${attempted.workflowId}' has no active lease to renew",
        )
        isExpired(existing) -> WorkflowLeaseConflictException(
            "Workflow '${attempted.workflowName}' and workflowId='${attempted.workflowId}' lease has expired before renewal",
        )
        else -> WorkflowLeaseConflictException(
            "Workflow '${attempted.workflowName}' and workflowId='${attempted.workflowId}' is leased by owner '${existing.ownerId}', not '${attempted.ownerId}'",
        )
    }
    private fun releaseConflict(
        attempted: WorkflowLease,
        existing: WorkflowLease,
    ): WorkflowLeaseConflictException = WorkflowLeaseConflictException(
        "Workflow '${attempted.workflowName}' and workflowId='${attempted.workflowId}' is leased by owner '${existing.ownerId}', not '${attempted.ownerId}'",
    )
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
        UPDATE ${table.tableName}
        SET ${table.checkpointRevisionColumn} = ?
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
            AND ${table.leaseIdColumn} = ?
            AND ${table.ownerIdColumn} = ?
            AND ${table.expiresAtEpochMillisColumn} > ?
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
)
