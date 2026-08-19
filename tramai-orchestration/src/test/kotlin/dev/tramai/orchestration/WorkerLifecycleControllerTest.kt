package dev.tramai.orchestration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic lifecycle-level state-machine tests: concurrent shutdown,
 * start vs in-progress shutdown, and root-ownership transfer.
 *
 * These are the cross-component races a bytecode architecture guard cannot
 * see: they assert who wins lifecycle transitions and when ownership of the
 * root coroutine lifecycle transfers.
 */
class WorkerLifecycleControllerTest {

    private val config = WorkerConfig(
        workerId = "lifecycle-test",
        poolName = "tests",
        pollIntervalMillis = 20,
        leaseDurationMillis = 5_000,
        drainTimeoutMillis = 60_000,
    )

    private class RecordingObserver : TramaiWorkerObserver {
        val workerStarted = CopyOnWriteArrayList<String>()
        val shutdownStarted = CopyOnWriteArrayList<String>()
        val shutdownComplete = CopyOnWriteArrayList<String>()
        val workerStopped = CopyOnWriteArrayList<String>()

        override fun onWorkerStarted(workerId: String) {
            workerStarted += workerId
        }

        override fun onShutdownStarted(workerId: String) {
            shutdownStarted += workerId
        }

        override fun onShutdownComplete(workerId: String) {
            shutdownComplete += workerId
        }

        override fun onWorkerStopped(workerId: String) {
            workerStopped += workerId
        }
    }

    private fun gatedWorker(
        gate: CompletableDeferred<Unit>,
        observer: RecordingObserver,
    ): Pair<TramaiWorker, InMemoryWorkflowCheckpointStore> {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workflow<LifecycleState>("lifecycle", definitionVersion = "v1") {
            localStep("hold") { state, _ -> gate.await(); state }
        }.build { it.value }
        val bindings = WorkflowBindingRegistry {
            bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = LifecycleCodec))
        }
        val worker = TramaiWorker(
            config = config,
            leaseStore = leaseStore,
            checkpointStore = store,
            checkpointCatalog = store,
            stepAttemptStore = store,
            workflowBindings = bindings,
            observability = observer,
        )
        runBlocking {
            store.save(
                WorkflowCheckpoint(
                    workflowName = "lifecycle",
                    workflowId = "w-1",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = LifecycleCodec.encode(LifecycleState("start")),
                    metadata = workflow.checkpointMetadata(),
                ),
            )
        }
        return worker to store
    }

    @Test
    fun `concurrent shutdown cannot clear lifecycle ownership while another shutdown is draining`() {
        val gate = CompletableDeferred<Unit>()
        val observer = RecordingObserver()
        val (worker, store) = gatedWorker(gate, observer)

        runBlocking {
            worker.start()
            // The poller claims the checkpoint and the execution blocks on the gate.
            withTimeout(10_000) {
                while (store.latestStepAttempt("w-1", "hold") == null) delay(5)
            }
            assertThat(observer.workerStarted).hasSize(1)

            // Shutdown A: starts draining, waits on the gated execution.
            val shutdownA = launch { worker.shutdown() }
            withTimeout(10_000) {
                while (observer.shutdownStarted.isEmpty()) delay(5)
            }

            // Shutdown B: concurrent, loses the CAS — must NOT clear workerJob.
            withTimeout(10_000) { worker.shutdown() }

            // start() mid-drain must be a no-op: ownership still belongs to A's root.
            withTimeout(10_000) { worker.start() }
            assertThat(observer.workerStarted)
                .withFailMessage("start() during drain must not create a second worker root")
                .hasSize(1)

            // Unblock the execution; A finishes the drain and clears its own root.
            gate.complete(Unit)
            withTimeout(10_000) { shutdownA.join() }
            assertThat(observer.shutdownComplete).hasSize(1)

            // A fresh lifecycle is possible afterwards.
            worker.start()
            assertThat(observer.workerStarted).hasSize(2)
            worker.shutdown()
            assertThat(observer.shutdownComplete).hasSize(2)
            assertThat(observer.workerStopped).hasSize(2)
        }
    }

    @Test
    fun `shutdown before start is a no-op and a clean lifecycle can still start`() {
        val gate = CompletableDeferred<Unit>()
        val observer = RecordingObserver()
        val (worker, _) = gatedWorker(gate, observer)
        runBlocking {
            worker.shutdown()
            assertThat(observer.shutdownStarted).isEmpty()
            worker.start()
            assertThat(observer.workerStarted).hasSize(1)
            worker.shutdown()
            assertThat(observer.shutdownComplete).hasSize(1)
            gate.complete(Unit)
        }
    }

    private data class LifecycleState(val value: String)

    private object LifecycleCodec : WorkflowStateCodec<LifecycleState> {
        override fun encode(state: LifecycleState): String = state.value
        override fun decode(payload: String): LifecycleState = LifecycleState(payload)
    }
}
