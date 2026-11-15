package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.SafeActorIdPolicy
import dev.tramai.orchestration.JdbcWorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowCancellationRequest
import dev.tramai.orchestration.WorkflowCheckpointConflictException
import dev.tramai.spring.sovereign.ops.WorkflowCancellationCommand
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsWorkflowCancellationMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsWorkflowCancellationStore
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import javax.sql.DataSource

/**
 * JDBC-backed atomic workflow cancellation and audit outbox store (0.7.7m).
 *
 * Implements strict transactional atomicity: the workflow checkpoint cancellation CAS and
 * the audit outbox intent are committed within the same database transaction on a single
 * JDBC connection. If any step fails or is refused, the connection rolls back completely.
 */
class JdbcSovereignOpsWorkflowCancellationStore(
    private val dataSource: DataSource,
    private val checkpointStore: JdbcWorkflowCheckpointStore,
    private val payloadCodec: JdbcOpsAuditOutboxPayloadCodec,
    private val clock: Clock = Clock.systemUTC(),
    private val maxReasonLength: Int = 4096,
) : SovereignOpsWorkflowCancellationStore {
    override suspend fun cancelWithAuditIntent(
        command: WorkflowCancellationCommand,
        actor: String,
        actorIssuer: String,
        actorIdentityDigest: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsWorkflowCancellationMutationResult {
        validateInputs(command, actor)

        return dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                // 1. Check if already cancelled
                val existingLifecycle = checkpointStore.lifecycleInConnection(conn, command.workflowName, command.runId)
                if (existingLifecycle != null) {
                    conn.rollback()
                    return SovereignOpsWorkflowCancellationMutationResult(
                        state = existingLifecycle,
                        alreadyCancelled = true,
                    )
                }

                // 2. Load checkpoint and verify revision
                val checkpoint =
                    checkpointStore.load(conn, command.workflowName, command.runId)
                        ?: throw IllegalStateException("tramai-workflow-not-found")

                if (checkpoint.revision != command.expectedRevision) {
                    conn.rollback()
                    throw WorkflowCheckpointConflictException("tramai-workflow-version-conflict")
                }

                // 3. Insert outbox record as PREPARED
                val preparedEncrypted = payloadCodec.encode(encodeOutboxRecord(auditIntent, null))
                insertPreparedOutbox(conn, auditIntent, preparedEncrypted)

                // 4. Execute atomic cancellation CAS on checkpoint row
                val cancellationRequest =
                    WorkflowCancellationRequest(
                        workflowName = command.workflowName,
                        workflowId = command.runId,
                        expectedRevision = command.expectedRevision,
                        expectedGeneration = checkpoint.checkpointGeneration,
                        actor = actor,
                        reason = command.reason,
                        cancelledAtEpochMillis = clock.millis(),
                        uncertain = false, // Checkpoint store automatically evaluates admissionSequence > 0
                    )
                val finalState = checkpointStore.cancelInConnection(conn, cancellationRequest)

                // 5. Update outbox record to PENDING
                val pendingIntent =
                    auditIntent.copy(
                        approvalStatus = finalState.status.wireName,
                        approvalVersion = finalState.revision,
                        status = SovereignOpsAuditOutboxStatus.PENDING,
                    )
                val pendingEncrypted = payloadCodec.encode(encodeOutboxRecord(pendingIntent, null))
                markPreparedOutboxPending(conn, pendingIntent, pendingEncrypted)

                // 6. Commit single atomic transaction
                conn.commit()

                SovereignOpsWorkflowCancellationMutationResult(
                    state = finalState,
                    alreadyCancelled = false,
                )
            } catch (e: Exception) {
                try {
                    conn.rollback()
                } catch (rollbackEx: Exception) {
                    e.addSuppressed(rollbackEx)
                }
                throw mapSqlException(e, auditIntent)
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    private fun validateInputs(
        command: WorkflowCancellationCommand,
        actor: String,
    ) {
        require(command.workflowName.isNotBlank()) { "workflowName must not be blank" }
        require(command.runId.isNotBlank()) { "runId must not be blank" }
        require(command.expectedRevision >= 0) { "expectedRevision must be non-negative" }
        require(command.reason.isNotBlank()) { "reason must not be blank" }
        require(command.reason.length <= maxReasonLength) {
            "Reason exceeds maximum length of $maxReasonLength"
        }
        SafeActorIdPolicy.validateActorId(actor, "actor")
    }

    private fun insertPreparedOutbox(
        conn: Connection,
        record: SovereignOpsAuditOutboxRecord,
        encrypted: JdbcEncryptedAuditOutboxPayload,
    ) {
        val sql =
            """
            INSERT INTO audit_outbox (
                outbox_id, event_key, status, correlation_key_hash,
                created_at, attempt_count,
                encrypted_payload, encryption_key_id, encryption_algorithm,
                encryption_nonce, payload_digest, version
            ) VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, 1)
            """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, record.outboxId)
            stmt.setString(2, record.eventKey)
            stmt.setString(3, record.status.name)
            stmt.setString(4, record.aggregateIdDigest)
            stmt.setTimestamp(5, Timestamp.from(record.createdAt))
            stmt.setBytes(6, encrypted.ciphertext)
            stmt.setString(7, encrypted.keyId)
            stmt.setString(8, encrypted.algorithm)
            stmt.setBytes(9, encrypted.nonce)
            stmt.setString(10, encrypted.payloadDigest)
            stmt.executeUpdate()
        }
    }

    private fun markPreparedOutboxPending(
        conn: Connection,
        record: SovereignOpsAuditOutboxRecord,
        encrypted: JdbcEncryptedAuditOutboxPayload,
    ) {
        val sql =
            """
            UPDATE audit_outbox
            SET status = 'PENDING',
                encrypted_payload = ?,
                encryption_key_id = ?,
                encryption_algorithm = ?,
                encryption_nonce = ?,
                payload_digest = ?,
                version = version + 1
            WHERE outbox_id = ? AND status = 'PREPARED'
            """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setBytes(1, encrypted.ciphertext)
            stmt.setString(2, encrypted.keyId)
            stmt.setString(3, encrypted.algorithm)
            stmt.setBytes(4, encrypted.nonce)
            stmt.setString(5, encrypted.payloadDigest)
            stmt.setString(6, record.outboxId)
            val updated = stmt.executeUpdate()
            check(updated == 1) { "tramai-sovereign-ops-outbox-concurrent-update" }
        }
    }

    private fun mapSqlException(
        e: Exception,
        record: SovereignOpsAuditOutboxRecord,
    ): Exception {
        if (e !is SQLException) return e
        val message = e.message?.lowercase() ?: ""
        return when {
            message.contains("uq_audit_outbox_event_key") || message.contains("audit_outbox_event_key_key") -> {
                IllegalStateException("tramai-sovereign-ops-outbox-duplicate-event-key: ${record.eventKey}", e)
            }

            message.contains("audit_outbox_pkey") -> {
                IllegalStateException("tramai-sovereign-ops-outbox-duplicate-id: ${record.outboxId}", e)
            }

            else -> {
                e
            }
        }
    }
}
