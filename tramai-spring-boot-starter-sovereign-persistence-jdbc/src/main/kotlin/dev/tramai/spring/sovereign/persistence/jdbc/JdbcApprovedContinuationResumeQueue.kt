package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueue
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkItem
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
 * ## Claim semantics
 * 1. Select candidates with SKIP LOCKED — only rows that are not locked
 *    by another transaction are considered.
 * 2. Items must be APPROVED, continuation PENDING, not expired, and have
 *    a valid encrypted credential.
 * 3. Unclaimed rows (`claimed_by IS NULL`) are eligible.
 * 4. Previously claimed rows with expired leases (`claimed_at < now()`)
 *    are eligible for reclamation.
 * 5. Claim writes `claimed_by` = worker ID, `claimed_at` = lease expiry.
 *    Continuation version is NOT touched — the engine resume owns version
 *    management.
 *
 * ## Retry state
 * On retryable failure, `resume_last_error_code`, `resume_next_attempt_at`,
 * and `resume_attempt_count` are updated. The claim is released so the
 * row can be reclaimed after the backoff window.
 *
 * ## Terminal failure
 * On terminal failure (retryAt = null), the continuation is set to
 * `CANCELLED` — not COMPLETED — so the state is distinguishable from
 * successful resume.
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
            val nowOdt = clock.instant().atOffset(ZoneOffset.UTC)
            val leaseUntilOdt = leaseUntil.atOffset(ZoneOffset.UTC)

            val selectSql = """
                SELECT a.approval_id, a.version AS approval_version,
                       c.version AS continuation_version, c.workflow_run_id
                FROM approvals a
                JOIN approval_continuations c ON c.approval_id = a.approval_id
                JOIN tramai_approval_resume_credentials rc
                    ON rc.approval_id = a.approval_id
                    AND rc.workflow_run_id = c.workflow_run_id
                WHERE a.status = 'APPROVED'
                  AND c.status = 'PENDING'
                  AND c.approval_expires_at > ?
                  AND rc.expires_at > ?
                  AND (
                      c.claimed_by IS NULL
                      OR c.claimed_at < ?
                  )
                  AND (
                      c.resume_next_attempt_at IS NULL
                      OR c.resume_next_attempt_at <= ?
                  )
                ORDER BY c.approval_expires_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            """.trimIndent()

            val items = mutableListOf<ApprovedContinuationResumeWorkItem>()
            conn.prepareStatement(selectSql).use { stmt ->
                stmt.setObject(1, nowOdt)
                stmt.setObject(2, nowOdt)
                stmt.setObject(3, nowOdt)
                stmt.setObject(4, nowOdt)
                stmt.setInt(5, limit)
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

            // Claim each item. Version is NOT incremented — engine resume
            // owns version management.
            val updateSql = """
                UPDATE approval_continuations
                SET claimed_by = ?,
                    claimed_at = ?,
                    resume_attempt_count = resume_attempt_count + 1,
                    resume_next_attempt_at = NULL
                WHERE approval_id = ?
                  AND status = 'PENDING'
                  AND (
                      claimed_by IS NULL
                      OR claimed_at < ?
                  )
            """.trimIndent()

            val claimed = mutableListOf<ApprovedContinuationResumeWorkItem>()
            conn.prepareStatement(updateSql).use { stmt ->
                for (item in items) {
                    stmt.setString(1, workerId)
                    stmt.setObject(2, leaseUntilOdt)
                    stmt.setString(3, item.approvalId.value)
                    stmt.setObject(4, nowOdt)
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
                SET status = 'COMPLETED',
                    completed_at = ?,
                    version = version + 1,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    resume_next_attempt_at = NULL,
                    resume_last_error_code = NULL,
                    resume_attempt_count = 0
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
            val nowOdt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

            if (retryAt != null) {
                // Release claim but schedule retry — keep status PENDING
                val retrySql = """
                    UPDATE approval_continuations
                    SET claimed_by = NULL,
                        claimed_at = NULL,
                        resume_last_error_code = ?,
                        resume_next_attempt_at = ?,
                        version = version + 1
                    WHERE approval_id = ?
                      AND claimed_by = ?
                """.trimIndent()
                conn.prepareStatement(retrySql).use { stmt ->
                    stmt.setString(1, reasonCode)
                    stmt.setObject(2, retryAt.atOffset(ZoneOffset.UTC))
                    stmt.setString(3, approvalId.value)
                    stmt.setString(4, workerId)
                    stmt.executeUpdate()
                }
            } else {
                // Terminal failure — set CANCELLED (not COMPLETED)
                val terminalSql = """
                    UPDATE approval_continuations
                    SET status = 'CANCELLED',
                        completed_at = ?,
                        version = version + 1,
                        claimed_by = NULL,
                        claimed_at = NULL,
                        resume_last_error_code = ?,
                        resume_next_attempt_at = NULL,
                        resume_attempt_count = 0
                    WHERE approval_id = ?
                      AND claimed_by = ?
                """.trimIndent()
                conn.prepareStatement(terminalSql).use { stmt ->
                    stmt.setObject(1, nowOdt)
                    stmt.setString(2, reasonCode)
                    stmt.setString(3, approvalId.value)
                    stmt.setString(4, workerId)
                    stmt.executeUpdate()
                }
            }
        }
    }
}
