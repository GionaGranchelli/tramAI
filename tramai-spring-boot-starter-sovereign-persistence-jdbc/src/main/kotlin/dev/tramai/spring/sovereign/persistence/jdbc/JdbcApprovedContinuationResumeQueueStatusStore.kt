package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueSnapshot
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueStatusStore
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JdbcApprovedContinuationResumeQueueStatusStore(
    private val dataSource: DataSource,
) : ApprovedContinuationResumeQueueStatusStore {

    override suspend fun snapshot(now: Instant): ApprovedContinuationResumeQueueSnapshot =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val snapshotNow = now.atOffset(ZoneOffset.UTC)
                val eligibleNow = countEligibleNow(conn, snapshotNow)
                val delayedRetry = countDelayedRetry(conn, snapshotNow)
                val activeLeases = countActiveLeases(conn, snapshotNow)
                val expiredLeases = countExpiredLeases(conn, snapshotNow)
                val terminalFailures = countTerminalFailures(conn)
                val oldestEligibleAgeSeconds = oldestEligibleAgeSeconds(conn, snapshotNow)
                val oldestRetryDueInSeconds = oldestRetryDueInSeconds(conn, snapshotNow)
                val lastErrorCodeCounts = lastErrorCodeCounts(conn)

                ApprovedContinuationResumeQueueSnapshot(
                    eligibleNow = eligibleNow,
                    delayedRetry = delayedRetry,
                    activeLeases = activeLeases,
                    expiredLeases = expiredLeases,
                    terminalFailures = terminalFailures,
                    oldestEligibleAgeSeconds = oldestEligibleAgeSeconds,
                    oldestRetryDueInSeconds = oldestRetryDueInSeconds,
                    lastErrorCodeCounts = lastErrorCodeCounts,
                )
            }
        }

    private fun countEligibleNow(conn: Connection, now: java.time.OffsetDateTime): Long {
        val sql = """
            SELECT COUNT(*)
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
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, now)
            stmt.setObject(2, now)
            stmt.setObject(3, now)
            stmt.setObject(4, now)
            stmt.executeQuery().use { rs ->
                check(rs.next())
                rs.getLong(1)
            }
        }
    }

    private fun countDelayedRetry(conn: Connection, now: java.time.OffsetDateTime): Long {
        val sql = """
            SELECT COUNT(*)
            FROM approval_continuations c
            JOIN approvals a ON a.approval_id = c.approval_id
            WHERE a.status = 'APPROVED'
              AND c.status = 'PENDING'
              AND c.resume_next_attempt_at IS NOT NULL
              AND c.resume_next_attempt_at > ?
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, now)
            stmt.executeQuery().use { rs ->
                check(rs.next())
                rs.getLong(1)
            }
        }
    }

    private fun countActiveLeases(conn: Connection, now: java.time.OffsetDateTime): Long {
        val sql = """
            SELECT COUNT(*)
            FROM approval_continuations c
            JOIN approvals a ON a.approval_id = c.approval_id
            WHERE a.status = 'APPROVED'
              AND c.status = 'PENDING'
              AND c.claimed_by IS NOT NULL
              AND c.claimed_at >= ?
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, now)
            stmt.executeQuery().use { rs ->
                check(rs.next())
                rs.getLong(1)
            }
        }
    }

    private fun countExpiredLeases(conn: Connection, now: java.time.OffsetDateTime): Long {
        val sql = """
            SELECT COUNT(*)
            FROM approval_continuations c
            JOIN approvals a ON a.approval_id = c.approval_id
            WHERE a.status = 'APPROVED'
              AND c.status = 'PENDING'
              AND c.claimed_by IS NOT NULL
              AND c.claimed_at < ?
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, now)
            stmt.executeQuery().use { rs ->
                check(rs.next())
                rs.getLong(1)
            }
        }
    }

    private fun countTerminalFailures(conn: Connection): Long {
        val sql = """
            SELECT COUNT(*)
            FROM approval_continuations
            WHERE status = 'CANCELLED'
              AND resume_last_error_code IS NOT NULL
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next())
                rs.getLong(1)
            }
        }
    }

    private fun oldestEligibleAgeSeconds(
        conn: Connection,
        now: java.time.OffsetDateTime,
    ): Long? {
        val sql = """
            SELECT EXTRACT(EPOCH FROM (? - c.created_at))::BIGINT
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
            ORDER BY c.created_at ASC
            LIMIT 1
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, now)
            stmt.setObject(2, now)
            stmt.setObject(3, now)
            stmt.setObject(4, now)
            stmt.setObject(5, now)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else null
            }
        }
    }

    private fun oldestRetryDueInSeconds(
        conn: Connection,
        now: java.time.OffsetDateTime,
    ): Long? {
        val sql = """
            SELECT EXTRACT(EPOCH FROM (c.resume_next_attempt_at - ?))::BIGINT
            FROM approval_continuations c
            JOIN approvals a ON a.approval_id = c.approval_id
            WHERE a.status = 'APPROVED'
              AND c.status = 'PENDING'
              AND c.resume_next_attempt_at IS NOT NULL
              AND c.resume_next_attempt_at > ?
            ORDER BY c.resume_next_attempt_at ASC
            LIMIT 1
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, now)
            stmt.setObject(2, now)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else null
            }
        }
    }

    private fun lastErrorCodeCounts(conn: Connection): Map<String, Long> {
        val sql = """
            SELECT resume_last_error_code, COUNT(*) AS cnt
            FROM approval_continuations
            WHERE status = 'CANCELLED'
              AND resume_last_error_code IS NOT NULL
            GROUP BY resume_last_error_code
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(rs.getString("resume_last_error_code"), rs.getLong("cnt"))
                    }
                }
            }
        }
    }
}
