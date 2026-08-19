package dev.tramai.orchestration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class WorkerShutdownCoordinatorTest {

    private val config = WorkerConfig(
        workerId = "shutdown-test",
        poolName = "tests",
        pollIntervalMillis = 20,
        leaseDurationMillis = 5_000,
        drainTimeoutMillis = 60_000,
    )

    private class RecordingObserver : TramaiWorkerObserver {
        val events = CopyOnWriteArrayList<String>()

        override fun onShutdownStarted(workerId: String) {
            events += "shutdownStarted"
        }

        override fun onDrainProgress(workerId: String, done: Int, pending: Int) {
            events += "drainProgress($done,$pending)"
        }

        override fun onShutdownComplete(workerId: String) {
            events += "shutdownComplete"
        }

        override fun onWorkerStopped(workerId: String) {
            events += "workerStopped"
        }

        override fun onLeaseAcquired(workflowId: String, workerId: String) {
            events += "leaseAcquired"
        }

        override fun onLeaseReleased(workflowId: String, workerId: String) {
            events += "leaseReleased"
        }

        override fun onStepAttemptStarted(runId: String, stepName: String, attemptId: String, workerId: String) {
            events += "attemptStarted"
        }

        override fun onStepAttemptCompleted(runId: String, stepName: String, attemptId: String, workerId: String) {
            events += "attemptCompleted"
        }

        override fun onStepAttemptFailed(runId: String, stepName: String, attemptId: String, workerId: String, error: Throwable) {
            events += "attemptFailed"
        }
    }

    private fun harness(
        drainTimeoutMillis: Long = 60_000,
        observer: RecordingObserver = RecordingObserver(),
        workflow: Workflow<TestState, String>,
    ): ShutdownHarness {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val config = this.config.copy(drainTimeoutMillis = drainTimeoutMillis)
        val leaseCoordinator = LeaseCoordinator(config, leaseStore, observer)

        // The supervisor reads the graceful-shutdown flag through a lambda;
        // the coordinator is assigned right after (same pattern as the
        // lifecycle controller). Only invoked during execution, so lateinit is safe.
        lateinit var coordinator: WorkerShutdownCoordinator
        val supervisor = WorkflowExecutionSupervisor(
            config = config,
            leaseStore = leaseStore,
            checkpointStore = store,
            stepAttemptStore = store,
            workflowBindings = WorkflowBindingRegistry {
                bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = TestCodec))
            },
            observability = observer,
            leaseCoordinator = leaseCoordinator,
            recoveryCoordinator = WorkflowRecoveryCoordinator(leaseStore, store),
            leaseRenewalLoop = LeaseRenewalLoop(config, leaseStore, observer),
            shuttingDownGracefully = { coordinator.isShuttingDownGracefully() },
        )
        coordinator = WorkerShutdownCoordinator(config, observer, supervisor, leaseStore)
        val rootSupervisor = SupervisorJob()
        val scope = CoroutineScope(rootSupervisor + Dispatchers.Default)
        supervisor.attachScope(scope)
        return ShutdownHarness(store, leaseStore, supervisor, coordinator, rootSupervisor, scope, observer, config)
    }

    private class ShutdownHarness(
        val store: InMemoryWorkflowCheckpointStore,
        val leaseStore: InMemoryWorkflowLeaseStore,
        val supervisor: WorkflowExecutionSupervisor,
        val coordinator: WorkerShutdownCoordinator,
        val rootSupervisor: Job,
        val scope: CoroutineScope,
        val observer: RecordingObserver,
        val config: WorkerConfig,
    ) {
        val pollJob: Job = scope.launch { delay(Long.MAX_VALUE) }
        val heartbeatJob: Job = scope.launch { delay(Long.MAX_VALUE) }

        fun start(workflowId: String = "w-1") {
            coordinator.prepareStart()
            coordinator.onStarted(pollJob, heartbeatJob, Thread { })
        }

        fun registerWorker() {
            runBlocking {
                leaseStore.registerWorker(
                    workerId = config.workerId,
                    poolName = config.poolName,
                    version = "test",
                    capabilityLabels = emptySet(),
                    host = "test",
                )
            }
        }
    }

    @Test
    fun `shutdown drains a running execution to completion before finishing`() {
        val gate = CompletableDeferred<Unit>()
        val workflow = workflow<TestState>("wf", definitionVersion = "v1") {
            localStep("mark") { state, _ -> gate.await(); state.copy(value = "done") }
        }.build { it.value }
        val h = harness(workflow = workflow)
        h.start()
        h.registerWorker()

        val cp = WorkflowCheckpoint(
            workflowName = "wf",
            workflowId = "w-1",
            nextStepIndex = 0,
            stepExecutions = 0,
            lastCompletedStepName = null,
            statePayload = TestCodec.encode(TestState("start")),
            metadata = workflow.checkpointMetadata(),
        )
        val saved = runBlocking { h.store.save(cp) }
        val lease = runBlocking {
            h.leaseStore.claim("wf", "w-1", "shutdown-test", saved.revision, 5_000)
        }
        h.supervisor.launch(saved, lease)

        runBlocking {
            withTimeout(10_000) {
                while (!h.observer.events.contains("attemptStarted")) delay(5)
            }
            // Shutdown starts while the execution is mid-step; the drain waits on it.
            val shutdownJob = launch { h.coordinator.shutdown(h.rootSupervisor) }
            withTimeout(10_000) {
                while (!h.observer.events.contains("shutdownStarted")) delay(5)
            }
            // The drain must not have given up while the execution is still running.
            assertThat(h.observer.events).doesNotContain("drainProgress(1,0)", "shutdownComplete")
            gate.complete(Unit)
            withTimeout(10_000) { shutdownJob.join() }
        }

        val attempt = runBlocking { h.store.listStepAttempts("w-1") }.single()
        assertThat(attempt.status).isEqualTo(StepAttemptStatus.COMPLETED)
        assertThat(h.observer.events).containsSubsequence(
            "shutdownStarted",
            "attemptCompleted",
            "drainProgress(1,0)",
            "shutdownComplete",
            "workerStopped",
        )
        assertThat(h.pollJob.isCancelled).isTrue()
        assertThat(h.heartbeatJob.isCancelled).isTrue()
        assertThat(h.rootSupervisor.isCancelled).isTrue()
        // Unregister happened: the worker is no longer listed.
        assertThat(runBlocking { h.leaseStore.listActiveWorkers() }).isEmpty()
        h.scope.cancel()
    }

    @Test
    fun `drain timeout cancels the remaining execution`() {
        val never = CompletableDeferred<Unit>()
        val workflow = workflow<TestState>("wf", definitionVersion = "v1") {
            localStep("mark") { state, _ -> never.await(); state }
        }.build { it.value }
        val h = harness(drainTimeoutMillis = 150, workflow = workflow)
        h.start()

        val saved = runBlocking {
            h.store.save(
                WorkflowCheckpoint(
                    workflowName = "wf",
                    workflowId = "w-1",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = TestCodec.encode(TestState("start")),
                    metadata = workflow.checkpointMetadata(),
                ),
            )
        }
        val lease = runBlocking {
            h.leaseStore.claim("wf", "w-1", "shutdown-test", saved.revision, 5_000)
        }
        h.supervisor.launch(saved, lease)
        runBlocking {
            withTimeout(10_000) {
                while (!h.observer.events.contains("attemptStarted")) delay(5)
            }
            withTimeout(10_000) { h.coordinator.shutdown(h.rootSupervisor) }
        }
        assertThat(h.observer.events).anyMatch { it.startsWith("drainProgress") }
        assertThat(h.rootSupervisor.isCancelled).isTrue()
        h.scope.cancel()
    }

    @Test
    fun `repeated shutdown is idempotent`() {
        val workflow = workflow<TestState>("wf", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state }
        }.build { it.value }
        val h = harness(workflow = workflow)
        h.start()
        runBlocking {
            withTimeout(10_000) { h.coordinator.shutdown(h.rootSupervisor) }
            withTimeout(10_000) { h.coordinator.shutdown(h.rootSupervisor) }
        }
        assertThat(h.observer.events.count { it == "shutdownStarted" }).isEqualTo(1)
        assertThat(h.observer.events.count { it == "shutdownComplete" }).isEqualTo(1)
        assertThat(h.observer.events.count { it == "workerStopped" }).isEqualTo(1)
        h.scope.cancel()
    }

    private data class TestState(val value: String)

    private object TestCodec : WorkflowStateCodec<TestState> {
        override fun encode(state: TestState): String = state.value
        override fun decode(payload: String): TestState = TestState(payload)
    }
}
