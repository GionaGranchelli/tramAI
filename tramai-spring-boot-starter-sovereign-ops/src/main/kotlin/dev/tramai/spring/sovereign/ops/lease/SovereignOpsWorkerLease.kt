package dev.tramai.spring.sovereign.ops.lease

import java.time.Instant

/**
 * A named worker lease used for multi-node coordination of sovereign ops
 * background workers.
 *
 * ## Security invariants
 * - [ownerId] is persisted as-is in the `worker_leases` table — no hashing.
 * - This is an internal coordination lease, not a user-facing credential.
 *   [ownerId] values should be machine identities (hostnames, instance IDs),
 *   not user PII.
 * - The [version] field acts as an optimistic lock for CAS updates.
 */
data class SovereignOpsWorkerLease(
    val leaseName: String,
    val ownerId: String?,
    val acquiredAt: Instant?,
    val expiresAt: Instant?,
    val heartbeatAt: Instant?,
    val version: Long,
) {
    /** A lease is expired when no expiry is set or the expiry is in the past. */
    fun isExpired(now: Instant): Boolean =
        expiresAt == null || !expiresAt.isAfter(now)

    /** True when [ownerId] is non-null and matches the given ID. */
    fun isOwnedBy(ownerId: String): Boolean =
        this.ownerId == ownerId

    init {
        if (leaseName.isBlank()) {
            throw IllegalArgumentException("leaseName must not be blank")
        }
        if (version <= 0) {
            throw IllegalArgumentException("version must be positive, got $version")
        }
    }
}
