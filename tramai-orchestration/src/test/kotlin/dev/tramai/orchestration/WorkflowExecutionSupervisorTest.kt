package dev.tramai.orchestration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class WorkflowExecutionSupervisorTest {

    private val config = WorkerConfig(
        workerId = "supervisor-test",
        poolName = "tests",
        pollIntervalMillis = 20,
        leaseDurationMillis = 5_000,
        drainTimeoutMillis = 1_000,
    )

    private class RecordingObserver : TramaiWorkerObserver {
        val leaseReleased = CopyOnWriteArrayList<String>()
        val leaseExpired = CopyOnWriteArrayList<String>()

        override fun onLeaseReleased(workflowId: String, workerId: String) {
            leaseReleased += workflowId
        }

        override fun onLeaseExpired(workflowId: String, workerId: String) {
            leaseExpired += workflowId
        }
    }

    private fun supervisor(
        store: InMemoryWorkflowCheckpointStore,
        leaseStore: InMemoryWorkflowLeaseStore,
        workflowBindings: WorkflowBindingRegistry,
        observer: RecordingObserver = RecordingObserver(),
    ): Pair<WorkflowExecutionSupervisor, RecordingObserver> {
        val leaseCoordinator = LeaseCoordinator(config, leaseStore, observer)
        val s = WorkflowExecutionSupervisor(
            config = config,
            leaseStore = leaseStore,
            checkpointStore = store,
            stepAttemptStore = store,
            workflowBindings = workflowBindings,
            observability = observer,
            leaseCoordinator = leaseCoordinator,
            recoveryCoordinator = WorkflowRecoveryCoordinator(leaseStore, store),
            leaseRenewalLoop = LeaseRenewalLoop(config, leaseStore, observer),
            shuttingDownGracefully = { false },
        )
        return s to observer
    }

    private fun checkpoint(
        name: String = "wf",
        id: String = "w-1",
        metadata: Map<String, String> = mapOf(WORKFLOW_DEFINITION_VERSION_METADATA_KEY to "v1"),
    ): WorkflowCheckpoint = WorkflowCheckpoint(
        workflowName = name,
        workflowId = id,
        nextStepIndex = 0,
        stepExecutions = 0,
        lastCompletedStepName = null,
        statePayload = TestCodec.encode(TestState("start")),
        metadata = metadata,
    )

    private fun lease(
        name: String = "wf",
        id: String = "w-1",
        leaseId: String = "l-1",
    ): WorkflowLease = WorkflowLease(
        workflowName = name,
        workflowId = id,
        leaseId = leaseId,
        ownerId = "supervisor-test",
        checkpointRevision = 1L,
        acquiredAtEpochMillis = 0L,
        expiresAtEpochMillis = 5_000L,
    )

    @Test
    fun `binding found executes the workflow and clears active state`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workflow<TestState>("wf", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = "done") }
        }.build { it.value }
        val bindings = WorkflowBindingRegistry {
            bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = TestCodec))
        }
        val (s, observer) = supervisor(store, leaseStore, bindings)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        s.attachScope(scope)
        val cp = runBlocking { store.save(checkpoint(metadata = workflow.checkpointMetadata())) }
        val lease = runBlocking { leaseStore.claim("wf", "w-1", "supervisor-test", cp.revision, 5_000) }
        s.launch(cp, lease)
        runBlocking {
            withTimeout(10_000) {
                while (s.activeExecutionCount() != 0) delay(10)
            }
        }
        assertThat(runBlocking { store.load("wf", "w-1") }).isNull()
        val attempt = runBlocking { store.listStepAttempts("w-1") }.single()
        assertThat(attempt.status).isEqualTo(StepAttemptStatus.COMPLETED)
        assertThat(s.isRunning("w-1")).isFalse()
        assertThat(s.latestFailure("w-1")).isNull()
        assertThat(observer.leaseReleased).contains("w-1")
        scope.cancel()
    }

    @Test
    fun `duplicate registration releases the loser lease`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val workflow = workflow<TestState>("wf", definitionVersion = "v1") {
            localStep("hold") { state, _ -> gate.await(); state }
        }.build { it.value }
        val bindings = WorkflowBindingRegistry {
            bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = TestCodec))
        }
        val (s, observer) = supervisor(store, leaseStore, bindings)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        s.attachScope(scope)
        val cp = runBlocking { store.save(checkpoint(metadata = workflow.checkpointMetadata())) }
        val lease = runBlocking {
            leaseStore.claim("wf", "w-1", "supervisor-test", cp.revision, 5_000)
        }
        s.launch(cp, lease)
        // Second registration of the same workflow: the loser must release its lease.
        s.launch(cp, lease)
        runBlocking {
            withTimeout(10_000) {
                while (observer.leaseReleased.isEmpty()) delay(10)
            }
        }
        assertThat(s.activeExecutionCount()).isEqualTo(1)
        gate.complete(Unit)
        scope.cancel()
    }

    @Test
    fun `unknown definition version releases the lease and skips execution`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workflow<TestState>("wf", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state }
        }.build { it.value }
        val bindings = WorkflowBindingRegistry {
            bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = TestCodec))
        }
        val (s, observer) = supervisor(store, leaseStore, bindings)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        s.attachScope(scope)
        val cp = runBlocking {
            store.save(
                checkpoint(metadata = workflow.checkpointMetadata() + mapOf(WORKFLOW_DEFINITION_VERSION_METADATA_KEY to "v9")),
            )
        }
        val lease = runBlocking { leaseStore.claim("wf", "w-1", "supervisor-test", cp.revision, 5_000) }
        s.launch(cp, lease)
        runBlocking {
            withTimeout(10_000) {
                while (s.activeExecutionCount() != 0) delay(10)
            }
        }
        // Skip: no failure recorded, lease released, checkpoint retained, no attempt.
        assertThat(s.latestFailure("w-1")).isNull()
        assertThat(observer.leaseReleased).contains("w-1")
        assertThat(runBlocking { store.load("wf", "w-1") }).isNotNull()
        assertThat(runBlocking { store.listStepAttempts("w-1") }).isEmpty()
        scope.cancel()
    }

    @Test
    fun `missing definition version metadata fails visibly and releases the lease`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workflow<TestState>("wf", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state }
        }.build { it.value }
        val bindings = WorkflowBindingRegistry {
            bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = TestCodec))
        }
        val (s, observer) = supervisor(store, leaseStore, bindings)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        s.attachScope(scope)
        val cp = runBlocking { store.save(checkpoint(metadata = emptyMap())) }
        val lease = runBlocking { leaseStore.claim("wf", "w-1", "supervisor-test", cp.revision, 5_000) }
        s.launch(cp, lease)
        runBlocking {
            withTimeout(10_000) {
                while (s.activeExecutionCount() != 0) delay(10)
            }
        }
        assertThat(s.latestFailure("w-1"))
            .isInstanceOf(WorkflowResumeException::class.java)
            .hasMessageContaining("missing required workflow definition metadata")
        assertThat(observer.leaseReleased).contains("w-1")
        assertThat(runBlocking { store.listStepAttempts("w-1") }).isEmpty()
        scope.cancel()
    }

    @Test
    fun `workflow suspension cleans up the attempt and releases the lease`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workflow<TestState>("wf", definitionVersion = "v1") {
            localStep("mark") { state, _ -> throw WorkflowSuspendedException("yield") }
        }.build { it.value }
        val bindings = WorkflowBindingRegistry {
            bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = TestCodec))
        }
        val (s, observer) = supervisor(store, leaseStore, bindings)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        s.attachScope(scope)
        val cp = runBlocking { store.save(checkpoint(metadata = workflow.checkpointMetadata())) }
        s.launch(cp, lease())
        runBlocking {
            withTimeout(10_000) {
                while (s.activeExecutionCount() != 0) delay(10)
            }
        }
        assertThat(s.latestFailure("w-1")).isNull()
        val attempt = runBlocking { store.listStepAttempts("w-1") }.single()
        // The step observer records the thrown suspension as a failed attempt before
        // the suspend catch runs (pre-existing worker semantics, preserved verbatim).
        assertThat(attempt.status).isEqualTo(StepAttemptStatus.FAILED)
        assertThat(observer.leaseReleased).contains("w-1")
        scope.cancel()
    }

    @Test
    fun `failed execution records latestFailure`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workflow<TestState>("wf", definitionVersion = "v1") {
            localStep("mark") { state, _ -> throw IllegalStateException("boom") }
        }.build { it.value }
        val bindings = WorkflowBindingRegistry {
            bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = TestCodec))
        }
        val (s, observer) = supervisor(store, leaseStore, bindings)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        s.attachScope(scope)
        val cp = runBlocking { store.save(checkpoint(metadata = workflow.checkpointMetadata())) }
        val lease = runBlocking { leaseStore.claim("wf", "w-1", "supervisor-test", cp.revision, 5_000) }
        s.launch(cp, lease)
        runBlocking {
            // Wait for terminal execution state: latestFailure becomes visible
            // before the durable attempt transition to FAILED (production sets
            // executionFailures before failActiveAttempt), so waiting on
            // latestFailure alone is racy.
            withTimeout(10_000) {
                while (s.activeExecutionCount() != 0) delay(10)
            }
        }
        assertThat(s.latestFailure("w-1")).isInstanceOf(IllegalStateException::class.java)
        val attempt = runBlocking { store.listStepAttempts("w-1") }.single()
        assertThat(attempt.status).isEqualTo(StepAttemptStatus.FAILED)
        scope.cancel()
    }

    @Test
    fun `snapshot iteration is safe when the map shrinks between size and iteration`() {
        // Models ConcurrentHashMap.values in the observed shutdown race: the
        // collection reports size == 1, then the map empties before the
        // iterator advances. Executes the SAME snapshot function production
        // uses (stableConcurrentSnapshot), so a regression back to
        // Collection.toList() in the snapshot path makes this RED.
        val shrinking = object : Collection<ActiveExecution> {
            override val size: Int get() = 1
            override fun isEmpty(): Boolean = false
            override fun iterator(): Iterator<ActiveExecution> = emptyList<ActiveExecution>().iterator()
            override fun contains(element: ActiveExecution): Boolean = false
            override fun containsAll(elements: Collection<ActiveExecution>): Boolean = false
        }
        assertThatThrownBy { shrinking.toList() }
            .isInstanceOf(NoSuchElementException::class.java)
        assertThat(stableConcurrentSnapshot(shrinking)).isEmpty()
    }

    private data class TestState(val value: String)

    private object TestCodec : WorkflowStateCodec<TestState> {
        override fun encode(state: TestState): String = state.value
        override fun decode(payload: String): TestState = TestState(payload)
    }
}
