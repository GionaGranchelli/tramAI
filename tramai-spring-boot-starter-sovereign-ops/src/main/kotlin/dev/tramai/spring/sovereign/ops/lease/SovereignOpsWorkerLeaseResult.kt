package dev.tramai.spring.sovereign.ops.lease

/**
 * Result of a [SovereignOpsWorkerLeaseStore.tryAcquire] attempt.
 *
 * ## Variants
 * - [Acquired]: the lease was successfully acquired by the caller.
 * - [AlreadyOwned]: the lease is already owned by the caller (heartbeat extended).
 * - [HeldByOther]: the lease is actively held by a different owner.
 */
sealed interface SovereignOpsWorkerLeaseAcquisition {
    data class Acquired(val lease: SovereignOpsWorkerLease) : SovereignOpsWorkerLeaseAcquisition
    data class AlreadyOwned(val lease: SovereignOpsWorkerLease) : SovereignOpsWorkerLeaseAcquisition
    data class HeldByOther(val lease: SovereignOpsWorkerLease) : SovereignOpsWorkerLeaseAcquisition
}

/**
 * Result of a [SovereignOpsWorkerLeaseStore.heartbeat] attempt.
 */
sealed interface SovereignOpsWorkerLeaseHeartbeat {
    data class Extended(val lease: SovereignOpsWorkerLease) : SovereignOpsWorkerLeaseHeartbeat
    data object NotOwner : SovereignOpsWorkerLeaseHeartbeat
    data object Missing : SovereignOpsWorkerLeaseHeartbeat
}

/**
 * Result of a [SovereignOpsWorkerLeaseStore.release] attempt.
 */
sealed interface SovereignOpsWorkerLeaseRelease {
    data object Released : SovereignOpsWorkerLeaseRelease
    data object NotOwner : SovereignOpsWorkerLeaseRelease
    data object Missing : SovereignOpsWorkerLeaseRelease
}
