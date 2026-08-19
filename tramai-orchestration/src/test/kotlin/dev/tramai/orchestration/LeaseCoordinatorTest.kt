package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class LeaseCoordinatorTest {

    private val config = WorkerConfig(
        workerId = "lease-test",
        poolName = "tests",
        pollIntervalMillis = 20,
        leaseDurationMillis = 5_000,
        drainTimeoutMillis = 1_000,
    )

    private class RecordingObserver : TramaiWorkerObserver {
        val acquired = CopyOnWriteArrayList<String>()
        val contested = CopyOnWriteArrayList<String>()
        val released = CopyOnWriteArrayList<String>()
        val releaseFailed = CopyOnWriteArrayList<String>()

        override fun onLeaseAcquired(workflowId: String, workerId: String) {
            acquired += workflowId
        }

        override fun onLeaseContested(workflowId: String, claimantWorkerId: String, currentWorkerId: String) {
            contested += workflowId
        }

        override fun onLeaseReleased(workflowId: String, workerId: String) {
            released += workflowId
        }

        override fun onLeaseReleaseFailed(workflowId: String, workerId: String, error: Throwable) {
            releaseFailed += workflowId
        }
    }

    private fun checkpoint(
        name: String = "wf",
        id: String = "w-1",
        revision: Long = 1L,
    ): WorkflowCheckpoint = WorkflowCheckpoint(
        workflowName = name,
        workflowId = id,
        nextStepIndex = 0,
        stepExecutions = 0,
        lastCompletedStepName = null,
        statePayload = "start",
        metadata = mapOf(WORKFLOW_DEFINITION_VERSION_METADATA_KEY to "v1"),
        revision = revision,
    )

    @Test
    fun `successful claim returns the lease and emits onLeaseAcquired`() {
        val store = InMemoryWorkflowLeaseStore()
        val observer = RecordingObserver()
        val coordinator = LeaseCoordinator(config, store, observer)
        val lease = runBlocking { coordinator.claim(checkpoint()) }
        assertThat(lease).isNotNull
        assertThat(lease?.ownerId).isEqualTo("lease-test")
        assertThat(observer.acquired).containsExactly("w-1")
        assertThat(observer.contested).isEmpty()
    }

    @Test
    fun `contended claim returns null and emits onLeaseContested`() {
        val store = InMemoryWorkflowLeaseStore()
        val observer = RecordingObserver()
        runBlocking {
            store.claim("wf", "w-1", "other-owner", 1L, 5_000)
        }
        val coordinator = LeaseCoordinator(config, store, observer)
        val lease = runBlocking { coordinator.claim(checkpoint()) }
        assertThat(lease).isNull()
        assertThat(observer.contested).containsExactly("w-1")
        assertThat(observer.acquired).isEmpty()
    }

    @Test
    fun `currentLease reports the store lease`() {
        val store = InMemoryWorkflowLeaseStore()
        val coordinator = LeaseCoordinator(config, store, RecordingObserver())
        runBlocking {
            store.claim("wf", "w-1", "owner-x", 1L, 5_000)
        }
        val current = runBlocking { coordinator.currentLease("wf", "w-1") }
        assertThat(current?.ownerId).isEqualTo("owner-x")
    }

    @Test
    fun `successful release returns true and emits onLeaseReleased`() {
        val store = InMemoryWorkflowLeaseStore()
        val observer = RecordingObserver()
        val coordinator = LeaseCoordinator(config, store, observer)
        val lease = runBlocking { store.claim("wf", "w-1", "lease-test", 1L, 5_000) }
        val released = runBlocking { coordinator.release(lease) }
        assertThat(released).isTrue()
        assertThat(observer.released).containsExactly("w-1")
        assertThat(runBlocking { store.currentLease("wf", "w-1") }).isNull()
    }

    @Test
    fun `release ordering is store release then tracked-lease clear then observer event`() {
        val store = InMemoryWorkflowLeaseStore()
        val observer = RecordingObserver()
        val coordinator = LeaseCoordinator(config, store, observer)
        val lease = runBlocking { store.claim("wf", "w-1", "lease-test", 1L, 5_000) }
        val order = CopyOnWriteArrayList<String>()
        val released = runBlocking {
            coordinator.release(lease) {
                // The owner clears its tracked lease here; must happen after the
                // store release and before the observer event (the pre-#251 order),
                // so a slow observer callback can never renew a released lease.
                order += "clear-tracked-lease"
                assertThat(runBlocking { store.currentLease("wf", "w-1") }).isNull()
            }
        }
        assertThat(released).isTrue()
        assertThat(observer.released).containsExactly("w-1")
        assertThat(order).containsExactly("clear-tracked-lease")
        assertThat(observer.released).isNotEmpty()
    }

    @Test
    fun `failed release returns false and emits onLeaseReleaseFailed`() {
        val failingStore = FailingLeaseStore(releaseError = IllegalStateException("store down"))
        val observer = RecordingObserver()
        val coordinator = LeaseCoordinator(config, failingStore, observer)
        val lease = WorkflowLease(
            workflowName = "wf",
            workflowId = "w-1",
            leaseId = "l-1",
            ownerId = "lease-test",
            checkpointRevision = 1L,
            acquiredAtEpochMillis = 0L,
            expiresAtEpochMillis = 5_000L,
        )
        val released = runBlocking { coordinator.release(lease) }
        assertThat(released).isFalse()
        assertThat(observer.releaseFailed).containsExactly("w-1")
        assertThat(observer.released).isEmpty()
    }

    @Test
    fun `cancellation during release escapes instead of becoming a release failure`() {
        val failingStore = FailingLeaseStore(releaseError = CancellationException("cancelled"))
        val observer = RecordingObserver()
        val coordinator = LeaseCoordinator(config, failingStore, observer)
        val lease = WorkflowLease(
            workflowName = "wf",
            workflowId = "w-1",
            leaseId = "l-1",
            ownerId = "lease-test",
            checkpointRevision = 1L,
            acquiredAtEpochMillis = 0L,
            expiresAtEpochMillis = 5_000L,
        )
        assertThatThrownBy { runBlocking { coordinator.release(lease) } }
            .isInstanceOf(CancellationException::class.java)
        assertThat(observer.releaseFailed).isEmpty()
    }

    private class FailingLeaseStore(
        private val releaseError: Throwable? = null,
    ) : WorkflowLeaseStore {
        private val leases = mutableMapOf<String, WorkflowLease>()

        override suspend fun currentLease(workflowName: String, workflowId: String): WorkflowLease? =
            leases[workflowName + "/" + workflowId]

        override suspend fun claim(
            workflowName: String,
            workflowId: String,
            ownerId: String,
            checkpointRevision: Long?,
            leaseDurationMillis: Long,
        ): WorkflowLease {
            val key = workflowName + "/" + workflowId
            val existing = leases[key]
            if (existing != null) {
                throw WorkflowLeaseConflictException("Lease already held by ${existing.ownerId}")
            }
            return WorkflowLease(
                workflowName = workflowName,
                workflowId = workflowId,
                leaseId = "l-" + workflowId,
                ownerId = ownerId,
                checkpointRevision = checkpointRevision,
                acquiredAtEpochMillis = 0L,
                expiresAtEpochMillis = leaseDurationMillis,
            ).also { leases[key] = it }
        }

        override suspend fun renew(
            lease: WorkflowLease,
            checkpointRevision: Long?,
            leaseDurationMillis: Long,
        ): WorkflowLease = lease.copy(
            checkpointRevision = checkpointRevision,
            expiresAtEpochMillis = lease.expiresAtEpochMillis + leaseDurationMillis,
        ).also { leases[lease.workflowName + "/" + lease.workflowId] = it }

        override suspend fun release(lease: WorkflowLease) {
            releaseError?.let { throw it }
            leases.remove(lease.workflowName + "/" + lease.workflowId)
        }
    }
}
