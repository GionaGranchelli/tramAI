@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

// Lease claims in one line read better long; .editorconfig enforces no line length.

package dev.tramai.orchestration

import dev.tramai.testing.persistence.lease.MutableMillisClock
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E10: a stale recovery worker must not be able to obtain a durable admission.
 *
 * The failure this closes: the worker's fenced checkpoint store inherited `admit` from its delegate, so a worker whose
 * lease had already been superseded took a grant and dispatched with it. The fenced checkpoint write refused it
 * afterwards — once the external effect had already landed.
 *
 * Every case here is deterministic: no threads, no sleeps, no timing. A takeover is a lease claim, and the interleaving
 * points are chosen by construction.
 */
private const val FENCE_WORKFLOW = "recovery-fence"
private const val FENCE_RUN = "run-1"

class RecoveryAdmissionFenceTest {
    private val clock = MutableMillisClock()

    private class Fixture(
        val clock: MutableMillisClock,
        val leaseStore: InMemoryWorkflowLeaseStore,
        val checkpoints: InMemoryWorkflowCheckpointStore,
    ) {
        /** Worker A takes the lease and seeds a row it can present. */
        suspend fun claimAs(
            workerId: String,
            leaseDurationMillis: Long = 1_000,
        ): WorkflowLease =
            leaseStore.claim(FENCE_WORKFLOW, FENCE_RUN, workerId, checkpointRevision = 1, leaseDurationMillis = leaseDurationMillis)

        suspend fun seed(): WorkflowCheckpoint = checkpoints.save(checkpoint())

        /**
         * The worker's admission path: the decision the private fenced decorator makes, with the fence this lease store
         * family actually provides.
         */
        suspend fun admit(
            delegate: WorkflowCheckpointStore,
            lease: WorkflowLease,
            request: WorkflowAdmissionRequest,
        ): WorkflowAdmission = admitUnderLeaseFence(leaseStore, delegate, lease, request)
    }

    private class AdmissionCountingStore(
        private val delegate: WorkflowCheckpointStore,
    ) : WorkflowCheckpointStore by delegate {
        var admits = 0
            private set

        override suspend fun admit(request: WorkflowAdmissionRequest): WorkflowAdmission = delegate.admit(request).also { admits++ }
    }

    private fun fixture(checkpoints: InMemoryWorkflowCheckpointStore = InMemoryWorkflowCheckpointStore()) =
        Fixture(clock, InMemoryWorkflowLeaseStore(clock), checkpoints)

    /**
     * W5 — the regression. Worker A holds the lease; its lease expires and worker B claims the workflow; A then asks for
     * an admission. A must be refused, and the durable counter must not move.
     */
    @Test
    fun `a worker whose lease was superseded cannot obtain a durable admission`() =
        runBlocking<Unit> {
            val f = fixture()
            val checked = AdmissionCountingStore(f.checkpoints)
            val row = f.seed()
            val workerA = f.claimAs("worker-a")
            val store = f
            clock.advance(5_000)
            f.claimAs("worker-b")

            val refusal = assertFailsWith<WorkflowAdmissionRefusedException> { store.admit(checked, workerA, admissionRequest(row)) }

            assertTrue(refusal.message!!.contains("no longer owns its lease"), refusal.message!!)
            assertEquals(0, checked.admits, "the superseded worker's admission must not reach the store at all")
        }

    /**
     * The interleaving the design has to survive: the takeover lands *between* the worker's lease read and the fence
     * operation. A currentLease()-then-delegate.admit() design passes the first half and grants; the fence does not.
     */
    @Test
    fun `a takeover landing between lease verification and admission is still refused`() =
        runBlocking<Unit> {
            val f = fixture()
            val checked = AdmissionCountingStore(f.checkpoints)
            val row = f.seed()
            val workerA = f.claimAs("worker-a")
            var providerCalls = 0
            // The takeover lands after the worker has its lease in hand and before the fence runs: exactly the window a
            // currentLease()-then-delegate.admit() design loses.
            val racing =
                object : WorkflowLeaseCheckpointFence {
                    override suspend fun saveCheckpointIfLeaseOwner(
                        checkpointStore: WorkflowCheckpointStore,
                        checkpoint: WorkflowCheckpoint,
                        expectedRevision: Long?,
                        expectedLease: WorkflowLease,
                    ): WorkflowCheckpoint =
                        f.leaseStore.saveCheckpointIfLeaseOwner(checkpointStore, checkpoint, expectedRevision, expectedLease)

                    override suspend fun deleteCheckpointIfLeaseOwner(
                        checkpointStore: WorkflowCheckpointStore,
                        workflowName: String,
                        workflowId: String,
                        expectedRevision: Long?,
                        expectedLease: WorkflowLease,
                        expectedGeneration: String?,
                    ) = f.leaseStore.deleteCheckpointIfLeaseOwner(
                        checkpointStore,
                        workflowName,
                        workflowId,
                        expectedRevision,
                        expectedLease,
                        expectedGeneration,
                    )

                    override suspend fun admitIfLeaseOwner(
                        checkpointStore: WorkflowCheckpointStore,
                        request: WorkflowAdmissionRequest,
                        expectedLease: WorkflowLease,
                    ): WorkflowAdmission? {
                        providerCalls++
                        clock.advance(5_000)
                        f.claimAs("worker-b")
                        return f.leaseStore.admitIfLeaseOwner(checkpointStore, request, expectedLease)
                    }
                }

            val refusal =
                assertFailsWith<WorkflowAdmissionRefusedException> {
                    admitUnderLeaseFence(racing, checked, workerA, admissionRequest(row))
                }

            assertEquals(1, providerCalls, "the worker's own lease read is what the fence must not trust")
            assertTrue(refusal.message!!.contains("no longer owns its lease"), refusal.message!!)
            assertEquals(0, checked.admits)
        }

    /** The owner still gets its admission, and the counter advances exactly once per call. */
    @Test
    fun `the active owner still obtains its durable admission`() =
        runBlocking<Unit> {
            val f = fixture()
            val checked = AdmissionCountingStore(f.checkpoints)
            val row = f.seed()
            val workerA = f.claimAs("worker-a")
            val store = f

            val first = store.admit(checked, workerA, admissionRequest(row))
            // An admission advances the row's revision, so the next attempt presents the revision it now holds — exactly
            // what the resumed session does through its revision sink.
            val advanced = assertNotNull(f.checkpoints.load(FENCE_WORKFLOW, FENCE_RUN)).revision
            val second = store.admit(checked, workerA, admissionRequest(row.copy(revision = advanced)))

            assertTrue(first.admitted, "the owning worker is admitted")
            assertEquals(1L, first.sequence)
            assertEquals(2L, second.sequence, "each attempt takes its own admission")
            assertEquals(2, checked.admits)
        }

    /**
     * Fail closed for implementations that cannot fence: a fence that only knows how to save and delete must refuse,
     * never fall back to an unfenced admission.
     */
    @Test
    fun `a fence that cannot fence admissions fails closed`() =
        runBlocking<Unit> {
            val f = fixture()
            val checked = AdmissionCountingStore(f.checkpoints)
            val row = f.seed()
            val workerA = f.claimAs("worker-a")
            val real = f.leaseStore
            // The pre-E10 shape: a third-party fence with the two original operations and no admission operation.
            val legacyFence =
                object : WorkflowLeaseCheckpointFence {
                    override suspend fun saveCheckpointIfLeaseOwner(
                        checkpointStore: WorkflowCheckpointStore,
                        checkpoint: WorkflowCheckpoint,
                        expectedRevision: Long?,
                        expectedLease: WorkflowLease,
                    ): WorkflowCheckpoint = real.saveCheckpointIfLeaseOwner(checkpointStore, checkpoint, expectedRevision, expectedLease)

                    override suspend fun deleteCheckpointIfLeaseOwner(
                        checkpointStore: WorkflowCheckpointStore,
                        workflowName: String,
                        workflowId: String,
                        expectedRevision: Long?,
                        expectedLease: WorkflowLease,
                        expectedGeneration: String?,
                    ) = real.deleteCheckpointIfLeaseOwner(
                        checkpointStore,
                        workflowName,
                        workflowId,
                        expectedRevision,
                        expectedLease,
                        expectedGeneration,
                    )
                }
            val store =
                object {
                    suspend fun admit(): WorkflowAdmission? = legacyFence.admitIfLeaseOwner(checked, admissionRequest(row), workerA)
                }

            val refusal = assertFailsWith<WorkflowAdmissionRefusedException> { store.admit() }

            assertTrue(refusal.message!!.contains("cannot fence a durable admission"), refusal.message!!)
            assertEquals(0, checked.admits, "an unfenceable implementation admits nothing")
            assertNotNull(f.leaseStore.currentLease(FENCE_WORKFLOW, FENCE_RUN))
        }

    /**
     * Deliberate asymmetry: operator-driven terminal cancellation is not gated on worker lease ownership, so it still
     * commits for a run whose lease has moved on. Recorded as a stated limit — a stale worker can commit a cancellation
     * it no longer owns.
     */
    @Test
    fun `a refusing checkpoint store surfaces as an admission refusal, not a persistence failure`() =
        runBlocking<Unit> {
            val f = fixture()
            val row = f.seed()
            val workerA = f.claimAs("worker-a")
            // A store that cannot issue a grant is a capability answer, not a broken store.
            val refusing =
                object : WorkflowCheckpointStore by f.checkpoints {
                    override suspend fun admit(request: WorkflowAdmissionRequest): WorkflowAdmission =
                        throw WorkflowAdmissionRefusedException("this store cannot issue admissions")
                }

            val refusal =
                assertFailsWith<WorkflowAdmissionRefusedException> {
                    f.admit(refusing, workerA, admissionRequest(row))
                }

            // The refusal's own type survives the persistence boundary: recovery can tell "not admitted" from
            // "storage broke" only if the type is preserved.
            assertTrue(refusal.message!!.contains("admission refused"), refusal.message!!)
        }

    @Test
    fun `operator cancellation is not gated on lease ownership`() =
        runBlocking<Unit> {
            val f = fixture()
            val row = f.seed()
            val workerA = f.claimAs("worker-a")
            clock.advance(5_000)
            f.claimAs("worker-b")

            f.checkpoints.cancel(
                WorkflowCancellationRequest(
                    workflowName = FENCE_WORKFLOW,
                    workflowId = FENCE_RUN,
                    expectedRevision = row.revision,
                    expectedGeneration = row.checkpointGeneration,
                    actor = "operator",
                    reason = "stopped during recovery",
                    cancelledAtEpochMillis = 0L,
                ),
            )

            assertEquals(
                WorkflowLifecycleStatus.CANCELLED,
                assertNotNull(f.leaseStore.currentLease(FENCE_WORKFLOW, FENCE_RUN)).let {
                    assertNotNull(f.checkpoints.lifecycle(FENCE_WORKFLOW, FENCE_RUN)).status
                },
                "the operator's cancellation commits regardless of who holds the lease",
            )
        }
}

private fun checkpoint(): WorkflowCheckpoint =
    WorkflowCheckpoint(
        workflowName = FENCE_WORKFLOW,
        workflowId = FENCE_RUN,
        nextStepIndex = 2,
        stepExecutions = 1,
        lastCompletedStepName = "charge-card",
        statePayload = "{}",
        revision = 1,
        metadata = emptyMap(),
        savedAtEpochMillis = 0L,
    )

private fun admissionRequest(row: WorkflowCheckpoint): WorkflowAdmissionRequest =
    WorkflowAdmissionRequest(
        workflowName = FENCE_WORKFLOW,
        workflowId = FENCE_RUN,
        expectedRevision = row.revision,
        expectedGeneration = row.checkpointGeneration,
    )
