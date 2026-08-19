package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class LeaseRenewalLoopTest {

    private val config = WorkerConfig(
        workerId = "renew-test",
        poolName = "tests",
        pollIntervalMillis = 20,
        leaseDurationMillis = 60,
        drainTimeoutMillis = 1_000,
    )

    private class RecordingObserver : TramaiWorkerObserver {
        val renewed = CopyOnWriteArrayList<String>()
        val expired = CopyOnWriteArrayList<String>()
        val renewalFailed = CopyOnWriteArrayList<String>()

        override fun onLeaseRenewed(workflowId: String, workerId: String, newExpiry: Long) {
            renewed += workflowId
        }

        override fun onLeaseExpired(workflowId: String, workerId: String) {
            expired += workflowId
        }

        override fun onLeaseRenewalFailed(workflowId: String, workerId: String, error: Throwable) {
            renewalFailed += workflowId
        }
    }

    private fun handle(
        store: WorkflowLeaseStore,
        observer: RecordingObserver,
        lease: WorkflowLease,
    ): ActiveExecution = ActiveExecution(
        workflowName = lease.workflowName,
        workflowId = lease.workflowId,
        lease = AtomicReference(lease),
    )

    private fun lease(): WorkflowLease = WorkflowLease(
        workflowName = "wf",
        workflowId = "w-1",
        leaseId = "l-1",
        ownerId = "renew-test",
        checkpointRevision = 1L,
        acquiredAtEpochMillis = 0L,
        expiresAtEpochMillis = 60L,
    )

    private class RecordingLeaseStore(
        private val renewBehavior: (WorkflowLease, Long?) -> WorkflowLease,
    ) : WorkflowLeaseStore {
        val renewCalls = CopyOnWriteArrayList<Pair<String, Long?>>()

        override suspend fun currentLease(workflowName: String, workflowId: String): WorkflowLease? = null

        override suspend fun claim(
            workflowName: String,
            workflowId: String,
            ownerId: String,
            checkpointRevision: Long?,
            leaseDurationMillis: Long,
        ): WorkflowLease = throw UnsupportedOperationException()

        override suspend fun renew(
            lease: WorkflowLease,
            checkpointRevision: Long?,
            leaseDurationMillis: Long,
        ): WorkflowLease {
            renewCalls += lease.leaseId to checkpointRevision
            return renewBehavior(lease, checkpointRevision)
        }

        override suspend fun release(lease: WorkflowLease) = Unit
    }

    @Test
    fun `renews before expiry and replaces the tracked lease`() {
        val store = RecordingLeaseStore { lease, _ -> lease.copy(expiresAtEpochMillis = lease.expiresAtEpochMillis + 60) }
        val observer = RecordingObserver()
        val loop = LeaseRenewalLoop(config, store, observer)
        val handle = handle(store, observer, lease())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch { loop.renew(handle) }
        runBlocking {
            withTimeout(5_000) {
                while (observer.renewed.isEmpty()) delay(5)
            }
        }
        assertThat(handle.lease.get()?.expiresAtEpochMillis).isGreaterThan(60L)
        assertThat(observer.renewed).containsExactly("w-1")
        job.cancel()
        scope.cancel()
    }

    @Test
    fun `renewal uses the execution latest checkpoint revision`() {
        val store = RecordingLeaseStore { lease, _ -> lease.copy(expiresAtEpochMillis = lease.expiresAtEpochMillis + 60) }
        val observer = RecordingObserver()
        val loop = LeaseRenewalLoop(config, store, observer)
        val handle = handle(store, observer, lease()).apply {
            lastRevision.set(42L)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch { loop.renew(handle) }
        runBlocking {
            withTimeout(5_000) {
                while (store.renewCalls.isEmpty()) delay(5)
            }
        }
        assertThat(store.renewCalls.first().second).isEqualTo(42L)
        job.cancel()
        scope.cancel()
    }

    @Test
    fun `a null lease ends the loop without events`() {
        val store = RecordingLeaseStore { lease, _ -> lease }
        val observer = RecordingObserver()
        val loop = LeaseRenewalLoop(config, store, observer)
        val handle = handle(store, observer, lease()).apply {
            lease.set(null)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch { loop.renew(handle) }
        runBlocking {
            withTimeout(5_000) { job.join() }
        }
        assertThat(observer.renewed).isEmpty()
        assertThat(observer.renewalFailed).isEmpty()
        scope.cancel()
    }

    @Test
    fun `cancellation of the renewal job exits cleanly without a renewal failure`() {
        val store = RecordingLeaseStore { lease, _ -> lease.copy(expiresAtEpochMillis = lease.expiresAtEpochMillis + 60) }
        val observer = RecordingObserver()
        val loop = LeaseRenewalLoop(config, store, observer)
        val handle = handle(store, observer, lease())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch { loop.renew(handle) }
        runBlocking {
            withTimeout(5_000) {
                while (observer.renewed.isEmpty()) delay(5)
            }
            job.cancel()
            withTimeout(5_000) { job.join() }
        }
        assertThat(observer.renewalFailed).isEmpty()
        scope.cancel()
    }

    @Test
    fun `renewal conflict marks the lease lost and cancels the execution`() {
        val store = RecordingLeaseStore { _, _ -> throw WorkflowLeaseConflictException("lost") }
        val observer = RecordingObserver()
        val loop = LeaseRenewalLoop(config, store, observer)
        val handle = handle(store, observer, lease())
        val executionJob: CompletableJob = SupervisorJob()
        handle.executionJob = executionJob
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch { loop.renew(handle) }
        runBlocking {
            withTimeout(5_000) {
                while (observer.expired.isEmpty()) delay(5)
            }
            withTimeout(5_000) { job.join() }
        }
        assertThat(observer.expired).containsExactly("w-1")
        assertThat(handle.lease.get()).isNull()
        assertThat(executionJob.isCancelled).isTrue()
        scope.cancel()
    }

    @Test
    fun `transient renewal failure retries and does not end the loop`() {
        val store = RecordingLeaseStore { lease, _ -> throw IllegalStateException("transient") }
        val observer = RecordingObserver()
        val loop = LeaseRenewalLoop(config, store, observer)
        val handle = handle(store, observer, lease())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch { loop.renew(handle) }
        runBlocking {
            withTimeout(5_000) {
                while (observer.renewalFailed.size < 2) delay(5)
            }
            job.cancel()
        }
        assertThat(observer.renewalFailed).hasSizeGreaterThanOrEqualTo(2)
        assertThat(observer.expired).isEmpty()
        assertThat(handle.lease.get()).isNotNull
        scope.cancel()
    }

    @Test
    fun `cancellation is not converted into a renewal failure`() {
        val store = RecordingLeaseStore { lease, _ -> throw CancellationException("cancelled") }
        val observer = RecordingObserver()
        val loop = LeaseRenewalLoop(config, store, observer)
        val handle = handle(store, observer, lease())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch { loop.renew(handle) }
        runBlocking {
            withTimeout(5_000) {
                // The store throws CancellationException from the first renew; the
                // loop rethrows it, so the coroutine completes exceptionally without
                // any renewal-failure event.
                while (!job.isCompleted) delay(5)
            }
        }
        assertThat(observer.renewalFailed).isEmpty()
        assertThat(observer.expired).isEmpty()
        scope.cancel()
    }
}
