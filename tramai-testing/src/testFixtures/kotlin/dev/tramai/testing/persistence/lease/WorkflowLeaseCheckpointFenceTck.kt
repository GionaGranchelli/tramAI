package dev.tramai.testing.persistence.lease

import dev.tramai.orchestration.StaleWorkflowLeaseException
import dev.tramai.orchestration.WorkflowCheckpoint
import dev.tramai.orchestration.WorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowLease
import dev.tramai.orchestration.WorkflowLeaseCheckpointFence
import dev.tramai.orchestration.WorkflowLeaseStore
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1g: the companion
 * [dev.tramai.orchestration.WorkflowLeaseCheckpointFence] compatibility
 * contract — a distinct optional SPI the built-in lease stores implement
 * and the worker depends on. The fence establishes "this active lease token
 * still owns the workflow" and makes the ownership check + checkpoint
 * mutation one atomic fencing operation. It is NOT a renewal API and must
 * not mutate lease metadata.
 *
 * Stale fencing is `StaleWorkflowLeaseException` with exactly
 * "Workflow lease is no longer active" — semantically distinct from the
 * ordinary claim/renew/release conflict.
 */
abstract class WorkflowLeaseCheckpointFenceTck {

    /** Fresh isolated lease + checkpoint storage and a deterministic clock per case. */
    protected abstract fun newHarness(): Harness

    class Harness(
        val clock: MutableMillisClock,
        val leaseStore: WorkflowLeaseStore,
        val fence: WorkflowLeaseCheckpointFence,
        val checkpointStore: WorkflowCheckpointStore,
    )

    private fun checkpoint(
        workflowName: String,
        workflowId: String,
        nextStepIndex: Int = 3,
        revision: Long = 0,
    ): WorkflowCheckpoint = WorkflowCheckpoint(
        workflowName = workflowName,
        workflowId = workflowId,
        nextStepIndex = nextStepIndex,
        stepExecutions = 1,
        lastCompletedStepName = "validate",
        statePayload = """{"state":"review"}""",
        revision = revision,
        metadata = emptyMap(),
        savedAtEpochMillis = 1_800_000_000_000L,
    )

    private fun assertStale(thrown: Throwable?) {
        assertThat(thrown).isInstanceOf(StaleWorkflowLeaseException::class.java)
        assertThat(thrown?.message).isEqualTo("Workflow lease is no longer active")
        assertThat(thrown?.cause).isNull()
    }

    @Test
    fun `fenced checkpoint save succeeds for the active owner`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
        val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
        val saved = h.fence.saveCheckpointIfLeaseOwner(
            checkpointStore = h.checkpointStore,
            checkpoint = checkpoint("invoice-review", "run-001", revision = 1, nextStepIndex = 4),
            expectedRevision = 1,
            expectedLease = lease,
        )
        assertThat(saved.revision).isEqualTo(2)
        assertThat(h.checkpointStore.load("invoice-review", "run-001")?.nextStepIndex).isEqualTo(4)
        assertThat(h.leaseStore.currentLease("invoice-review", "run-001")).isEqualTo(lease)
    }

    @Test
    fun `fenced checkpoint delete succeeds for the active owner`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
        val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
        h.fence.deleteCheckpointIfLeaseOwner(
            checkpointStore = h.checkpointStore,
            workflowName = "invoice-review",
            workflowId = "run-001",
            expectedRevision = 1,
            expectedLease = lease,
        )
        assertThat(h.checkpointStore.load("invoice-review", "run-001")).isNull()
        assertThat(h.leaseStore.currentLease("invoice-review", "run-001")).isEqualTo(lease)
    }

    @Test
    fun `fence with an expired lease is stale and leaves the checkpoint unchanged`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
        val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
        h.clock.advance(2_000)
        assertStale(runCatching {
            h.fence.saveCheckpointIfLeaseOwner(
                checkpointStore = h.checkpointStore,
                checkpoint = checkpoint("invoice-review", "run-001", revision = 1, nextStepIndex = 9),
                expectedRevision = 1,
                expectedLease = lease,
            )
        }.exceptionOrNull())
        assertThat(h.checkpointStore.load("invoice-review", "run-001")?.nextStepIndex).isEqualTo(3)
    }

    @Test
    fun `fence with a replaced lease is stale and leaves the checkpoint unchanged`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
        val old = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
        h.clock.advance(2_000)
        h.leaseStore.claim("invoice-review", "run-001", "worker-9", checkpointRevision = 1, leaseDurationMillis = 1_000)
        assertStale(runCatching {
            h.fence.saveCheckpointIfLeaseOwner(
                checkpointStore = h.checkpointStore,
                checkpoint = checkpoint("invoice-review", "run-001", revision = 1, nextStepIndex = 9),
                expectedRevision = 1,
                expectedLease = old,
            )
        }.exceptionOrNull())
        assertThat(h.checkpointStore.load("invoice-review", "run-001")?.nextStepIndex).isEqualTo(3)
    }

    @Test
    fun `fence with the wrong lease id is stale`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
        val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
        assertStale(runCatching {
            h.fence.saveCheckpointIfLeaseOwner(
                checkpointStore = h.checkpointStore,
                checkpoint = checkpoint("invoice-review", "run-001", revision = 1),
                expectedRevision = 1,
                expectedLease = lease.copy(leaseId = "forged-token"),
            )
        }.exceptionOrNull())
    }

    @Test
    fun `fence with the wrong owner is stale`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
        val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
        assertStale(runCatching {
            h.fence.saveCheckpointIfLeaseOwner(
                checkpointStore = h.checkpointStore,
                checkpoint = checkpoint("invoice-review", "run-001", revision = 1),
                expectedRevision = 1,
                expectedLease = lease.copy(ownerId = "forged-owner"),
            )
        }.exceptionOrNull())
    }

    @Test
    fun `fence with a missing lease is stale`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
        assertStale(runCatching {
            h.fence.saveCheckpointIfLeaseOwner(
                checkpointStore = h.checkpointStore,
                checkpoint = checkpoint("invoice-review", "run-001", revision = 1),
                expectedRevision = 1,
                expectedLease = WorkflowLeaseFixtures.lease(),
            )
        }.exceptionOrNull())
        assertThat(h.checkpointStore.load("invoice-review", "run-001")).isNotNull
    }

    @Test
    fun `fence binds lease identity to checkpoint identity on save`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("workflow-b", "id-1", revision = 1))
        val lease = h.leaseStore.claim("workflow-a", "id-1", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
        assertThat(runCatching {
            h.fence.saveCheckpointIfLeaseOwner(
                checkpointStore = h.checkpointStore,
                checkpoint = checkpoint("workflow-b", "id-1", revision = 1, nextStepIndex = 9),
                expectedRevision = 1,
                expectedLease = lease,
            )
        }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(h.checkpointStore.load("workflow-b", "id-1")?.nextStepIndex).isEqualTo(3)
        assertThat(h.leaseStore.currentLease("workflow-a", "id-1")).isEqualTo(lease)
    }

    @Test
    fun `fence binds lease identity to checkpoint identity on delete`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("workflow-a", "id-2", revision = 1))
        val lease = h.leaseStore.claim("workflow-a", "id-1", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
        assertThat(runCatching {
            h.fence.deleteCheckpointIfLeaseOwner(
                checkpointStore = h.checkpointStore,
                workflowName = "workflow-a",
                workflowId = "id-2",
                expectedRevision = 1,
                expectedLease = lease,
            )
        }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(h.checkpointStore.load("workflow-a", "id-2")).isNotNull
        assertThat(h.leaseStore.currentLease("workflow-a", "id-1")).isEqualTo(lease)
    }

    @Test
    fun `fence does not renew or extend the lease`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
        val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
        h.clock.advance(500)
        h.fence.saveCheckpointIfLeaseOwner(
            checkpointStore = h.checkpointStore,
            checkpoint = checkpoint("invoice-review", "run-001", revision = 1),
            expectedRevision = 1,
            expectedLease = lease,
        )
        h.clock.advance(500)
        // Still inside the original window at T0+1000? No: T0+1000 is exact
        // expiry, so the lease must be gone — the fence never extended it.
        assertThat(h.leaseStore.currentLease("invoice-review", "run-001")).isNull()
    }

    @Test
    fun `fence does not mutate durable lease metadata`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
        val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 3, leaseDurationMillis = 1_000)
        // The caller's snapshot is a capability token; the fence must not
        // write its checkpointRevision into the durable lease.
        val tampered = lease.copy(checkpointRevision = 999)
        h.fence.saveCheckpointIfLeaseOwner(
            checkpointStore = h.checkpointStore,
            checkpoint = checkpoint("invoice-review", "run-001", revision = 1, nextStepIndex = 4),
            expectedRevision = 1,
            expectedLease = tampered,
        )
        assertThat(h.checkpointStore.load("invoice-review", "run-001")?.nextStepIndex).isEqualTo(4)
        assertThat(h.leaseStore.currentLease("invoice-review", "run-001")?.checkpointRevision).isEqualTo(3)
    }

    @Test
    fun `fence works with a null checkpoint revision lease`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
        val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = null, leaseDurationMillis = 1_000)
        h.fence.saveCheckpointIfLeaseOwner(
            checkpointStore = h.checkpointStore,
            checkpoint = checkpoint("invoice-review", "run-001", revision = 1, nextStepIndex = 4),
            expectedRevision = 1,
            expectedLease = lease,
        )
        assertThat(h.checkpointStore.load("invoice-review", "run-001")?.nextStepIndex).isEqualTo(4)
    }

    @Test
    fun `fence delete with stale lease is stale and leaves the checkpoint`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
        val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
        h.clock.advance(2_000)
        assertStale(runCatching {
            h.fence.deleteCheckpointIfLeaseOwner(
                checkpointStore = h.checkpointStore,
                workflowName = "invoice-review",
                workflowId = "run-001",
                expectedRevision = 1,
                expectedLease = lease,
            )
        }.exceptionOrNull())
        assertThat(h.checkpointStore.load("invoice-review", "run-001")).isNotNull
    }

    @Test
    fun `fence works with a tampered acquiredAt snapshot`() = runBlocking<Unit> {
        val h = newHarness()
        h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
        val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
        val tampered = lease.copy(acquiredAtEpochMillis = 42, expiresAtEpochMillis = 43)
        val saved = h.fence.saveCheckpointIfLeaseOwner(
            checkpointStore = h.checkpointStore,
            checkpoint = checkpoint("invoice-review", "run-001", revision = 1),
            expectedRevision = 1,
            expectedLease = tampered,
        )
        assertThat(saved.revision).isEqualTo(2)
    }
}
