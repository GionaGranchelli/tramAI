@file:Suppress("MaxLineLength", "FunctionNaming", "ktlint:standard:max-line-length")

// A test-fixture contract: backticked case names are the spec, and ktfmt rejoins the signatures detekt rejects.

package dev.tramai.testing.persistence.lease

import dev.tramai.orchestration.StaleWorkflowLeaseException
import dev.tramai.orchestration.WorkflowAdmissionRequest
import dev.tramai.orchestration.WorkflowCancellationRequest
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

    /**
     * Whether the harness's checkpoint store holds a durable admission at all. A local-only store records neither an
     * admission counter nor a lifecycle status, so there the fence's only admissible answer is a refusal.
     */
    protected open val durableAdmissionSupported: Boolean get() = true

    /** Whether the harness's checkpoint store holds authoritative terminal cancellation. */
    protected open val durableCancellationSupported: Boolean get() = true

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
    ): WorkflowCheckpoint =
        WorkflowCheckpoint(
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

    /**
     * Persist a fresh checkpoint and return the persisted snapshot. The
     * returned value is the actor's captured authority: generation + revision
     * as minted by the store (a raw [checkpoint] carries null generation and
     * can never update a generated record).
     */
    private suspend fun Harness.seedCheckpoint(
        workflowName: String,
        workflowId: String,
        nextStepIndex: Int = 3,
        revision: Long = 1,
    ): WorkflowCheckpoint =
        checkpointStore.save(
            checkpoint(workflowName, workflowId, nextStepIndex = nextStepIndex, revision = revision),
        )

    private fun assertStale(thrown: Throwable?) {
        assertThat(thrown).isInstanceOf(StaleWorkflowLeaseException::class.java)
        assertThat(thrown?.message).isEqualTo("Workflow lease is no longer active")
        assertThat(thrown?.cause).isNull()
    }

    @Test
    fun `fenced checkpoint save succeeds for the active owner`() =
        runBlocking<Unit> {
            val h = newHarness()
            val persisted = h.seedCheckpoint("invoice-review", "run-001")
            val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            val saved =
                h.fence.saveCheckpointIfLeaseOwner(
                    checkpointStore = h.checkpointStore,
                    checkpoint =
                        checkpoint("invoice-review", "run-001", revision = 1, nextStepIndex = 4).copy(
                            checkpointGeneration = persisted.checkpointGeneration,
                        ),
                    expectedRevision = persisted.revision,
                    expectedLease = lease,
                )
            assertThat(saved.revision).isEqualTo(2)
            assertThat(h.checkpointStore.load("invoice-review", "run-001")?.nextStepIndex).isEqualTo(4)
            assertThat(h.leaseStore.currentLease("invoice-review", "run-001")).isEqualTo(lease)
        }

    // E10: an admission authorizes an external effect, so it is fenced on lease ownership in the same atomic step as
    // the checkpoint write. These cases are the contract every store family must meet.

    @Test
    fun `fenced admission succeeds for the active owner`() =
        runBlocking<Unit> {
            val h = newHarness()
            val persisted = h.seedCheckpoint("invoice-review", "run-001")
            val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)

            if (!durableAdmissionSupported) {
                // No admission authority behind the fence: it must refuse rather than become a silent success.
                val refused = runCatching { h.fence.admitIfLeaseOwner(h.checkpointStore, admissionRequest(persisted), lease) }
                assertThat(refused.isFailure || refused.getOrNull() == null).isTrue()
                return@runBlocking
            }

            val admission = h.fence.admitIfLeaseOwner(h.checkpointStore, admissionRequest(persisted), lease)

            assertThat(admission).isNotNull
            assertThat(admission!!.admitted).isTrue()
            assertThat(admission.sequence).isEqualTo(1L)
        }

    @Test
    fun `a superseded lease cannot take an admission and consumes no sequence`() =
        runBlocking<Unit> {
            val h = newHarness()
            val persisted = h.seedCheckpoint("invoice-review", "run-001")
            val first = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            h.clock.advance(2_000)
            val second = h.leaseStore.claim("invoice-review", "run-001", "worker-8", checkpointRevision = 1, leaseDurationMillis = 1_000)

            val refused = h.fence.admitIfLeaseOwner(h.checkpointStore, admissionRequest(persisted), first)

            assertThat(refused).isNull()
            if (durableAdmissionSupported) {
                // The counting authority is untouched: the new owner's first admission is still sequence 1.
                val granted = h.fence.admitIfLeaseOwner(h.checkpointStore, admissionRequest(persisted), second)
                assertThat(granted?.sequence).isEqualTo(1L)
            }
        }

    @Test
    fun `an admission presented by the wrong owner is never granted`() =
        runBlocking<Unit> {
            val h = newHarness()
            val persisted = h.seedCheckpoint("invoice-review", "run-001")
            val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            // Same lease id, different owner: a forgery that only an owner-blind check would accept.
            val forged = lease.copy(ownerId = "worker-9")

            val outcome = runCatching { h.fence.admitIfLeaseOwner(h.checkpointStore, admissionRequest(persisted), forged) }

            assertThat(outcome.getOrNull()?.admitted).isNotEqualTo(true)
        }

    @Test
    fun `fenced admission preserves the run's durable cancellation refusal`() =
        runBlocking<Unit> {
            val h = newHarness()
            val persisted = h.seedCheckpoint("invoice-review", "run-001")
            org.junit.jupiter.api.Assumptions.assumeTrue(
                durableCancellationSupported,
                "this checkpoint store has no authoritative cancellation to preserve",
            )
            val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            h.checkpointStore.cancel(
                WorkflowCancellationRequest(
                    workflowName = "invoice-review",
                    workflowId = "run-001",
                    expectedRevision = persisted.revision,
                    expectedGeneration = persisted.checkpointGeneration,
                    actor = "operator",
                    reason = "stopped",
                    cancelledAtEpochMillis = 0L,
                ),
            )

            val admission = h.fence.admitIfLeaseOwner(h.checkpointStore, admissionRequest(persisted), lease)

            assertThat(admission).isNotNull
            assertThat(admission!!.admitted).isFalse()
            assertThat(admission.cancellation).isNotNull
        }

    private fun admissionRequest(persisted: WorkflowCheckpoint): WorkflowAdmissionRequest =
        WorkflowAdmissionRequest(
            workflowName = persisted.workflowName,
            workflowId = persisted.workflowId,
            expectedRevision = persisted.revision,
            expectedGeneration = persisted.checkpointGeneration,
        )

    @Test
    fun `fenced checkpoint delete succeeds for the active owner`() =
        runBlocking<Unit> {
            val h = newHarness()
            val persisted = h.seedCheckpoint("invoice-review", "run-001")
            val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            h.fence.deleteCheckpointIfLeaseOwner(
                checkpointStore = h.checkpointStore,
                workflowName = "invoice-review",
                workflowId = "run-001",
                expectedRevision = persisted.revision,
                expectedGeneration = persisted.checkpointGeneration,
                expectedLease = lease,
            )
            assertThat(h.checkpointStore.load("invoice-review", "run-001")).isNull()
            assertThat(h.leaseStore.currentLease("invoice-review", "run-001")).isEqualTo(lease)
        }

    @Test
    fun `fence with an expired lease is stale and leaves the checkpoint unchanged`() =
        runBlocking<Unit> {
            val h = newHarness()
            h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
            val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            h.clock.advance(2_000)
            assertStale(
                runCatching {
                    h.fence.saveCheckpointIfLeaseOwner(
                        checkpointStore = h.checkpointStore,
                        checkpoint = checkpoint("invoice-review", "run-001", revision = 1, nextStepIndex = 9),
                        expectedRevision = 1,
                        expectedLease = lease,
                    )
                }.exceptionOrNull(),
            )
            assertThat(h.checkpointStore.load("invoice-review", "run-001")?.nextStepIndex).isEqualTo(3)
        }

    @Test
    fun `fence with a replaced lease is stale and leaves the checkpoint unchanged`() =
        runBlocking<Unit> {
            val h = newHarness()
            h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
            val old = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            h.clock.advance(2_000)
            h.leaseStore.claim("invoice-review", "run-001", "worker-9", checkpointRevision = 1, leaseDurationMillis = 1_000)
            assertStale(
                runCatching {
                    h.fence.saveCheckpointIfLeaseOwner(
                        checkpointStore = h.checkpointStore,
                        checkpoint = checkpoint("invoice-review", "run-001", revision = 1, nextStepIndex = 9),
                        expectedRevision = 1,
                        expectedLease = old,
                    )
                }.exceptionOrNull(),
            )
            assertThat(h.checkpointStore.load("invoice-review", "run-001")?.nextStepIndex).isEqualTo(3)
        }

    @Test
    fun `fence with the wrong lease id is stale`() =
        runBlocking<Unit> {
            val h = newHarness()
            h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
            val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            assertStale(
                runCatching {
                    h.fence.saveCheckpointIfLeaseOwner(
                        checkpointStore = h.checkpointStore,
                        checkpoint = checkpoint("invoice-review", "run-001", revision = 1),
                        expectedRevision = 1,
                        expectedLease = lease.copy(leaseId = "forged-token"),
                    )
                }.exceptionOrNull(),
            )
        }

    @Test
    fun `fence with the wrong owner is stale`() =
        runBlocking<Unit> {
            val h = newHarness()
            h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
            val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            assertStale(
                runCatching {
                    h.fence.saveCheckpointIfLeaseOwner(
                        checkpointStore = h.checkpointStore,
                        checkpoint = checkpoint("invoice-review", "run-001", revision = 1),
                        expectedRevision = 1,
                        expectedLease = lease.copy(ownerId = "forged-owner"),
                    )
                }.exceptionOrNull(),
            )
        }

    @Test
    fun `fence with a missing lease is stale`() =
        runBlocking<Unit> {
            val h = newHarness()
            h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
            assertStale(
                runCatching {
                    h.fence.saveCheckpointIfLeaseOwner(
                        checkpointStore = h.checkpointStore,
                        checkpoint = checkpoint("invoice-review", "run-001", revision = 1),
                        expectedRevision = 1,
                        expectedLease = WorkflowLeaseFixtures.lease(),
                    )
                }.exceptionOrNull(),
            )
            assertThat(h.checkpointStore.load("invoice-review", "run-001")).isNotNull
        }

    @Test
    fun `fence binds lease identity to checkpoint identity on save`() =
        runBlocking<Unit> {
            val h = newHarness()
            h.checkpointStore.save(checkpoint("workflow-b", "id-1", revision = 1))
            val lease = h.leaseStore.claim("workflow-a", "id-1", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            assertThat(
                runCatching {
                    h.fence.saveCheckpointIfLeaseOwner(
                        checkpointStore = h.checkpointStore,
                        checkpoint = checkpoint("workflow-b", "id-1", revision = 1, nextStepIndex = 9),
                        expectedRevision = 1,
                        expectedLease = lease,
                    )
                }.exceptionOrNull(),
            ).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(h.checkpointStore.load("workflow-b", "id-1")?.nextStepIndex).isEqualTo(3)
            assertThat(h.leaseStore.currentLease("workflow-a", "id-1")).isEqualTo(lease)
        }

    @Test
    fun `fence binds lease identity to checkpoint identity on delete`() =
        runBlocking<Unit> {
            val h = newHarness()
            h.checkpointStore.save(checkpoint("workflow-a", "id-2", revision = 1))
            val lease = h.leaseStore.claim("workflow-a", "id-1", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            assertThat(
                runCatching {
                    h.fence.deleteCheckpointIfLeaseOwner(
                        checkpointStore = h.checkpointStore,
                        workflowName = "workflow-a",
                        workflowId = "id-2",
                        expectedRevision = 1,
                        expectedLease = lease,
                    )
                }.exceptionOrNull(),
            ).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(h.checkpointStore.load("workflow-a", "id-2")).isNotNull
            assertThat(h.leaseStore.currentLease("workflow-a", "id-1")).isEqualTo(lease)
        }

    @Test
    fun `fence does not renew or extend the lease`() =
        runBlocking<Unit> {
            val h = newHarness()
            val persisted = h.seedCheckpoint("invoice-review", "run-001")
            val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            h.clock.advance(500)
            h.fence.saveCheckpointIfLeaseOwner(
                checkpointStore = h.checkpointStore,
                checkpoint =
                    checkpoint("invoice-review", "run-001", revision = 1).copy(
                        checkpointGeneration = persisted.checkpointGeneration,
                    ),
                expectedRevision = persisted.revision,
                expectedLease = lease,
            )
            h.clock.advance(500)
            // Still inside the original window at T0+1000? No: T0+1000 is exact
            // expiry, so the lease must be gone — the fence never extended it.
            assertThat(h.leaseStore.currentLease("invoice-review", "run-001")).isNull()
        }

    @Test
    fun `fence does not mutate durable lease metadata`() =
        runBlocking<Unit> {
            val h = newHarness()
            val persisted = h.seedCheckpoint("invoice-review", "run-001")
            val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 3, leaseDurationMillis = 1_000)
            // The caller's snapshot is a capability token; the fence must not
            // write its checkpointRevision into the durable lease.
            val tampered = lease.copy(checkpointRevision = 999)
            h.fence.saveCheckpointIfLeaseOwner(
                checkpointStore = h.checkpointStore,
                checkpoint =
                    checkpoint("invoice-review", "run-001", revision = 1, nextStepIndex = 4).copy(
                        checkpointGeneration = persisted.checkpointGeneration,
                    ),
                expectedRevision = persisted.revision,
                expectedLease = tampered,
            )
            assertThat(h.checkpointStore.load("invoice-review", "run-001")?.nextStepIndex).isEqualTo(4)
            assertThat(h.leaseStore.currentLease("invoice-review", "run-001")?.checkpointRevision).isEqualTo(3)
        }

    @Test
    fun `fence works with a null checkpoint revision lease`() =
        runBlocking<Unit> {
            val h = newHarness()
            val persisted = h.seedCheckpoint("invoice-review", "run-001")
            val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = null, leaseDurationMillis = 1_000)
            h.fence.saveCheckpointIfLeaseOwner(
                checkpointStore = h.checkpointStore,
                checkpoint =
                    checkpoint("invoice-review", "run-001", revision = 1, nextStepIndex = 4).copy(
                        checkpointGeneration = persisted.checkpointGeneration,
                    ),
                expectedRevision = persisted.revision,
                expectedLease = lease,
            )
            assertThat(h.checkpointStore.load("invoice-review", "run-001")?.nextStepIndex).isEqualTo(4)
        }

    @Test
    fun `fence delete with stale lease is stale and leaves the checkpoint`() =
        runBlocking<Unit> {
            val h = newHarness()
            h.checkpointStore.save(checkpoint("invoice-review", "run-001", revision = 1))
            val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            h.clock.advance(2_000)
            assertStale(
                runCatching {
                    h.fence.deleteCheckpointIfLeaseOwner(
                        checkpointStore = h.checkpointStore,
                        workflowName = "invoice-review",
                        workflowId = "run-001",
                        expectedRevision = 1,
                        expectedLease = lease,
                    )
                }.exceptionOrNull(),
            )
            assertThat(h.checkpointStore.load("invoice-review", "run-001")).isNotNull
        }

    @Test
    fun `fence works with a tampered acquiredAt snapshot`() =
        runBlocking<Unit> {
            val h = newHarness()
            val persisted = h.seedCheckpoint("invoice-review", "run-001")
            val lease = h.leaseStore.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 1, leaseDurationMillis = 1_000)
            val tampered = lease.copy(acquiredAtEpochMillis = 42, expiresAtEpochMillis = 43)
            val saved =
                h.fence.saveCheckpointIfLeaseOwner(
                    checkpointStore = h.checkpointStore,
                    checkpoint =
                        checkpoint("invoice-review", "run-001", revision = 1).copy(
                            checkpointGeneration = persisted.checkpointGeneration,
                        ),
                    expectedRevision = persisted.revision,
                    expectedLease = tampered,
                )
            assertThat(saved.revision).isEqualTo(2)
        }

    // ── Lease-token lineage properties ─────────────────────────────

    @Test
    fun `fence authority follows token lineage across multiple generations`() =
        runBlocking<Unit> {
            val h = newHarness()
            val name = "fence-lineage"
            val id = "workflow"
            val persisted = h.seedCheckpoint(name, id)
            val t1 = h.leaseStore.claim(name, id, "worker-a", checkpointRevision = 1, leaseDurationMillis = 1_000)
            h.clock.advance(1_001)
            val t2 = h.leaseStore.claim(name, id, "worker-b", checkpointRevision = 1, leaseDurationMillis = 1_000)
            h.clock.advance(1_001)
            val t3 = h.leaseStore.claim(name, id, "worker-a", checkpointRevision = 1, leaseDurationMillis = 1_000)

            val saved =
                h.fence.saveCheckpointIfLeaseOwner(
                    checkpointStore = h.checkpointStore,
                    checkpoint =
                        checkpoint(name, id, nextStepIndex = 4, revision = 1).copy(
                            checkpointGeneration = persisted.checkpointGeneration,
                        ),
                    expectedRevision = persisted.revision,
                    expectedLease = t3,
                )
            assertThat(saved.revision).isEqualTo(2)

            assertStale(
                runCatching {
                    h.fence.saveCheckpointIfLeaseOwner(
                        checkpointStore = h.checkpointStore,
                        checkpoint = checkpoint(name, id, nextStepIndex = 9, revision = 2),
                        expectedRevision = 2,
                        expectedLease = t1,
                    )
                }.exceptionOrNull(),
            )
            assertThat(h.checkpointStore.load(name, id)?.nextStepIndex).isEqualTo(4)

            assertStale(
                runCatching {
                    h.fence.deleteCheckpointIfLeaseOwner(
                        checkpointStore = h.checkpointStore,
                        workflowName = name,
                        workflowId = id,
                        expectedRevision = 2,
                        expectedLease = t2,
                    )
                }.exceptionOrNull(),
            )
            assertThat(h.checkpointStore.load(name, id)?.nextStepIndex).isEqualTo(4)
            assertThat(h.leaseStore.currentLease(name, id)).isEqualTo(t3)
        }

    @Test
    fun `exact expiry takeover atomically invalidates predecessor fence`() =
        runBlocking<Unit> {
            val h = newHarness()
            val name = "exact-expiry-fence"
            val id = "workflow"
            h.checkpointStore.save(checkpoint(name, id, revision = 1))
            val old = h.leaseStore.claim(name, id, "worker-old", checkpointRevision = 1, leaseDurationMillis = 1_000)
            h.clock.advance(1_000)
            val successor = h.leaseStore.claim(name, id, "worker-new", checkpointRevision = 1, leaseDurationMillis = 1_000)

            assertStale(
                runCatching {
                    h.fence.saveCheckpointIfLeaseOwner(
                        checkpointStore = h.checkpointStore,
                        checkpoint = checkpoint(name, id, nextStepIndex = 9, revision = 1),
                        expectedRevision = 1,
                        expectedLease = old,
                    )
                }.exceptionOrNull(),
            )
            assertThat(h.checkpointStore.load(name, id)?.nextStepIndex).isEqualTo(3)

            assertStale(
                runCatching {
                    h.fence.deleteCheckpointIfLeaseOwner(
                        checkpointStore = h.checkpointStore,
                        workflowName = name,
                        workflowId = id,
                        expectedRevision = 1,
                        expectedLease = old,
                    )
                }.exceptionOrNull(),
            )
            assertThat(h.checkpointStore.load(name, id)?.nextStepIndex).isEqualTo(3)

            // Advance the clock before the successor's fenced save: the fence must
            // never extend the lease, so the successor's expiry must remain
            // exactly its claim-time expiry (M20: a fence that rewrote expiresAt
            // from the CURRENT clock would shift it and break the equality).
            h.clock.advance(250)
            val persisted = h.checkpointStore.load(name, id)!!
            val saved =
                h.fence.saveCheckpointIfLeaseOwner(
                    checkpointStore = h.checkpointStore,
                    checkpoint =
                        checkpoint(name, id, nextStepIndex = 4, revision = 1).copy(
                            checkpointGeneration = persisted.checkpointGeneration,
                        ),
                    expectedRevision = persisted.revision,
                    expectedLease = successor,
                )
            assertThat(saved.revision).isEqualTo(2)
            assertThat(h.checkpointStore.load(name, id)?.nextStepIndex).isEqualTo(4)
            assertThat(h.leaseStore.currentLease(name, id)).isEqualTo(successor)
        }
}
