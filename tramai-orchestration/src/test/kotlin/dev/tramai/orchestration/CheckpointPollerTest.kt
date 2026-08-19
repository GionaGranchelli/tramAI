package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class CheckpointPollerTest {

    private val workerConfig = WorkerConfig(
        workerId = "poll-test",
        poolName = "tests",
        pollIntervalMillis = 20,
        leaseDurationMillis = 5_000,
        drainTimeoutMillis = 1_000,
    )

    private class RecordingObserver : TramaiWorkerObserver {
        val leaseAcquired = CopyOnWriteArrayList<String>()
        val pollFailures = CopyOnWriteArrayList<Throwable>()

        override fun onLeaseAcquired(workflowId: String, workerId: String) {
            leaseAcquired += workflowId
        }

        override fun onPollFailed(workerId: String, error: Throwable) {
            pollFailures += error
        }
    }

    private fun poller(
        config: WorkerConfig = workerConfig,
        store: InMemoryWorkflowCheckpointStore = InMemoryWorkflowCheckpointStore(),
        leaseStore: InMemoryWorkflowLeaseStore = InMemoryWorkflowLeaseStore(),
        partitionStrategy: PartitionAssignmentStrategy = ModHashPartitionStrategy(),
        observer: RecordingObserver = RecordingObserver(),
        acceptingWork: () -> Boolean = { true },
    ): Triple<CheckpointPoller, InMemoryWorkflowCheckpointStore, RecordingObserver> {
        val supervisor = WorkflowExecutionSupervisor(
            config = config,
            leaseStore = leaseStore,
            checkpointStore = store,
            stepAttemptStore = store,
            workflowBindings = WorkflowBindingRegistry { },
            observability = observer,
            leaseCoordinator = LeaseCoordinator(config, leaseStore, observer),
            recoveryCoordinator = WorkflowRecoveryCoordinator(leaseStore, store),
            leaseRenewalLoop = LeaseRenewalLoop(config, leaseStore, observer),
            shuttingDownGracefully = { false },
        )
        val coordinator = LeaseCoordinator(config, leaseStore, observer)
        val p = CheckpointPoller(
            config = config,
            checkpointCatalog = store,
            workerRegistryStore = leaseStore,
            partitionStrategy = partitionStrategy,
            leaseCoordinator = coordinator,
            executionSupervisor = supervisor,
            observability = observer,
            acceptingWork = acceptingWork,
        )
        return Triple(p, store, observer)
    }

    private fun checkpoint(
        store: InMemoryWorkflowCheckpointStore,
        name: String,
        id: String,
        metadata: Map<String, String> = mapOf(WORKFLOW_DEFINITION_VERSION_METADATA_KEY to "v1"),
    ): WorkflowCheckpoint = WorkflowCheckpoint(
        workflowName = name,
        workflowId = id,
        nextStepIndex = 0,
        stepExecutions = 0,
        lastCompletedStepName = null,
        statePayload = "start",
        metadata = metadata,
    ).also { runBlocking { store.save(it) } }

    @Test
    fun `pollOnce claims checkpoints in deterministic name then id order`() {
        val (p, store, observer) = poller()
        // Ids chosen so name-ordering and id-ordering disagree: sorted by
        // (name, id) the claim order must be w-3, w-2, w-1.
        checkpoint(store, "b", "w-2")
        checkpoint(store, "a", "w-3")
        checkpoint(store, "c", "w-1")
        runBlocking {
            p.pollOnce()
        }
        assertThat(observer.leaseAcquired).containsExactly("w-3", "w-2", "w-1")
    }

    @Test
    fun `partition rejection skips the checkpoint`() {
        val never = PartitionAssignmentStrategy { _, _, _ -> false }
        val (p, store, observer) = poller(
            config = workerConfig.copy(partitionEnabled = true),
            partitionStrategy = never,
        )
        checkpoint(store, "a", "w-1")
        runBlocking {
            p.pollOnce()
        }
        assertThat(observer.leaseAcquired).isEmpty()
    }

    @Test
    fun `a checkpoint already active on this worker is not claimed again`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val observer = RecordingObserver()
        val config = workerConfig
        val coordinator = LeaseCoordinator(config, leaseStore, observer)
        val cp = checkpoint(store, "a", "w-1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val workflow = workflow<TestState>("a", definitionVersion = "v1") {
            localStep("hold") { state, _ -> gate.await(); state }
        }.build { it.value }
        val binding = WorkflowBindingRegistry {
            bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = TestCodec))
        }
        val activeSupervisor = WorkflowExecutionSupervisor(
            config = config,
            leaseStore = leaseStore,
            checkpointStore = store,
            stepAttemptStore = store,
            workflowBindings = binding,
            observability = observer,
            leaseCoordinator = coordinator,
            recoveryCoordinator = WorkflowRecoveryCoordinator(leaseStore, store),
            leaseRenewalLoop = LeaseRenewalLoop(config, leaseStore, observer),
            shuttingDownGracefully = { false },
        )
        activeSupervisor.attachScope(scope)
        val p = CheckpointPoller(
            config = config,
            checkpointCatalog = store,
            workerRegistryStore = leaseStore,
            partitionStrategy = ModHashPartitionStrategy(),
            leaseCoordinator = coordinator,
            executionSupervisor = activeSupervisor,
            observability = observer,
            acceptingWork = { true },
        )
        val lease = runBlocking { leaseStore.claim("a", "w-1", "poll-test", cp.revision, 5_000) }
        activeSupervisor.launch(cp, lease)
        runBlocking {
            p.pollOnce()
        }
        assertThat(observer.leaseAcquired).isEmpty()
        gate.complete(Unit)
        scope.cancel()
    }

    @Test
    fun `a checkpoint with an existing lease is skipped`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val observer = RecordingObserver()
        val (p, _, _) = poller(store = store, leaseStore = leaseStore, observer = observer)
        val cp = checkpoint(store, "a", "w-1")
        runBlocking {
            leaseStore.claim("a", "w-1", "other-owner", cp.revision, 5_000)
            p.pollOnce()
        }
        assertThat(observer.leaseAcquired).isEmpty()
    }

    @Test
    fun `a checkpoint requiring operator recovery is skipped silently`() {
        val (p, store, observer) = poller()
        val record = WorkflowRecoveryRecord(
            reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
            stepName = "mark",
            attemptId = "attempt-1",
            priorWorkerId = "prior",
            detectedAtEpochMillis = 0L,
        )
        val cp = WorkflowCheckpoint(
            workflowName = "a",
            workflowId = "w-1",
            nextStepIndex = 0,
            stepExecutions = 0,
            lastCompletedStepName = null,
            statePayload = "start",
            metadata = mapOf(WORKFLOW_DEFINITION_VERSION_METADATA_KEY to "v1"),
            recoveryState = WorkflowRecoveryState.Required(record),
        )
        runBlocking {
            store.save(cp)
            p.pollOnce()
        }
        assertThat(observer.leaseAcquired).isEmpty()
    }

    @Test
    fun `poll failure is observable and cancellation is preserved`() {
        val throwingCatalog = object : WorkflowCheckpointCatalog {
            override suspend fun listCheckpoints(): List<WorkflowCheckpoint> =
                throw IllegalStateException("catalog down")
        }
        val observer = RecordingObserver()
        val coordinator = LeaseCoordinator(workerConfig, InMemoryWorkflowLeaseStore(), observer)
        val supervisor = WorkflowExecutionSupervisor(
            config = workerConfig,
            leaseStore = InMemoryWorkflowLeaseStore(),
            checkpointStore = InMemoryWorkflowCheckpointStore(),
            stepAttemptStore = InMemoryWorkflowCheckpointStore(),
            workflowBindings = WorkflowBindingRegistry { },
            observability = observer,
            leaseCoordinator = coordinator,
            recoveryCoordinator = WorkflowRecoveryCoordinator(InMemoryWorkflowLeaseStore(), InMemoryWorkflowCheckpointStore()),
            leaseRenewalLoop = LeaseRenewalLoop(workerConfig, InMemoryWorkflowLeaseStore(), observer),
            shuttingDownGracefully = { false },
        )
        val p = CheckpointPoller(
            config = workerConfig,
            checkpointCatalog = throwingCatalog,
            workerRegistryStore = null,
            partitionStrategy = ModHashPartitionStrategy(),
            leaseCoordinator = coordinator,
            executionSupervisor = supervisor,
            observability = observer,
            acceptingWork = { true },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch { p.pollLoop() }
        runBlocking {
            withTimeout(5_000) {
                while (observer.pollFailures.isEmpty()) delay(5)
            }
        }
        assertThat(observer.pollFailures).hasSize(1)
        // The safe persistence boundary sanitizes the raw catalog failure.
        assertThat(observer.pollFailures.single())
            .hasMessageContaining("Workflow persistence list failed")
        // Cancellation escapes the loop instead of being converted into a poll failure.
        runBlocking {
            job.cancel()
            withTimeout(5_000) { job.join() }
        }
        assertThat(observer.pollFailures).hasSize(1)
        assertThat(job.isCancelled).isTrue()
        scope.cancel()
    }

    private data class TestState(val value: String)

    private object TestCodec : WorkflowStateCodec<TestState> {
        override fun encode(state: TestState): String = state.value
        override fun decode(payload: String): TestState = TestState(payload)
    }
}
