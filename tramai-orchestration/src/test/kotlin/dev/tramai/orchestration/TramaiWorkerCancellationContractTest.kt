package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.system.measureTimeMillis
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
    fun `graceful shutdown finishes running work within drain window`() = runBlocking {
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
        stepStarted.await()

        // Step is running — shutdown should wait, not complete immediately
        val shutdownMs = measureTimeMillis {
            val shutdown = async { worker.shutdown() }
            delay(50)
            assertThat(shutdown.isCompleted).isFalse()
            allowCompletion.complete(Unit)
            withTimeout(1_000) { shutdown.await() }
        }
        assertThat(shutdownMs).isLessThan(500)

        // Work completed successfully
        assertThat(checkpointStore.load(workflow.name, runId)).isNull()
        assertThat(leaseStore.listActiveWorkers()).isEmpty()
    }

    // -------------------------------------------------------------------------
    // Test 2: Drain timeout cancels running step – CANCELLED not FAILED
    // -------------------------------------------------------------------------

    @Test
    fun `drain timeout cancels running step as CANCELLED not FAILED`() = runBlocking {
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

        val shutdownMillis = measureTimeMillis {
            worker.shutdown()
        }
        // Shutdown returns fast because drain fires and delay throws immediately
        assertThat(shutdownMillis).isLessThan(500)
        waitUntil {
            checkpointStore.load(workflow.name, runId) != null &&
                checkpointStore.latestStepAttempt(runId, "blocking")?.status ==
                StepAttemptStatus.CANCELLED &&
                leaseStore.listActiveWorkers().isEmpty()
        }
        assertThat(checkpointStore.latestStepAttempt(runId, "blocking")?.status)
            .isEqualTo(StepAttemptStatus.CANCELLED)
        assertThat(leaseStore.listActiveWorkers()).isEmpty()
        assertThat(worker.latestFailure(runId)).isNull()
        assertThat(leaseStore.currentLease(workflow.name, runId)).isNull()
    }

    // -------------------------------------------------------------------------
    // Test 3: Observer throws during abandonment – cancellation still survives
    // -------------------------------------------------------------------------

    @Test
    fun `observer throw during abandonment does not replace cancellation`() = runBlocking {
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
        worker.shutdown()

        // Observer was called
        assertThat(observer.onWorkflowAbandonedCalled).isTrue()

        // Step was cancelled (not failed)
        assertThat(
            checkpointStore.latestStepAttempt(runId, "blocking")?.status,
        ).isEqualTo(StepAttemptStatus.CANCELLED)

        // No normal failure was recorded
        assertThat(worker.latestFailure(runId)).isNull()

        // Lease was released despite observer failure
        waitUntil {
            leaseStore.currentLease(workflow.name, runId) == null
        }
    }

    // -------------------------------------------------------------------------
    // Test 4: Poll job cancellation does not trigger onPollFailed
    // -------------------------------------------------------------------------

    @Test
    fun `poll job cancellation does not trigger onPollFailed`() = runBlocking {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workerWorkflow("poll-cancel") {
            localStep(
                name = "never-reached",
                transform = { state, _ -> state },
            )
        }
        val observer = RecordingPollObserver()
        val worker = worker("worker-0", leaseStore, checkpointStore, workflow,
            pollIntervalMillis = 10, observability = observer)
        worker.start()
        delay(50) // let a poll cycle happen
        worker.shutdown()

        assertThat(observer.pollFailedCount).isZero()
    }

    // -------------------------------------------------------------------------
    // Test 5: Lease-renewal cancellation does not trigger onLeaseRenewalFailed
    // -------------------------------------------------------------------------

    @Test
    fun `lease renewal cancellation does not trigger onLeaseRenewalFailed`() = runBlocking {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val renewStore = BlockingRenewLeaseStore(leaseStore)
        val stepReleased = CompletableDeferred<Unit>()
        val workflow = workerWorkflow("lease-renewal-cancel") {
            localStep(
                name = "hold-lease",
                transform = { state, _ ->
                    renewStore.renewalStarted.await()
                    stepReleased.complete(Unit)
                    state.copy(value = "${state.value}:done")
                },
            )
        }
        val runId = "run-lease-renewal"
        seedCheckpoint(checkpointStore, workflow, runId, WorkerState("start"))

        val observer = RecordingLeaseObserver()
        val worker = worker("worker-0", renewStore, checkpointStore, workflow,
            leaseDurationMillis = 50, pollIntervalMillis = 20,
            observability = observer)
        worker.start()
        waitUntil {
            checkpointStore.latestStepAttempt(runId, "hold-lease")?.status == StepAttemptStatus.STARTED
        }
        stepReleased.await()
        worker.shutdown()

        assertThat(observer.leaseRenewalFailedCount).isZero()
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private class ThrowingOnAbandonObserver : TramaiWorkerObserver {
        var onWorkflowAbandonedCalled = false
        override fun onWorkflowAbandoned(
            workflowId: String,
            workerId: String,
            lastStep: String?,
            timeoutMillis: Long,
        ) {
            onWorkflowAbandonedCalled = true
            throw RuntimeException("observer abandon failure")
        }
    }

    private class RecordingPollObserver : TramaiWorkerObserver {
        var pollFailedCount = 0
        override fun onPollFailed(workerId: String, error: Throwable) {
            pollFailedCount++
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
    ) : WorkflowLeaseStore by delegate {
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
        .registerWorkerBinding(WorkerStateCodec)

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
        workflowRegistry = mapOf(workflow.name to workflow),
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
