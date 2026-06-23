package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseAcquisition
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseStore
import dev.tramai.spring.sovereign.ops.SovereignOpsOutboxWorkerProperties
import java.time.Clock
import java.time.Instant

/**
 * Lease-aware wrapper around [SovereignOpsAuditOutboxBackgroundWorker].
 *
 * Before delegating to the real worker, this wrapper attempts to acquire
 * the configured worker lease. If the lease is held by another owner,
 * the cycle is skipped with a [SovereignOpsAuditOutboxWorkerSkippedSummary].
 *
 * ## Design
 * - **Wrapper, not rewrite.** The existing [SovereignOpsAuditOutboxBackgroundWorker]
 *   is unchanged. This class adds the coordination layer on top.
 * - **Heartbeat after run.** When the lease is acquired and the delegate
 *   completes successfully, a heartbeat is issued to extend the lease.
 * - **Cancellation is rethrown.** [kotlinx.coroutines.CancellationException]
 *   must never be swallowed by lease logic.
 *
 * ## Security
 * - No raw lease metadata is leaked in the skipped summary.
 * - Heartbeat failures are logged at the store level, not reflected
 *   in the worker run summary (the dispatch cycle already completed).
 */
class LeasedSovereignOpsAuditOutboxBackgroundWorker(
    private val delegate: SovereignOpsAuditOutboxBackgroundWorker,
    private val leaseStore: SovereignOpsWorkerLeaseStore,
    private val properties: SovereignOpsOutboxWorkerProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Attempts to acquire the worker lease, then:
     * - If acquired/owned: delegates to the real worker and heartbeats.
     * - If held by another owner: returns a skipped summary.
     */
    suspend fun runOnce(): SovereignOpsAuditOutboxWorkerRunSummary {
        val now = clock.instant()

        val acquisition = leaseStore.tryAcquire(
            leaseName = properties.leaseName,
            ownerId = properties.workerId,
            now = now,
            leaseDuration = properties.leaseDuration,
        )

        return when (acquisition) {
            is SovereignOpsWorkerLeaseAcquisition.Acquired,
            is SovereignOpsWorkerLeaseAcquisition.AlreadyOwned -> {
                val summary = delegate.runOnce()

                // Best-effort heartbeat: the dispatch completed; a heartbeat
                // failure does not invalidate the run result.
                try {
                    leaseStore.heartbeat(
                        leaseName = properties.leaseName,
                        ownerId = properties.workerId,
                        now = clock.instant(),
                        leaseDuration = properties.leaseDuration,
                    )
                } catch (_: Exception) {
                    // Heartbeat is advisory — drop silently.
                }

                summary
            }

            is SovereignOpsWorkerLeaseAcquisition.HeldByOther -> {
                SovereignOpsAuditOutboxWorkerRunSummary(
                    recovered = null,
                    dispatched = null,
                    failure = null,
                    skipped = SovereignOpsAuditOutboxWorkerSkippedSummary(
                        reason = "lease-held-by-other",
                    ),
                    startedAt = now,
                    completedAt = clock.instant(),
                )
            }
        }
    }
}
