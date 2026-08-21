package dev.tramai.orchestration

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class ShutdownHookTest {
    @Test
    fun `shutdown fires observer events in correct order`() { runBlocking {
        val events = mutableListOf<String>()
        val workerStarted = java.util.concurrent.CountDownLatch(1)
        val observer = object : TramaiWorkerObserver {
            override fun onWorkerStarted(workerId: String) { workerStarted.countDown() }
            override fun onShutdownStarted(workerId: String) { events.add("started") }
            override fun onDrainProgress(workerId: String, done: Int, pending: Int) { events.add("drain") }
            override fun onShutdownComplete(workerId: String) { events.add("complete") }
        }
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = makeWorkflow("shutdown-events") {
            localStep(
                name = "work",
                transform = { state, _ -> state },
            )
        }
        val worker = makeWorker("worker-1", leaseStore, store, workflow, observability = observer)
        worker.start()
        assertThat(
            workerStarted.await(5, TimeUnit.SECONDS),
        ).isTrue()
        worker.shutdown()
        assertThat(events).containsExactly("started", "drain", "complete")
    }
    }

    @Test
    fun `close fires shutdown events in correct order`() { runBlocking {
        val events = mutableListOf<String>()
        val workerStarted = java.util.concurrent.CountDownLatch(1)
        val observer = object : TramaiWorkerObserver {
            override fun onWorkerStarted(workerId: String) { workerStarted.countDown() }
            override fun onShutdownStarted(workerId: String) { events.add("started") }
            override fun onDrainProgress(workerId: String, done: Int, pending: Int) { events.add("drain") }
            override fun onShutdownComplete(workerId: String) { events.add("complete") }
        }
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = makeWorkflow("close-events") {
            localStep(
                name = "noop",
                transform = { state, _ -> state },
            )
        }
        val worker = makeWorker("worker-2", leaseStore, store, workflow, observability = observer)
        worker.start()
        assertThat(
            workerStarted.await(5, TimeUnit.SECONDS),
        ).isTrue()
        worker.close()
        assertThat(events).containsExactly("started", "drain", "complete")
    }
    }

    @Test
    fun `recording observer captures lifecycle events during workflow execution and shutdown`() { runBlocking {
        val events = mutableListOf<String>()
        val observer = object : TramaiWorkerObserver {
            override fun onLeaseAcquired(workflowId: String, workerId: String) { events.add("lease_acquired:$workflowId") }
            override fun onLeaseReleased(workflowId: String, workerId: String) { events.add("lease_released:$workflowId") }
            override fun onWorkerStarted(workerId: String) { events.add("worker_started") }
            override fun onWorkerStopped(workerId: String) { events.add("worker_stopped") }
            override fun onShutdownStarted(workerId: String) { events.add("shutdown_started") }
            override fun onDrainProgress(workerId: String, done: Int, pending: Int) { events.add("drain_progress") }
            override fun onShutdownComplete(workerId: String) { events.add("shutdown_complete") }
        }
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = makeWorkflow("recording-lifecycle") {
            localStep(
                name = "process",
                transform = { state, _ -> ShutdownHookState(state.value + ":done") },
            )
        }
        val runId = "run-recording"
        makeSeed(store, workflow, runId, ShutdownHookState("start"))

        val worker = makeWorker("worker-3", leaseStore, store, workflow, observability = observer)
        worker.start()
        try {
            waitUntil { store.load(workflow.name, runId) == null }
            assertThat(events).anySatisfy { assertThat(it).isEqualTo("worker_started") }
            assertThat(events).anySatisfy { assertThat(it).startsWith("lease_acquired:") }
            assertThat(events).anySatisfy { assertThat(it).startsWith("lease_released:") }
        } finally {
            worker.shutdown()
        }
        assertThat(events).anySatisfy { assertThat(it).isEqualTo("shutdown_started") }
        assertThat(events).anySatisfy { assertThat(it).isEqualTo("shutdown_complete") }
        assertThat(events).anySatisfy { assertThat(it).isEqualTo("worker_stopped") }
    }
    }

    private fun makeWorkflow(
        name: String,
        configure: WorkflowBuilder<ShutdownHookState>.() -> Unit,
    ): Workflow<ShutdownHookState, String> = workflow<ShutdownHookState>(name, configure = configure)
        .build { it.value }
        

    private fun makeWorker(
        workerId: String,
        leaseStore: WorkflowLeaseStore,
        checkpointStore: WorkflowCheckpointStore,
        workflow: Workflow<ShutdownHookState, String>,
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
                    stateCodec = ShutdownHookStateCodec,
                ),
            )
        },
        observability = observability,
    )

    private suspend fun makeSeed(
        checkpointStore: WorkflowCheckpointStore,
        workflow: Workflow<ShutdownHookState, String>,
        workflowId: String,
        state: ShutdownHookState,
    ) {
        checkpointStore.save(
            checkpoint = WorkflowCheckpoint(
                workflowName = workflow.name,
                workflowId = workflowId,
                nextStepIndex = 0,
                stepExecutions = 0,
                lastCompletedStepName = null,
                statePayload = ShutdownHookStateCodec.encode(state),
                metadata = workflow.checkpointMetadata(),
            ),
        )
    }

    private suspend fun waitUntil(block: suspend () -> Boolean) {
        withTimeout(5_000) {
            while (!block()) {
                delay(10)
            }
        }
    }
}

private data class ShutdownHookState(
    val value: String,
)

private object ShutdownHookStateCodec : WorkflowStateCodec<ShutdownHookState> {
    override fun encode(state: ShutdownHookState): String = state.value
    override fun decode(payload: String): ShutdownHookState = ShutdownHookState(payload)
}
