package dev.tramai.orchestration

import java.sql.Connection

/** `admitSql` binds (increment precondition, workflow name, workflow id). */
private const val ADMIT_WORKFLOW_ID_PARAMETER = 3

/** `admissionSelectForUpdateSql` binds (workflow name, workflow id, revision, generation). */
private const val SELECT_REVISION_PARAMETER = 3
private const val SELECT_GENERATION_PARAMETER = 4

/**
 * The durable admission counter for one checkpoint table.
 *
 * A grant must be proven by its own write, so the locked read, the increment and the affected-row assertion live
 * together: a receipt is the increment this caller made, never a value observed afterwards.
 */
internal class JdbcAdmissionCounter(
    private val table: JdbcWorkflowCheckpointTable,
    private val lifecycleOf: (Connection, String, String) -> WorkflowLifecycleState?,
) {
    internal fun admitInConnection(
        connection: java.sql.Connection,
        request: WorkflowAdmissionRequest,
    ): WorkflowAdmission {
        // The receipt must be this grant's own increment. Reading admission_seq back after an UPDATE in autocommit
        // lets a concurrent admission increment first, and two grants then return the same sequence — a permit
        // identity that is not unique. So the counter is read under a row lock inside one transaction, and the
        // receipt is that read plus one: never an observation of somebody else's write.
        val observed = admissionSelectForUpdateInConnection(connection, request)
        if (observed == null) {
            // Refused: the row is cancelled, or the preconditions no longer hold.
            lifecycleOf(connection, request.workflowName, request.workflowId)?.let { cancellation ->
                return WorkflowAdmission(false, admissionSequenceInConnection(connection, request), cancellation)
            }
            throw safePersistenceFailure(
                PersistenceResourceKind.CHECKPOINT,
                PersistenceOperation.COMPARE_AND_SET,
                PersistenceFailureCode.CONFLICT,
            )
        }
        val changed =
            connection.prepareStatement(admitSql()).use { statement ->
                statement.setLong(1, observed)
                statement.setString(2, request.workflowName)
                statement.setString(ADMIT_WORKFLOW_ID_PARAMETER, request.workflowId)
                statement.executeUpdate()
            }
        // A granted admission is proof of a durable increment. If the write did not take, there is no grant to
        // report: returning admitted = true here would hand out a permit for a sequence that does not exist.
        if (changed != 1) {
            throw safePersistenceFailure(
                PersistenceResourceKind.CHECKPOINT,
                PersistenceOperation.COMPARE_AND_SET,
                PersistenceFailureCode.CONFLICT,
            )
        }
        return WorkflowAdmission(true, observed + 1L)
    }

    /**
     * The counter under a row lock, or null when the run cannot be admitted.
     *
     * The supplied connection must already be inside a transaction: outside one the lock dies with the statement and
     * two callers could read the same value. `JdbcWorkflowCheckpointStore.admit()` is the caller that opens it, and
     * this read decides nothing about lifecycle on its own -- it only refuses when the row is gone or cancelled.
     */
    private fun admissionSelectForUpdateInConnection(
        connection: java.sql.Connection,
        request: WorkflowAdmissionRequest,
    ): Long? =
        connection.prepareStatement(admissionSelectForUpdateSql(request.expectedGeneration != null)).use { statement ->
            statement.setString(1, request.workflowName)
            statement.setString(2, request.workflowId)
            statement.setLong(SELECT_REVISION_PARAMETER, request.expectedRevision)
            if (request.expectedGeneration != null) {
                statement.setString(SELECT_GENERATION_PARAMETER, request.expectedGeneration)
            }
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) null else resultSet.getLong(table.admissionSequenceColumn)
            }
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
    private fun admissionSelectForUpdateSql(generationAware: Boolean): String =
        """
        SELECT ${table.admissionSequenceColumn}
        FROM ${table.tableName}
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
            AND ${table.revisionColumn} = ?
            AND ${if (generationAware) "${table.checkpointGenerationColumn} = ?" else "${table.checkpointGenerationColumn} IS NULL"}
            AND ${table.lifecycleStatusColumn} IS NULL
        FOR UPDATE
        """.trimIndent()

    private fun admitSql(): String =
        """
        UPDATE ${table.tableName}
        SET ${table.admissionSequenceColumn} = ${table.admissionSequenceColumn} + 1
        WHERE ${table.admissionSequenceColumn} = ?
            AND ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
            AND ${table.lifecycleStatusColumn} IS NULL
        """.trimIndent()

    private fun admissionSequenceSelectSql(): String =
        """
        SELECT ${table.admissionSequenceColumn}
        FROM ${table.tableName}
        WHERE ${table.workflowNameColumn} = ?
            AND ${table.workflowIdColumn} = ?
        """.trimIndent()
}
