package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Owns the lease renewal cadence for one active execution.
 *
 * Verbatim worker semantics: renew every `leaseDurationMillis / 2` using the
 * execution's latest checkpoint revision; a successful renewal atomically
 * replaces the tracked lease and emits [TramaiWorkerObserver.onLeaseRenewed];
 * a renewal conflict marks the lease lost (expired event, lease cleared,
 * execution job cancelled) and terminates ownership; transient failures emit
 * [TramaiWorkerObserver.onLeaseRenewalFailed] and retry on a halved cadence;
 * cancellation escapes as-is. The loop is tied to the execution lifetime and
 * must never outlive it — the execution supervisor cancels the renewal job in
 * its execution `finally`.
 */
internal class LeaseRenewalLoop(
    private val config: WorkerConfig,
    private val leaseStore: WorkflowLeaseStore,
    private val observability: TramaiWorkerObserver,
) {
    suspend fun renew(handle: ActiveExecution) {
        val interval = maxOf(1L, config.leaseDurationMillis / 2)
        var nextDelayMillis = interval
        while (currentCoroutineContext().isActive) {
            delay(nextDelayMillis)
            val currentLease = handle.lease.get() ?: return
            val renewed = try {
                leaseStore.renew(
                    lease = currentLease,
                    checkpointRevision = handle.lastRevision.get(),
                    leaseDurationMillis = config.leaseDurationMillis,
                )
            } catch (_: WorkflowLeaseConflictException) {
                observability.onLeaseExpired(handle.workflowId, config.workerId)
                handle.lease.set(null)
                handle.executionJob?.cancel(CancellationException("Workflow lease for '${handle.workflowId}' was lost"))
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                observability.onLeaseRenewalFailed(
                    handle.workflowId,
                    config.workerId,
                    safeWorkerObservableFailure(PersistenceResourceKind.LEASE, PersistenceOperation.RENEW, error),
                )
                nextDelayMillis = maxOf(50L, interval / 2)
                continue
            }
            handle.lease.set(renewed)
            observability.onLeaseRenewed(handle.workflowId, config.workerId, renewed.expiresAtEpochMillis)
            nextDelayMillis = interval
        }
    }
}
