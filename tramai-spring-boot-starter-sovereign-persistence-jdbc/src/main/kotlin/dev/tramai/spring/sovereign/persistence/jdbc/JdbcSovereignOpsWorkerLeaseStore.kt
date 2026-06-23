package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLease
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseAcquisition
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseHeartbeat
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseRelease
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseStore
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * JDBC implementation of [SovereignOpsWorkerLeaseStore] backed by the
 * `worker_leases` table in PostgreSQL.
 *
 * ## Acquisition algorithm
 * 1. `INSERT ... ON CONFLICT DO NOTHING` — idempotently creates the row.
 * 2. `SELECT ... FOR UPDATE` — locks the row for the current transaction.
 * 3. Decision logic:
 *    - Same owner + not expired → heartbeat/extend → AlreadyOwned
 *    - No owner or expired → take ownership → Acquired
 *    - Different owner + not expired → HeldByOther
 * 4. `UPDATE ... SET ...` — applies the decision, asserts exactly 1 row updated.
 * 5. Re-reads the updated row so returned lease version matches the committed DB state.
 *
 * ## Concurrency
 * The `FOR UPDATE` row lock ensures exactly one concurrent caller wins
 * for a given `lease_name`. The loser receives `HeldByOther`.
 *
 * ## Heartbeat
 * Rejected if the lease is expired or held by a different owner.
 *
 * ## Security
 * - [ownerId] is stored as plaintext — this is a machine coordination lease,
 *   not a user credential. Use hostnames or instance IDs.
 * - No encryption is applied to lease rows (they contain no user data).
 * - All mutation helpers assert exactly 1 row updated (fail-closed).
 *
 * @param dataSource A JDBC [DataSource] (usually HikariCP in Spring Boot).
 *   The caller is responsible for providing a pooled, production-grade source.
 */
class JdbcSovereignOpsWorkerLeaseStore(
    private val dataSource: DataSource,
) : SovereignOpsWorkerLeaseStore {

    override suspend fun tryAcquire(
        leaseName: String,
        ownerId: String,
        now: Instant,
        leaseDuration: Duration,
    ): SovereignOpsWorkerLeaseAcquisition {
        val expiresAt = now.plus(leaseDuration)
        return dataSource.connection.use { conn ->
            inTransaction(conn) {
                ensureRow(conn, leaseName)
                val lease = selectForUpdate(conn, leaseName)
                    ?: throw IllegalStateException("worker_leases row missing after ensureRow: $leaseName")
                val result = decide(lease, ownerId, now, expiresAt)
                if (result is SovereignOpsWorkerLeaseAcquisition.Acquired ||
                    result is SovereignOpsWorkerLeaseAcquisition.AlreadyOwned
                ) {
                    val updated = updateOwnership(conn, leaseName, ownerId, now, expiresAt)
                    return@inTransaction when (result) {
                        is SovereignOpsWorkerLeaseAcquisition.Acquired ->
                            SovereignOpsWorkerLeaseAcquisition.Acquired(updated)
                        is SovereignOpsWorkerLeaseAcquisition.AlreadyOwned ->
                            SovereignOpsWorkerLeaseAcquisition.AlreadyOwned(updated)
                    }
                }
                result
            }
        }
    }

    override suspend fun heartbeat(
        leaseName: String,
        ownerId: String,
        now: Instant,
        leaseDuration: Duration,
    ): SovereignOpsWorkerLeaseHeartbeat {
        val expiresAt = now.plus(leaseDuration)
        return dataSource.connection.use { conn ->
            inTransaction(conn) {
                val lease = selectForUpdate(conn, leaseName)
                    ?: return@inTransaction SovereignOpsWorkerLeaseHeartbeat.Missing
                if (lease.ownerId != ownerId) {
                    return@inTransaction SovereignOpsWorkerLeaseHeartbeat.NotOwner
                }
                if (lease.isExpired(now)) {
                    return@inTransaction SovereignOpsWorkerLeaseHeartbeat.Expired
                }
                val updated = updateOwnership(conn, leaseName, ownerId, now, expiresAt)
                SovereignOpsWorkerLeaseHeartbeat.Extended(updated)
            }
        }
    }

    override suspend fun release(
        leaseName: String,
        ownerId: String,
        now: Instant,
    ): SovereignOpsWorkerLeaseRelease {
        return dataSource.connection.use { conn ->
            inTransaction(conn) {
                val lease = selectForUpdate(conn, leaseName)
                    ?: return@inTransaction SovereignOpsWorkerLeaseRelease.Missing
                if (lease.ownerId != ownerId) return@inTransaction SovereignOpsWorkerLeaseRelease.NotOwner
                clearOwnership(conn, leaseName)
                SovereignOpsWorkerLeaseRelease.Released
            }
        }
    }

    override suspend fun get(leaseName: String): SovereignOpsWorkerLease? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT lease_name, owner_id, acquired_at, expires_at, heartbeat_at, version " +
                    "FROM worker_leases WHERE lease_name = ?",
            ).use { stmt ->
                stmt.setString(1, leaseName)
                stmt.executeQuery().use { rs ->
                    return if (rs.next()) rowToLease(rs) else null
                }
            }
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────

    private fun <T> inTransaction(conn: Connection, block: (Connection) -> T): T {
        val prevAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            return result
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = prevAutoCommit
        }
    }

    private fun ensureRow(conn: Connection, leaseName: String) {
        conn.prepareStatement(
            "INSERT INTO worker_leases (lease_name, owner_id, acquired_at, expires_at, heartbeat_at, version) " +
                "VALUES (?, NULL, NULL, NULL, NULL, 1) ON CONFLICT (lease_name) DO NOTHING",
        ).use { stmt ->
            stmt.setString(1, leaseName)
            stmt.executeUpdate()
        }
    }

    private fun selectForUpdate(conn: Connection, leaseName: String): SovereignOpsWorkerLease? {
        conn.prepareStatement(
            "SELECT lease_name, owner_id, acquired_at, expires_at, heartbeat_at, version " +
                "FROM worker_leases WHERE lease_name = ? FOR UPDATE",
        ).use { stmt ->
            stmt.setString(1, leaseName)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) rowToLease(rs) else null
            }
        }
    }

    private fun decide(
        lease: SovereignOpsWorkerLease,
        ownerId: String,
        now: Instant,
        newExpiresAt: Instant,
    ): SovereignOpsWorkerLeaseAcquisition {
        val currentOwner = lease.ownerId
        val expired = lease.isExpired(now)

        return when {
            currentOwner == ownerId -> {
                SovereignOpsWorkerLeaseAcquisition.AlreadyOwned(
                    lease.copy(
                        expiresAt = newExpiresAt,
                        heartbeatAt = now,
                    ),
                )
            }
            expired -> {
                SovereignOpsWorkerLeaseAcquisition.Acquired(
                    lease.copy(
                        ownerId = ownerId,
                        acquiredAt = now,
                        expiresAt = newExpiresAt,
                        heartbeatAt = now,
                    ),
                )
            }
            else -> {
                SovereignOpsWorkerLeaseAcquisition.HeldByOther(lease)
            }
        }
    }

    /**
     * Applies the ownership update and returns the re-read row so the
     * returned lease version matches the committed DB state.
     */
    private fun updateOwnership(
        conn: Connection,
        leaseName: String,
        ownerId: String,
        now: Instant,
        expiresAt: Instant,
    ): SovereignOpsWorkerLease {
        conn.prepareStatement(
            "UPDATE worker_leases SET owner_id = ?, acquired_at = ?, heartbeat_at = ?, expires_at = ?, version = version + 1 " +
                "WHERE lease_name = ?",
        ).use { stmt ->
            stmt.setString(1, ownerId)
            stmt.setTimestamp(2, Timestamp.from(now))
            stmt.setTimestamp(3, Timestamp.from(now))
            stmt.setTimestamp(4, Timestamp.from(expiresAt))
            stmt.setString(5, leaseName)
            val updated = stmt.executeUpdate()
            require(updated == 1) {
                "tramai-sovereign-worker-lease-update-failed: expected 1 row, got $updated"
            }
        }
        return selectForUpdate(conn, leaseName)
            ?: throw IllegalStateException("worker_leases row disappeared after update: $leaseName")
    }

    private fun clearOwnership(conn: Connection, leaseName: String) {
        conn.prepareStatement(
            "UPDATE worker_leases SET owner_id = NULL, acquired_at = NULL, expires_at = NULL, heartbeat_at = NULL, version = version + 1 " +
                "WHERE lease_name = ?",
        ).use { stmt ->
            stmt.setString(1, leaseName)
            val updated = stmt.executeUpdate()
            require(updated == 1) {
                "tramai-sovereign-worker-lease-release-failed: expected 1 row, got $updated"
            }
        }
    }

    private fun rowToLease(rs: ResultSet): SovereignOpsWorkerLease =
        SovereignOpsWorkerLease(
            leaseName = rs.getString("lease_name"),
            ownerId = rs.getString("owner_id"),
            acquiredAt = rs.getTimestamp("acquired_at")?.toInstant(),
            expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
            heartbeatAt = rs.getTimestamp("heartbeat_at")?.toInstant(),
            version = rs.getLong("version"),
        )
}
