package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

/**
 * Contract tests proving that worker cancellation preserves the
 * CancellationException identity and never classifies it as a normal
 * failure.
 *
 * Covers:
 * - Graceful shutdown completes running work within drain window
 * - Drain timeout cancels running step (CANCELLED, never FAILED)
 * - Observer throws during abandonment notification
 * - Poll job cancellation
 * - Lease-renewal cancellation
 */
class TramaiWorkerCancellationContractTest {

    // -------------------------------------------------------------------------
    // Test 1: Graceful shutdown completes within drain window
    // -------------------------------------------------------------------------

    @Test
    fun `graceful shutdown finishes running work within drain window`() { runBlocking {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val stepStarted = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        val workflow = workerWorkflow("shutdown-completes") {
            localStep(
                name = "work",
                transform = { state, _ ->
                    stepStarted.complete(Unit)
                    allowCompletion.await()
                    state.copy(value = "${state.value}:done")
                },
            )
        }
        val runId = "run-shutdown-completes"
        seedCheckpoint(checkpointStore, workflow, runId, WorkerState("start"))

        val worker = worker("worker-0", leaseStore, checkpointStore, workflow,
            drainTimeoutMillis = 2_000, pollIntervalMillis = 20)
        worker.start()
        withTimeout(5_000) {
            stepStarted.await()
        }

        val shutdown = async(start = CoroutineStart.UNDISPATCHED) {
            worker.shutdown()
        }

        assertThat(shutdown.isCompleted).isFalse()

        allowCompletion.complete(Unit)

        withTimeout(5_000) {
            shutdown.await()
        }

        waitUntil {
            checkpointStore.load(workflow.name, runId) == null &&
                checkpointStore.latestStepAttempt(runId, "work")?.status == StepAttemptStatus.COMPLETED &&
                leaseStore.currentLease(workflow.name, runId) == null
        }

        assertThat(checkpointStore.load(workflow.name, runId)).isNull()

        assertThat(
            checkpointStore.latestStepAttempt(runId, "work")?.status,
        ).isEqualTo(StepAttemptStatus.COMPLETED)

        assertThat(
            leaseStore.currentLease(workflow.name, runId),
        ).isNull()
    }
    }

    // -------------------------------------------------------------------------
    // Test 2: Drain timeout cancels running step – CANCELLED not FAILED
    // -------------------------------------------------------------------------

    @Test
    fun `drain timeout cancels running step as CANCELLED not FAILED`() { runBlocking {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workerWorkflow("drain-cancelled") {
            localStep(
                name = "blocking",
                transform = { state, _ ->
                    delay(2000)
                    state.copy(value = "${state.value}:done")
                },
            )
        }
        val runId = "run-drain-cancelled"
        seedCheckpoint(checkpointStore, workflow, runId, WorkerState("start"))

        val worker = worker("worker-0", leaseStore, checkpointStore, workflow,
            drainTimeoutMillis = 200, pollIntervalMillis = 20)
        worker.start()
        waitUntil {
            checkpointStore.latestStepAttempt(runId, "blocking")?.status == StepAttemptStatus.STARTED
        }

        withTimeout(5_000) {
            worker.shutdown()
        }
        // Shutdown returns fast because drain fires and delay throws immediately
        waitUntil {
            checkpointStore.load(workflow.name, runId) != null &&
                checkpointStore.latestStepAttempt(runId, "blocking")?.status ==
                StepAttemptStatus.CANCELLED &&
                leaseStore.listActiveWorkers().isEmpty() &&
                leaseStore.currentLease(workflow.name, runId) == null
        }
        assertThat(checkpointStore.latestStepAttempt(runId, "blocking")?.status)
            .isEqualTo(StepAttemptStatus.CANCELLED)
        assertThat(leaseStore.listActiveWorkers()).isEmpty()
        assertThat(worker.latestFailure(runId)).isNull()
    }
    }

    // -------------------------------------------------------------------------
    // Test 3: Observer throws during abandonment – cancellation still survives
    // -------------------------------------------------------------------------

    @Test
    fun `observer throw during abandonment does not replace cancellation`() { runBlocking {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workerWorkflow("observer-throws") {
            localStep(
                name = "blocking",
                transform = { state, _ ->
                    delay(2000)
                    state.copy(value = "${state.value}:done")
                },
            )
        }
        val runId = "run-observer-throws"
        seedCheckpoint(checkpointStore, workflow, runId, WorkerState("start"))

        val observer = ThrowingOnAbandonObserver()
        val worker = worker("worker-0", leaseStore, checkpointStore, workflow,
            drainTimeoutMillis = 200, pollIntervalMillis = 20,
            observability = observer)
        worker.start()
        waitUntil {
            checkpointStore.latestStepAttempt(runId, "blocking")?.status == StepAttemptStatus.STARTED
        }

        // Shutdown — observer throws, but cancellation and lease release survive
        withTimeout(5_000) {
            worker.shutdown()
        }

        // Observer was called (wait for async abandonment callback)
        withTimeout(5_000) {
            observer.abandonmentCalled.await()
        }

        // Step was cancelled (not failed) — wait for async cleanup
        waitUntil {
            checkpointStore.latestStepAttempt(runId, "blocking")?.status ==
                StepAttemptStatus.CANCELLED &&
                leaseStore.currentLease(workflow.name, runId) == null
        }

        assertThat(
            checkpointStore.latestStepAttempt(runId, "blocking")?.status,
        ).isEqualTo(StepAttemptStatus.CANCELLED)

        // No normal failure was recorded
        assertThat(worker.latestFailure(runId)).isNull()
    }
    }

    // -------------------------------------------------------------------------
    // Test 4: Poll job cancellation does not trigger onPollFailed
    // -------------------------------------------------------------------------

    @Test
    fun `poll job cancellation does not trigger onPollFailed`() { runBlocking {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val checkpointCatalog = BlockingCheckpointCatalog()

        val workflow = workerWorkflow("poll-cancel") {
            localStep(
                name = "never-reached",
                transform = { state, _ -> state },
            )
        }

        val observer = RecordingPollObserver()
        val worker = worker(
            workerId = "worker-0",
            leaseStore = leaseStore,
            checkpointStore = checkpointStore,
            checkpointCatalog = checkpointCatalog,
            workflow = workflow,
            pollIntervalMillis = 10,
            observability = observer,
        )

        worker.start()

        withTimeout(5_000) {
            checkpointCatalog.pollEntered.await()
        }

        withTimeout(5_000) {
            worker.shutdown()
        }

        assertThat(observer.pollFailedCount).isZero()
    }
    }

    // -------------------------------------------------------------------------
    // Test 5: Lease-renewal cancellation does not trigger onLeaseRenewalFailed
    // -------------------------------------------------------------------------

    @Test
    fun `lease renewal cancellation does not trigger onLeaseRenewalFailed`() { runBlocking {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val delegateLeaseStore = InMemoryWorkflowLeaseStore()
        val leaseStore = BlockingRenewLeaseStore(delegateLeaseStore)

        val workflow = workerWorkflow("lease-renewal-cancel") {
            localStep(
                name = "hold-lease",
                transform = { _, _ ->
                    awaitCancellation()
                },
            )
        }

        val runId = "run-lease-renewal"
        seedCheckpoint(
            checkpointStore,
            workflow,
            runId,
            WorkerState("start"),
        )

        val observer = RecordingLeaseObserver()
        val worker = worker(
            workerId = "worker-0",
            leaseStore = leaseStore,
            checkpointStore = checkpointStore,
            workflow = workflow,
            leaseDurationMillis = 50,
            pollIntervalMillis = 20,
            drainTimeoutMillis = 200,
            observability = observer,
        )

        worker.start()

        withTimeout(5_000) {
            leaseStore.renewalStarted.await()
        }

        withTimeout(5_000) {
            worker.shutdown()
        }

        // Drain timeout is a bound on shutdown, so cancellation cleanup may finish
        // asynchronously after shutdown returns. Coordinate on the exact durable
        // outcome instead of assuming a loaded dispatcher completes it in the
        // residual (often 1 ms) post-cancellation window.
        waitUntil {
            checkpointStore.latestStepAttempt(runId, "hold-lease")?.status ==
                StepAttemptStatus.CANCELLED &&
                delegateLeaseStore.currentLease(workflow.name, runId) == null
        }

        assertThat(observer.leaseRenewalFailedCount).isZero()

        assertThat(
            checkpointStore.latestStepAttempt(runId, "hold-lease")?.status,
        ).isEqualTo(StepAttemptStatus.CANCELLED)

        assertThat(worker.latestFailure(runId)).isNull()

        assertThat(
            delegateLeaseStore.currentLease(workflow.name, runId),
        ).isNull()
    }
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private class ThrowingOnAbandonObserver : TramaiWorkerObserver {
        val abandonmentCalled = CompletableDeferred<Unit>()
        override fun onWorkflowAbandoned(
            workflowId: String,
            workerId: String,
            lastStep: String?,
            timeoutMillis: Long,
        ) {
            abandonmentCalled.complete(Unit)
            throw RuntimeException("observer abandon failure")
        }
    }

    private class RecordingPollObserver : TramaiWorkerObserver {
        var pollFailedCount = 0
        override fun onPollFailed(workerId: String, error: Throwable) {
            pollFailedCount++
        }
    }

    private class BlockingCheckpointCatalog : WorkflowCheckpointCatalog {
        val pollEntered = CompletableDeferred<Unit>()

        override suspend fun listCheckpoints(): List<WorkflowCheckpoint> {
            pollEntered.complete(Unit)
            awaitCancellation()
        }
    }

    private class RecordingLeaseObserver : TramaiWorkerObserver {
        var leaseRenewalFailedCount = 0
        override fun onLeaseRenewalFailed(
            workflowId: String,
            workerId: String,
            error: Throwable,
        ) {
            leaseRenewalFailedCount++
        }
    }

    private class BlockingRenewLeaseStore(
        private val delegate: InMemoryWorkflowLeaseStore,
    ) : WorkflowLeaseStore by delegate, WorkflowLeaseCheckpointFence by delegate {
        val renewalStarted = CompletableDeferred<Unit>()

        override suspend fun renew(
            lease: WorkflowLease,
            checkpointRevision: Long?,
            leaseDurationMillis: Long,
        ): WorkflowLease {
            renewalStarted.complete(Unit)
            awaitCancellation()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers (mirrored from TramaiWorkerTest since those are private)
    // -------------------------------------------------------------------------

    private data class WorkerState(val value: String)

    private object WorkerStateCodec : WorkflowStateCodec<WorkerState> {
        override fun encode(state: WorkerState): String = state.value
        override fun decode(payload: String): WorkerState = WorkerState(payload)
    }

    private fun workerWorkflow(
        name: String,
        configure: WorkflowBuilder<WorkerState>.() -> Unit,
    ): Workflow<WorkerState, String> = workflow<WorkerState>(name, configure = configure)
        .build { it.value }
        

    private fun worker(
        workerId: String,
        leaseStore: WorkflowLeaseStore,
        checkpointStore: WorkflowCheckpointStore,
        workflow: Workflow<WorkerState, String>,
        checkpointCatalog: WorkflowCheckpointCatalog = checkpointStore as WorkflowCheckpointCatalog,
        stepAttemptStore: StepAttemptRecordStore = checkpointStore as StepAttemptRecordStore,
        observability: TramaiWorkerObserver = NoOpTramaiWorkerObserver,
        pollIntervalMillis: Long = 20,
        leaseDurationMillis: Long = 200,
        drainTimeoutMillis: Long = 1_000,
        workerCount: Int = 1,
        partitionEnabled: Boolean = false,
    ): TramaiWorker = TramaiWorker(
        config = WorkerConfig(
            workerId = workerId,
            poolName = "tests",
            pollIntervalMillis = pollIntervalMillis,
            leaseDurationMillis = leaseDurationMillis,
            drainTimeoutMillis = drainTimeoutMillis,
            partitionEnabled = partitionEnabled,
            workerCount = workerCount,
        ),
        leaseStore = leaseStore,
        checkpointStore = checkpointStore,
        checkpointCatalog = checkpointCatalog,
        stepAttemptStore = stepAttemptStore,
        workflowBindings = WorkflowBindingRegistry {
            bind(
                workflow = workflow,
                persistence = WorkflowPersistence(
                    checkpointStore = checkpointStore,
                    stateCodec = WorkerStateCodec,
                ),
            )
        },
        observability = observability,
    )

    private suspend fun seedCheckpoint(
        checkpointStore: WorkflowCheckpointStore,
        workflow: Workflow<WorkerState, String>,
        workflowId: String,
        state: WorkerState,
    ) {
        checkpointStore.save(
            checkpoint = WorkflowCheckpoint(
                workflowName = workflow.name,
                workflowId = workflowId,
                nextStepIndex = 0,
                stepExecutions = 0,
                lastCompletedStepName = null,
                statePayload = WorkerStateCodec.encode(state),
                metadata = workflow.checkpointMetadata(),
            ),
        )
    }

    private suspend fun waitUntil(block: suspend () -> Boolean) {
        withTimeout(20_000) {
            while (!block()) {
                delay(10)
            }
        }
    }
}
