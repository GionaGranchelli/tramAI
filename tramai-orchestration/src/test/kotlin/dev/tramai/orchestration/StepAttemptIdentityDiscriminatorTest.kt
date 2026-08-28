package dev.tramai.orchestration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 8.3b2b — step-attempt identity authority.
 *
 * Invariant: every newly-created StepAttemptRecord.attemptId originates from one
 * explicit orchestration composition authority ([StepAttemptIdentitySource]).
 * Attempt identity is opaque: it never acquires chronology, wall-time, lease,
 * or claim authority, and update/CAS/re-record never manufacture replacement
 * identity.
 *
 * The harness exhausts the source with IllegalStateException on any extra
 * sample, so "sampled exactly once" and "zero new identity" are structural:
 * any hidden re-sampling fails the test instead of being asserted away.
 */
class StepAttemptIdentityDiscriminatorTest {

    private val config = WorkerConfig(
        workerId = "identity-supervisor",
        poolName = "tests",
        pollIntervalMillis = 20,
        leaseDurationMillis = 5_000,
        drainTimeoutMillis = 1_000,
    )

    private class QueuedStepAttemptIdentitySource(vararg ids: String) : StepAttemptIdentitySource {
        private val queue = ArrayDeque(ids.toList())
        var samples: Int = 0
            private set

        override fun newAttemptId(): String {
            samples++
            return queue.removeFirstOrNull()
                ?: throw IllegalStateException("identity source exhausted — unexpected extra sample")
        }
    }

    private data class TestState(val value: String)

    private object TestCodec : WorkflowStateCodec<TestState> {
        override fun encode(state: TestState): String = state.value
        override fun decode(payload: String): TestState = TestState(payload)
    }

    private class RecordingObserver : TramaiWorkerObserver {
        val leaseReleased = CopyOnWriteArrayList<String>()

        override fun onLeaseReleased(workflowId: String, workerId: String) {
            leaseReleased += workflowId
        }
    }

    private fun supervisor(
        store: InMemoryWorkflowCheckpointStore,
        leaseStore: InMemoryWorkflowLeaseStore,
        workflowBindings: WorkflowBindingRegistry,
        identitySource: StepAttemptIdentitySource,
    ): Pair<WorkflowExecutionSupervisor, RecordingObserver> {
        val observer = RecordingObserver()
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
            stepAttemptIdentitySource = identitySource,
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
        ownerId = "identity-supervisor",
        checkpointRevision = 1L,
        acquiredAtEpochMillis = 0L,
        expiresAtEpochMillis = 5_000L,
    )

    private fun attempt(
        attemptId: String,
        status: StepAttemptStatus,
        stepName: String = "plan",
    ) = StepAttemptRecord(
        runId = "w-1",
        stepName = stepName,
        attemptId = attemptId,
        workerId = "worker",
        leaseToken = "lease",
        status = status,
        startedAt = 1_000L,
        replayPolicy = ReplayPolicy.IDEMPOTENT,
    )

    private fun runToCompletion(
        store: InMemoryWorkflowCheckpointStore,
        leaseStore: InMemoryWorkflowLeaseStore,
        workflow: Workflow<TestState, String>,
        identitySource: StepAttemptIdentitySource,
    ): Pair<WorkflowExecutionSupervisor, RecordingObserver> {
        val bindings = WorkflowBindingRegistry {
            bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = TestCodec))
        }
        val (s, observer) = supervisor(store, leaseStore, bindings, identitySource)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        s.attachScope(scope)
        val cp = runBlocking { store.save(checkpoint(metadata = workflow.checkpointMetadata())) }
        val lease = runBlocking {
            leaseStore.claim("wf", "w-1", "identity-supervisor", cp.revision, 5_000)
        }
        s.launch(cp, lease)
        try {
            runBlocking {
                withTimeout(10_000) {
                    while (s.activeExecutionCount() != 0) delay(10)
                }
            }
        } finally {
            scope.cancel()
        }
        return s to observer
    }

    private fun singleStepWorkflow(): Workflow<TestState, String> =
        workflow<TestState>("wf", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = "done") }
        }.build { it.value }

    @Test
    fun `P0-A injected attempt ID is persisted exactly`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val source = QueuedStepAttemptIdentitySource("attempt-A")

        val (s, _) = runToCompletion(store, leaseStore, singleStepWorkflow(), source)

        val attempt = runBlocking { store.listStepAttempts("w-1") }.single()
        assertThat(attempt.attemptId).isEqualTo("attempt-A")
        assertThat(source.samples).isEqualTo(1)
        assertThat(s.latestFailure("w-1")).isNull()
    }

    @Test
    fun `P0-B source sampled exactly once per newly-created attempt`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val source = QueuedStepAttemptIdentitySource("attempt-B")

        runToCompletion(store, leaseStore, singleStepWorkflow(), source)

        val attempts = runBlocking { store.listStepAttempts("w-1") }
        assertThat(attempts).hasSize(1)
        assertThat(source.samples).isEqualTo(1)
    }

    @Test
    fun `P0-C two created attempts consume two distinct source values in creation order`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val source = QueuedStepAttemptIdentitySource("attempt-C1", "attempt-C2")
        val workflow = workflow<TestState>("wf", definitionVersion = "v1") {
            localStep("first") { state, _ -> state.copy(value = "one") }
            localStep("second") { state, _ -> state.copy(value = "two") }
        }.build { it.value }

        runToCompletion(store, leaseStore, workflow, source)

        val attempts = runBlocking { store.listStepAttempts("w-1") }
        assertThat(attempts.map { it.attemptId })
            .containsExactly("attempt-C1", "attempt-C2")
        assertThat(attempts.map { it.attemptId }.distinct()).hasSize(2)
        assertThat(source.samples).isEqualTo(2)
    }

    @Test
    fun `P0-D update preserves original ID and consumes zero new identity`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val source = QueuedStepAttemptIdentitySource("attempt-D")

        runToCompletion(store, leaseStore, singleStepWorkflow(), source)

        val attempt = runBlocking { store.listStepAttempts("w-1") }.single()
        assertThat(attempt.attemptId).isEqualTo("attempt-D")
        assertThat(attempt.status).isEqualTo(StepAttemptStatus.COMPLETED)
        assertThat(source.samples).isEqualTo(1)
    }

    @Test
    fun `P0-E CAS preserves original ID and consumes zero new identity`() {
        val store = InMemoryWorkflowCheckpointStore()
        val source = QueuedStepAttemptIdentitySource()
        runBlocking {
            val original = attempt("attempt-E", StepAttemptStatus.STARTED)
            store.recordStepAttempt(original)
            val updated = original.copy(
                status = StepAttemptStatus.COMPLETED,
                completedAt = 2_000L,
            )

            val replaced = store.compareAndSetStepAttempt(original, updated)

            assertThat(replaced).isTrue()
            assertThat(store.latestStepAttempt("w-1", "plan")?.attemptId).isEqualTo("attempt-E")
            assertThat(source.samples).isEqualTo(0)
        }
    }

    @Test
    fun `P0-F chronology remains governed by creation order, never attempt ID`() {
        val store = InMemoryWorkflowCheckpointStore()
        val source = QueuedStepAttemptIdentitySource("zzz-b2b-original", "aaa-b2b-rerun")
        runBlocking {
            // Ids produced by the identity authority in lexical-opposite order to creation.
            val original = attempt(source.newAttemptId(), StepAttemptStatus.UNKNOWN)
            val rerun = attempt(source.newAttemptId(), StepAttemptStatus.COMPLETED)
            store.recordStepAttempt(original)
            store.recordStepAttempt(rerun)

            assertThat(store.latestStepAttempt("w-1", "plan")?.attemptId)
                .withFailMessage("Equal startedAt must resolve 'latest' to the last-created attempt, never the attempt id")
                .isEqualTo("aaa-b2b-rerun")
            assertThat(store.listStepAttempts("w-1").map { it.attemptId })
                .withFailMessage("Equal startedAt must order by creation, never by the attempt id")
                .containsExactly("zzz-b2b-original", "aaa-b2b-rerun")
            assertThat(source.samples).isEqualTo(2)
        }
    }

    @Test
    fun `P0-G blank generated ID fails before persistence`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val source = QueuedStepAttemptIdentitySource("  ")

        val (s, _) = runToCompletion(store, leaseStore, singleStepWorkflow(), source)

        assertThat(s.latestFailure("w-1"))
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("attemptId")
        assertThat(runBlocking { store.listStepAttempts("w-1") }).isEmpty()
        assertThat(source.samples).isEqualTo(1)
    }

    @Test
    fun `P0-H failAttempt preserves original ID and consumes zero new identity`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val source = QueuedStepAttemptIdentitySource("attempt-H")
        val workflow = workflow<TestState>("wf", definitionVersion = "v1") {
            localStep("boom") { _, _ -> throw IllegalStateException("step exploded") }
        }.build { it.value }

        runToCompletion(store, leaseStore, workflow, source)

        val attempt = runBlocking { store.listStepAttempts("w-1") }.single()
        assertThat(attempt.attemptId).isEqualTo("attempt-H")
        assertThat(attempt.status).isEqualTo(StepAttemptStatus.FAILED)
        assertThat(source.samples).isEqualTo(1)
    }
}
