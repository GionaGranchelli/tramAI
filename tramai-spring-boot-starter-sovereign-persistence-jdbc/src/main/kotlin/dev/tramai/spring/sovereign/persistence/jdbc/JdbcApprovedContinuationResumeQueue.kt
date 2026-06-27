package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueue
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkItem
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * JDBC-backed [ApprovedContinuationResumeQueue] that uses
 * `SELECT ... FOR UPDATE SKIP LOCKED` to safely claim resumable items
 * under concurrent workers.
 *
 * The claim writes `claimed_by`, `claimed_at` into the continuation row.
 * If the continuation already has a claim (claimed_by is not null), it is
 * skipped — the worker first acquired the database-level lock and then
 * verified the claim is still available.
 *
 * @param dataSource PostgreSQL DataSource.
 * @param clock clock for temporal checks.
 */
class JdbcApprovedContinuationResumeQueue(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovedContinuationResumeQueue {

    override suspend fun claimApprovedPending(
        workerId: String,
        limit: Int,
        leaseUntil: Instant,
    ): List<ApprovedContinuationResumeWorkItem> =
        dataSource.connection.use { conn ->
            val leaseUntilOdt = leaseUntil.atOffset(ZoneOffset.UTC)
            val nowOdt = clock.instant().atOffset(ZoneOffset.UTC)
            val selectSql = """
                SELECT a.approval_id, a.version AS approval_version,
                       c.version AS continuation_version, c.workflow_run_id
                FROM approvals a
                JOIN approval_continuations c ON c.approval_id = a.approval_id
                JOIN tramai_approval_resume_credentials rc ON rc.approval_id = a.approval_id
                WHERE a.status = 'APPROVED'
                  AND c.status = 'PENDING'
                  AND c.claimed_by IS NULL
                  AND c.claimed_at IS NULL
                  AND c.approval_expires_at > ?
                  AND rc.expires_at > ?
                ORDER BY c.approval_expires_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            """.trimIndent()

            val items = mutableListOf<ApprovedContinuationResumeWorkItem>()
            conn.prepareStatement(selectSql).use { stmt ->
                stmt.setObject(1, nowOdt)
                stmt.setObject(2, nowOdt)
                stmt.setInt(3, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        items += ApprovedContinuationResumeWorkItem(
                            approvalId = ApprovalId(rs.getString("approval_id")),
                            approvalVersion = rs.getLong("approval_version"),
                            continuationVersion = rs.getLong("continuation_version"),
                            workflowRunId = rs.getString("workflow_run_id"),
                        )
                    }
                }
            }

            if (items.isEmpty()) return@use emptyList()

            // Claim each item by updating claimed_by + claimed_at
            val updateSql = """
                UPDATE approval_continuations
                SET claimed_by = ?, claimed_at = ?, version = version + 1
                WHERE approval_id = ?
                  AND status = 'PENDING'
                  AND claimed_by IS NULL
                  AND claimed_at IS NULL
            """.trimIndent()

            val claimed = mutableListOf<ApprovedContinuationResumeWorkItem>()
            conn.prepareStatement(updateSql).use { stmt ->
                for (item in items) {
                    stmt.setString(1, workerId)
                    stmt.setObject(2, leaseUntilOdt)
                    stmt.setString(3, item.approvalId.value)
                    if (stmt.executeUpdate() == 1) {
                        claimed += item
                    }
                }
            }

            claimed
        }

    override suspend fun markResumeSucceeded(approvalId: ApprovalId, workerId: String) {
        dataSource.connection.use { conn ->
            val updateSql = """
                UPDATE approval_continuations
                SET status = 'COMPLETED', completed_at = ?, version = version + 1
                WHERE approval_id = ?
                  AND claimed_by = ?
                  AND status = 'PENDING'
            """.trimIndent()
            conn.prepareStatement(updateSql).use { stmt ->
                stmt.setObject(1, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                stmt.setString(2, approvalId.value)
                stmt.setString(3, workerId)
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun markResumeFailed(
        approvalId: ApprovalId,
        workerId: String,
        reasonCode: String,
        retryAt: Instant?,
    ) {
        dataSource.connection.use { conn ->
            if (retryAt != null) {
                // Release claim but mark for retry — keep status PENDING
                val releaseSql = """
                    UPDATE approval_continuations
                    SET claimed_by = NULL, claimed_at = NULL, version = version + 1
                    WHERE approval_id = ?
                      AND claimed_by = ?
                """.trimIndent()
                conn.prepareStatement(releaseSql).use { stmt ->
                    stmt.setString(1, approvalId.value)
                    stmt.setString(2, workerId)
                    stmt.executeUpdate()
                }
            } else {
                // Terminal failure — mark as completed (terminal state)
                val terminalSql = """
                    UPDATE approval_continuations
                    SET status = 'COMPLETED', completed_at = ?, version = version + 1
                    WHERE approval_id = ?
                      AND claimed_by = ?
                """.trimIndent()
                conn.prepareStatement(terminalSql).use { stmt ->
                    stmt.setObject(1, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    stmt.setString(2, approvalId.value)
                    stmt.setString(3, workerId)
                    stmt.executeUpdate()
                }
            }
        }
    }
}
