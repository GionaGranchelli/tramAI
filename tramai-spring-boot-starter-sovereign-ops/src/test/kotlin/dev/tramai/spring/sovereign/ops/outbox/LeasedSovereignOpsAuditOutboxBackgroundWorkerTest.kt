package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.SovereignOpsOutboxWorkerProperties
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLease
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseAcquisition
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseHeartbeat
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseRelease
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class LeasedSovereignOpsAuditOutboxBackgroundWorkerTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"))
    private val BASE_NOW = fixedClock.instant()
    private val LEASE_DURATION = Duration.ofMinutes(2)
    /** Very short heartbeat interval so tests don't hang. */
    private val HEARTBEAT_INTERVAL = Duration.ofMillis(10)

    private lateinit var leaseStore: FakeLeaseStore
    private lateinit var operations: TrackingOperations
    private lateinit var properties: SovereignOpsOutboxWorkerProperties

    @BeforeEach
    fun setUp() {
        leaseStore = FakeLeaseStore()
        operations = TrackingOperations()
        properties = SovereignOpsOutboxWorkerProperties().copy(
            enabled = true,
            leaseEnabled = true,
            leaseName = "test-lease",
            workerId = "worker-a",
            leaseDuration = LEASE_DURATION,
            leaseHeartbeatInterval = HEARTBEAT_INTERVAL,
        )
    }

    private fun worker(): LeasedSovereignOpsAuditOutboxBackgroundWorker {
        val delegate = SovereignOpsAuditOutboxBackgroundWorker(
            operations = operations,
            properties = properties,
            clock = fixedClock,
        )
        return LeasedSovereignOpsAuditOutboxBackgroundWorker(
            delegate = delegate,
            leaseStore = leaseStore,
            properties = properties,
            clock = fixedClock,
        )
    }

    @Test
    fun `acquired lease runs delegate`() = runBlocking {
        leaseStore.acquireResult = SovereignOpsWorkerLeaseAcquisition.Acquired(
            SovereignOpsWorkerLease(
                leaseName = "test-lease",
                ownerId = "worker-a",
                acquiredAt = BASE_NOW,
                expiresAt = BASE_NOW.plus(LEASE_DURATION),
                heartbeatAt = BASE_NOW,
                version = 1,
            ),
        )

        val summary = worker().runOnce()
        assertThat(operations.recoverCalled).isTrue
        assertThat(summary.skipped).isNull()
    }

    @Test
    fun `already owned lease runs delegate`() = runBlocking {
        leaseStore.acquireResult = SovereignOpsWorkerLeaseAcquisition.AlreadyOwned(
            SovereignOpsWorkerLease(
                leaseName = "test-lease",
                ownerId = "worker-a",
                acquiredAt = BASE_NOW,
                expiresAt = BASE_NOW.plus(LEASE_DURATION),
                heartbeatAt = BASE_NOW,
                version = 2,
            ),
        )

        val summary = worker().runOnce()
        assertThat(operations.recoverCalled).isTrue
        assertThat(summary.skipped).isNull()
    }

    @Test
    fun `held by other does not invoke delegate`() = runBlocking {
        leaseStore.acquireResult = SovereignOpsWorkerLeaseAcquisition.HeldByOther(
            SovereignOpsWorkerLease(
                leaseName = "test-lease",
                ownerId = "worker-b",
                acquiredAt = BASE_NOW,
                expiresAt = BASE_NOW.plus(LEASE_DURATION),
                heartbeatAt = BASE_NOW,
                version = 1,
            ),
        )

        val summary = worker().runOnce()
        assertThat(operations.recoverCalled).isFalse
        assertThat(summary.skipped).isNotNull
        assertThat(summary.skipped!!.reason).isEqualTo("lease-held-by-other")
        assertThat(summary.recovered).isNull()
        assertThat(summary.dispatched).isNull()
    }

    @Test
    fun `heartbeat is called during run`() = runBlocking {
        leaseStore.acquireResult = SovereignOpsWorkerLeaseAcquisition.Acquired(
            SovereignOpsWorkerLease(
                leaseName = "test-lease",
                ownerId = "worker-a",
                acquiredAt = BASE_NOW,
                expiresAt = BASE_NOW.plus(LEASE_DURATION),
                heartbeatAt = BASE_NOW,
                version = 1,
            ),
        )

        // Make the delegate take long enough for the heartbeat coroutine to fire.
        operations.beforeRun = { kotlinx.coroutines.delay(50) }

        worker().runOnce()
        assertThat(leaseStore.heartbeatCalled).isTrue
    }

    @Test
    fun `lost lease during run cancels delegate`() = runBlocking {
        leaseStore.acquireResult = SovereignOpsWorkerLeaseAcquisition.Acquired(
            SovereignOpsWorkerLease(
                leaseName = "test-lease",
                ownerId = "worker-a",
                acquiredAt = BASE_NOW,
                expiresAt = BASE_NOW.plus(LEASE_DURATION),
                heartbeatAt = BASE_NOW,
                version = 1,
            ),
        )
        leaseStore.heartbeatResult = SovereignOpsWorkerLeaseHeartbeat.Expired

        var cancelledDuringRun = false
        operations.beforeRun = {
            try {
                delay(200)  // long enough for heartbeat coroutine to fire
            } finally {
                cancelledDuringRun = true
            }
        }

        val thrown = try {
            worker().runOnce()
            throw AssertionError("expected SovereignOpsWorkerLeaseLostException")
        } catch (e: SovereignOpsWorkerLeaseLostException) {
            e
        }

        assertThat(thrown.message).contains("tramai-sovereign-ops-worker-lease-lost")
        assertThat(cancelledDuringRun).isTrue()
    }

    // ── Fakes ──────────────────────────────────────────────────────────

    class TrackingOperations : SovereignOpsAuditOutboxOperations {
        var recoverCalled = false
        var dispatchCalled = false
        var beforeRun: (suspend () -> Unit)? = null

        override suspend fun recoverPrepared(limit: Int?): SovereignOpsAuditOutboxRecoverySummary {
            beforeRun?.invoke()
            recoverCalled = true
            return SovereignOpsAuditOutboxRecoverySummary(
                inspected = 0,
            )
        }

        override suspend fun retryPending(limit: Int?): SovereignOpsAuditOutboxDispatchResult {
            dispatchCalled = true
            return SovereignOpsAuditOutboxDispatchResult(
                claimed = 0,
                emitted = 0,
                failedRetryable = 0,
                failedPermanent = 0,
            )
        }

        override suspend fun listOutboxRecords(
            status: SovereignOpsAuditOutboxStatus?,
            limit: Int?,
        ): List<SovereignOpsAuditOutboxSummary> = emptyList()

        override suspend fun markPreparedFailed(
            outboxId: String,
            reason: String,
        ): SovereignOpsAuditOutboxSummary = throw UnsupportedOperationException()
    }

    class FakeLeaseStore : SovereignOpsWorkerLeaseStore {
        var acquireResult: SovereignOpsWorkerLeaseAcquisition? = null
        var heartbeatCalled = false
        /** If set, heartbeat returns this instead of Extended. */
        var heartbeatResult: SovereignOpsWorkerLeaseHeartbeat? = null

        override suspend fun tryAcquire(
            leaseName: String,
            ownerId: String,
            now: Instant,
            leaseDuration: Duration,
        ): SovereignOpsWorkerLeaseAcquisition =
            acquireResult ?: throw IllegalStateException("acquireResult not set")

        override suspend fun heartbeat(
            leaseName: String,
            ownerId: String,
            now: Instant,
            leaseDuration: Duration,
        ): SovereignOpsWorkerLeaseHeartbeat {
            heartbeatCalled = true
            heartbeatResult?.let { return it }
            return SovereignOpsWorkerLeaseHeartbeat.Extended(
                SovereignOpsWorkerLease(
                    leaseName = leaseName,
                    ownerId = ownerId,
                    acquiredAt = now,
                    expiresAt = now.plus(leaseDuration),
                    heartbeatAt = now,
                    version = 1,
                ),
            )
        }

        override suspend fun release(
            leaseName: String,
            ownerId: String,
            now: Instant,
        ): SovereignOpsWorkerLeaseRelease = SovereignOpsWorkerLeaseRelease.Released

        override suspend fun get(leaseName: String): SovereignOpsWorkerLease? = null
    }
}
