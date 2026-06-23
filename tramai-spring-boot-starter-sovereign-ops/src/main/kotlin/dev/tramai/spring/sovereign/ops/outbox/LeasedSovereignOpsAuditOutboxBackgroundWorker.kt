package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseAcquisition
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseHeartbeat
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseStore
import dev.tramai.spring.sovereign.ops.SovereignOpsOutboxWorkerProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/**
 * Lease-aware wrapper around [SovereignOpsAuditOutboxBackgroundWorker].
 *
 * Before delegating to the real worker, this wrapper attempts to acquire
 * the configured worker lease. If the lease is held by another owner,
 * the cycle is skipped with a [SovereignOpsAuditOutboxWorkerSkippedSummary].
 *
 * During delegate execution, a heartbeat coroutine periodically extends
 * the lease so that long-running cycles do not lose the lease mid-run.
 * If the heartbeat returns anything other than Extended, a
 * [SovereignOpsWorkerLeaseLostException] is thrown — a non-cancellation
 * [RuntimeException] that **reliably fails** the enclosing
 * [coroutineScope], cancelling the delegate. A
 * [kotlinx.coroutines.CancellationException] from a child coroutine
 * would be treated as normal cancellation and might not propagate.
 *
 * ## Design
 * - **Wrapper, not rewrite.** The existing [SovereignOpsAuditOutboxBackgroundWorker]
 *   is unchanged. This class adds the coordination layer on top.
 * - **Heartbeat loop during run.** A coroutine heartbeats at
 *   [SovereignOpsOutboxWorkerProperties.leaseHeartbeatInterval] while the
 *   delegate runs. Lease loss aborts the cycle.
 * - **Cancellation is rethrown.** [CancellationException] must never be
 *   swallowed by lease logic.
 *
 * ## Security
 * - No raw lease metadata is leaked in the skipped summary.
 */
class LeasedSovereignOpsAuditOutboxBackgroundWorker(
    private val delegate: SovereignOpsAuditOutboxBackgroundWorker,
    private val leaseStore: SovereignOpsWorkerLeaseStore,
    private val properties: SovereignOpsOutboxWorkerProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Attempts to acquire the worker lease, then:
     * - If acquired/owned: runs the delegate with a heartbeat loop.
     * - If held by another owner: returns a skipped summary.
     *
     * The heartbeat loop extends the lease at
     * [SovereignOpsOutboxWorkerProperties.leaseHeartbeatInterval] intervals.
     * A non-Extended heartbeat throws [SovereignOpsWorkerLeaseLostException],
     * which fails the [coroutineScope] and cancels the delegate.
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
            is SovereignOpsWorkerLeaseAcquisition.AlreadyOwned ->
                runWithHeartbeat()

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

    /**
     * Runs the delegate inside a [coroutineScope] with a sibling heartbeat
     * coroutine that extends the lease until the delegate completes.
     *
     * A non-Extended heartbeat throws [SovereignOpsWorkerLeaseLostException]
     * — a non-cancellation [RuntimeException] that reliably fails the
     * enclosing [coroutineScope], cancelling the delegate.
     */
    private suspend fun runWithHeartbeat(): SovereignOpsAuditOutboxWorkerRunSummary =
        coroutineScope {
            val heartbeatJob = launch {
                while (isActive) {
                    delay(properties.leaseHeartbeatInterval.toMillis())

                    val heartbeat = leaseStore.heartbeat(
                        leaseName = properties.leaseName,
                        ownerId = properties.workerId,
                        now = clock.instant(),
                        leaseDuration = properties.leaseDuration,
                    )

                    if (heartbeat !is SovereignOpsWorkerLeaseHeartbeat.Extended) {
                        throw SovereignOpsWorkerLeaseLostException(
                            "tramai-sovereign-ops-worker-lease-lost: " +
                                "heartbeat returned ${heartbeat::class.simpleName}",
                        )
                    }
                }
            }

            try {
                delegate.runOnce()
            } catch (e: CancellationException) {
                throw e
            } finally {
                heartbeatJob.cancelAndJoin()
            }
        }
}
