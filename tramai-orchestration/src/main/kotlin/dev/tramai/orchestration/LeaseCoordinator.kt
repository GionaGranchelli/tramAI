package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation

/**
 * Owns lease acquisition, contention handling, and release for the worker.
 *
 * The coordinator wraps [WorkflowLeaseStore] with the worker's observer
 * notifications: a successful claim emits [TramaiWorkerObserver.onLeaseAcquired],
 * contention emits [TramaiWorkerObserver.onLeaseContested], a successful release
 * emits [TramaiWorkerObserver.onLeaseReleased], and a failed release emits
 * [TramaiWorkerObserver.onLeaseReleaseFailed] (sanitized through the safe
 * persistence-failure boundary) before returning `false` so the caller can
 * decide whether to clear its own lease reference.
 *
 * Lease renewal is intentionally NOT owned here — it belongs to
 * [LeaseRenewalLoop].
 */
internal class LeaseCoordinator(
    private val config: WorkerConfig,
    private val leaseStore: WorkflowLeaseStore,
    private val observability: TramaiWorkerObserver,
) {
    suspend fun currentLease(
        workflowName: String,
        workflowId: String,
    ): WorkflowLease? = leaseStore.currentLease(workflowName, workflowId)

    /**
     * Claims the lease for [checkpoint], or returns null when the claim is
     * contested. A contested claim inspects the current lease and emits
     * [TramaiWorkerObserver.onLeaseContested]; the checkpoint is not executed.
     */
    suspend fun claim(checkpoint: WorkflowCheckpoint): WorkflowLease? {
        return try {
            leaseStore.claim(
                workflowName = checkpoint.workflowName,
                workflowId = checkpoint.workflowId,
                ownerId = config.workerId,
                checkpointRevision = checkpoint.revision,
                leaseDurationMillis = config.leaseDurationMillis,
            )
        } catch (_: WorkflowLeaseConflictException) {
            val currentLease = leaseStore.currentLease(checkpoint.workflowName, checkpoint.workflowId)
            if (currentLease != null) {
                observability.onLeaseContested(checkpoint.workflowId, config.workerId, currentLease.ownerId)
            }
            null
        }?.also {
            observability.onLeaseAcquired(checkpoint.workflowId, config.workerId)
        }
    }

    /**
     * Releases [lease]. Returns true when the store accepted the release and
     * [TramaiWorkerObserver.onLeaseReleased] was emitted; returns false (after
     * emitting [TramaiWorkerObserver.onLeaseReleaseFailed]) when the store
     * rejected it, so callers keep their lease reference and can retry.
     */
    suspend fun release(lease: WorkflowLease): Boolean {
        return try {
            leaseStore.release(lease)
            observability.onLeaseReleased(lease.workflowId, config.workerId)
            true
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            observability.onLeaseReleaseFailed(
                lease.workflowId,
                config.workerId,
                safeWorkerObservableFailure(PersistenceResourceKind.LEASE, PersistenceOperation.RELEASE, error),
            )
            false
        }
    }
}
