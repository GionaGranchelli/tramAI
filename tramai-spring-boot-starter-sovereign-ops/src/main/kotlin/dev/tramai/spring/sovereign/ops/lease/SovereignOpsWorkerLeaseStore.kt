package dev.tramai.spring.sovereign.ops.lease

import java.time.Duration
import java.time.Instant

/**
 * Persistence contract for worker lease coordination.
 *
 * Implementations coordinate named leases across multiple JVM nodes
 * using an external store (JDBC, Redis, etc.). A lease allows a worker
 * to claim exclusive ownership of a background task (e.g., audit outbox
 * recovery/dispatch) so that only one node runs the cycle at a time.
 *
 * ## Design constraints
 * - All methods accept [now] for test determinism — do not use static clocks.
 * - [tryAcquire] must be atomic: if two callers race for the same lease name,
 *   exactly one wins. The other receives [SovereignOpsWorkerLeaseAcquisition.HeldByOther].
 * - Expired leases can be stolen. A non-expired lease held by a different
 *   owner must never be stolen.
 * - [heartbeat] extends the lease expiry. Only the current owner may heartbeat.
 * - [release] clears ownership. Only the current owner may release.
 */
interface SovereignOpsWorkerLeaseStore {

    /**
     * Attempt to acquire [leaseName] for [ownerId].
     *
     * If no lease exists for this name, a new row is inserted with
     * `expiresAt = now + leaseDuration` and [SovereignOpsWorkerLeaseAcquisition.Acquired]
     * is returned.
     *
     * If a lease exists and is owned by [ownerId], the expiry is extended
     * and [SovereignOpsWorkerLeaseAcquisition.AlreadyOwned] is returned.
     *
     * If a lease exists, is not expired, and is owned by a different owner,
     * [SovereignOpsWorkerLeaseAcquisition.HeldByOther] is returned.
     *
     * If a lease exists, is expired, and is owned by a different owner,
     * the lease is stolen and [SovereignOpsWorkerLeaseAcquisition.Acquired]
     * is returned.
     */
    suspend fun tryAcquire(
        leaseName: String,
        ownerId: String,
        now: Instant,
        leaseDuration: Duration,
    ): SovereignOpsWorkerLeaseAcquisition

    /**
     * Extend the lease expiry for [leaseName] by [leaseDuration] from [now].
     *
     * Returns [SovereignOpsWorkerLeaseHeartbeat.Extended] if the caller is the
     * current owner and the lease is not expired.
     *
     * Returns [SovereignOpsWorkerLeaseHeartbeat.NotOwner] if a different owner
     * holds the lease.
     *
     * Returns [SovereignOpsWorkerLeaseHeartbeat.Missing] if no lease exists
     * for [leaseName].
     */
    suspend fun heartbeat(
        leaseName: String,
        ownerId: String,
        now: Instant,
        leaseDuration: Duration,
    ): SovereignOpsWorkerLeaseHeartbeat

    /**
     * Release a lease held by [ownerId].
     *
     * Clears [ownerId], [acquiredAt], [expiresAt], and [heartbeatAt].
     * The row remains for future acquisition.
     *
     * Returns [SovereignOpsWorkerLeaseRelease.Released] on success,
     * [SovereignOpsWorkerLeaseRelease.NotOwner] if a different owner holds
     * the lease, or [SovereignOpsWorkerLeaseRelease.Missing] if no row exists.
     */
    suspend fun release(
        leaseName: String,
        ownerId: String,
        now: Instant,
    ): SovereignOpsWorkerLeaseRelease

    /**
     * Look up a lease by name. Returns null if no row exists.
     */
    suspend fun get(leaseName: String): SovereignOpsWorkerLease?
}
