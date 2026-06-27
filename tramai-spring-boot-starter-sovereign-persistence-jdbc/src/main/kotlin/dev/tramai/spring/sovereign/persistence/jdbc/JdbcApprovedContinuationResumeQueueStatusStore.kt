package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueSnapshot
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueStatusStore
import java.time.Instant
import java.time.OffsetDateTime
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
                ApprovedContinuationResumeQueueSnapshot(
                    eligibleNow = countEligibleNow(conn, snapshotNow),
                    delayedRetry = countDelayedRetry(conn, snapshotNow),
                    activeLeases = countActiveLeases(conn, snapshotNow),
                    expiredLeases = countExpiredLeases(conn, snapshotNow),
                    terminalFailures = countTerminalFailures(conn),
                    oldestEligibleAgeSeconds = oldestEligibleAgeSeconds(conn, snapshotNow),
                    oldestRetryDueInSeconds = oldestRetryDueInSeconds(conn, snapshotNow),
                    lastErrorCodeCounts = lastErrorCodeCounts(conn),
                )
            }
        }

    private fun countEligibleNow(c: java.sql.Connection, z: OffsetDateTime): Long {
        val s = c.prepareStatement(
            "SELECT COUNT(*) FROM approvals a " +
            "JOIN approval_continuations c ON c.approval_id = a.approval_id " +
            "JOIN tramai_approval_resume_credentials rc ON rc.approval_id = a.approval_id AND rc.workflow_run_id = c.workflow_run_id " +
            "WHERE a.status = 'APPROVED' AND c.status = 'PENDING' " +
            "AND c.approval_expires_at > ? AND rc.expires_at > ? " +
            "AND (c.claimed_by IS NULL OR c.claimed_at < ?) " +
            "AND (c.resume_next_attempt_at IS NULL OR c.resume_next_attempt_at <= ?)"
        )
        return s.use { ps ->
            ps.setObject(1, z); ps.setObject(2, z); ps.setObject(3, z); ps.setObject(4, z)
            ps.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }
    }

    private fun countDelayedRetry(c: java.sql.Connection, z: OffsetDateTime): Long {
        val s = c.prepareStatement(
            "SELECT COUNT(*) FROM approvals a " +
            "JOIN approval_continuations c ON c.approval_id = a.approval_id " +
            "JOIN tramai_approval_resume_credentials rc ON rc.approval_id = a.approval_id AND rc.workflow_run_id = c.workflow_run_id " +
            "WHERE a.status = 'APPROVED' AND c.status = 'PENDING' " +
            "AND c.approval_expires_at > ? AND rc.expires_at > ? " +
            "AND (c.claimed_by IS NULL OR c.claimed_at < ?) " +
            "AND c.resume_next_attempt_at IS NOT NULL AND c.resume_next_attempt_at > ?"
        )
        return s.use { ps ->
            ps.setObject(1, z); ps.setObject(2, z); ps.setObject(3, z); ps.setObject(4, z)
            ps.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }
    }

    private fun countActiveLeases(c: java.sql.Connection, z: OffsetDateTime): Long {
        val s = c.prepareStatement(
            "SELECT COUNT(*) FROM approvals a " +
            "JOIN approval_continuations c ON c.approval_id = a.approval_id " +
            "JOIN tramai_approval_resume_credentials rc ON rc.approval_id = a.approval_id AND rc.workflow_run_id = c.workflow_run_id " +
            "WHERE a.status = 'APPROVED' AND c.status = 'PENDING' " +
            "AND c.approval_expires_at > ? AND rc.expires_at > ? " +
            "AND c.claimed_by IS NOT NULL AND c.claimed_at >= ? " +
            "AND (c.resume_next_attempt_at IS NULL OR c.resume_next_attempt_at <= ?)"
        )
        return s.use { ps ->
            ps.setObject(1, z); ps.setObject(2, z); ps.setObject(3, z); ps.setObject(4, z)
            ps.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }
    }

    private fun countExpiredLeases(c: java.sql.Connection, z: OffsetDateTime): Long {
        val s = c.prepareStatement(
            "SELECT COUNT(*) FROM approvals a " +
            "JOIN approval_continuations c ON c.approval_id = a.approval_id " +
            "JOIN tramai_approval_resume_credentials rc ON rc.approval_id = a.approval_id AND rc.workflow_run_id = c.workflow_run_id " +
            "WHERE a.status = 'APPROVED' AND c.status = 'PENDING' " +
            "AND c.approval_expires_at > ? AND rc.expires_at > ? " +
            "AND c.claimed_by IS NOT NULL AND c.claimed_at < ? " +
            "AND (c.resume_next_attempt_at IS NULL OR c.resume_next_attempt_at <= ?)"
        )
        return s.use { ps ->
            ps.setObject(1, z); ps.setObject(2, z); ps.setObject(3, z); ps.setObject(4, z)
            ps.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }
    }

    private fun countTerminalFailures(c: java.sql.Connection): Long {
        val s = c.prepareStatement(
            "SELECT COUNT(*) FROM approval_continuations " +
            "WHERE status = 'CANCELLED' AND resume_last_error_code IS NOT NULL"
        )
        return s.use { ps ->
            ps.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }
    }

    private fun oldestEligibleAgeSeconds(c: java.sql.Connection, z: OffsetDateTime): Long? {
        val s = c.prepareStatement(
            "SELECT EXTRACT(EPOCH FROM (? - c.created_at))::BIGINT " +
            "FROM approvals a " +
            "JOIN approval_continuations c ON c.approval_id = a.approval_id " +
            "JOIN tramai_approval_resume_credentials rc ON rc.approval_id = a.approval_id AND rc.workflow_run_id = c.workflow_run_id " +
            "WHERE a.status = 'APPROVED' AND c.status = 'PENDING' " +
            "AND c.approval_expires_at > ? AND rc.expires_at > ? " +
            "AND (c.claimed_by IS NULL OR c.claimed_at < ?) " +
            "AND (c.resume_next_attempt_at IS NULL OR c.resume_next_attempt_at <= ?) " +
            "ORDER BY c.created_at ASC LIMIT 1"
        )
        return s.use { ps ->
            ps.setObject(1, z); ps.setObject(2, z); ps.setObject(3, z)
            ps.setObject(4, z); ps.setObject(5, z)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else null
            }
        }
    }

    private fun oldestRetryDueInSeconds(c: java.sql.Connection, z: OffsetDateTime): Long? {
        val s = c.prepareStatement(
            "SELECT MIN(EXTRACT(EPOCH FROM (c.resume_next_attempt_at - ?))::BIGINT) " +
            "FROM approvals a " +
            "JOIN approval_continuations c ON c.approval_id = a.approval_id " +
            "JOIN tramai_approval_resume_credentials rc ON rc.approval_id = a.approval_id AND rc.workflow_run_id = c.workflow_run_id " +
            "WHERE a.status = 'APPROVED' AND c.status = 'PENDING' " +
            "AND c.approval_expires_at > ? AND rc.expires_at > ? " +
            "AND (c.claimed_by IS NULL OR c.claimed_at < ?) " +
            "AND c.resume_next_attempt_at IS NOT NULL AND c.resume_next_attempt_at > ?"
        )
        return s.use { ps ->
            ps.setObject(1, z); ps.setObject(2, z); ps.setObject(3, z)
            ps.setObject(4, z); ps.setObject(5, z)
            ps.executeQuery().use { rs ->
                check(rs.next())
                val v = rs.getLong(1)
                if (rs.wasNull()) null else v
            }
        }
    }

    private fun lastErrorCodeCounts(c: java.sql.Connection): Map<String, Long> {
        val s = c.prepareStatement(
            "SELECT resume_last_error_code, COUNT(*) AS cnt " +
            "FROM approval_continuations " +
            "WHERE resume_last_error_code IS NOT NULL AND status IN ('PENDING', 'CANCELLED') " +
            "GROUP BY resume_last_error_code"
        )
        return s.use { ps ->
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(rs.getString("resume_last_error_code"), rs.getLong("cnt"))
                    }
                }
            }
        }
    }
}
