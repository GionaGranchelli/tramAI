package dev.tramai.orchestration

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Proves synchronous, execution-owned step-attempt identity establishment (D7-A1).
 *
 * Invariants proven:
 * 1. Synchronous attempt visibility: during step body execution, the attempt record is
 *    guaranteed to be persisted in [StepAttemptRecordStore] with status [StepAttemptStatus.STARTED].
 * 2. Scope propagation: [currentStepAttempt] returns the exact [PersistedStepAttempt] matching
 *    the persisted record.
 * 3. Lineage inheritance: nested steps inherit the enclosing attempt's identity from the coroutine context.
 * 4. Ungoverned compatibility: ungoverned runs run with `currentStepAttempt() == null` without overhead or error.
 */
class StepAttemptSynchronousAuthorityTest {
    private val config =
        WorkerConfig(
            workerId = "sync-attempt-supervisor",
            poolName = "tests",
            pollIntervalMillis = 20,
            leaseDurationMillis = 5_000,
            drainTimeoutMillis = 1_000,
        )

    private class SingleAttemptIdentitySource(
        private val id: String,
    ) : StepAttemptIdentitySource {
        private var sampled = false

        override fun newAttemptId(): String {
            check(!sampled) { "SingleAttemptIdentitySource sampled more than once" }
            sampled = true
            return id
        }
    }

    private data class State(
        val value: String,
    )

    private object StateCodec : WorkflowStateCodec<State> {
        override fun encode(state: State): String = state.value

        override fun decode(payload: String): State = State(payload)
    }

    private class TestObserver : TramaiWorkerObserver {
        val startedAttempts = CopyOnWriteArrayList<String>()

        override fun onStepAttemptStarted(
            runId: String,
            stepName: String,
            attemptId: String,
            workerId: String,
        ) {
            startedAttempts += attemptId
        }
    }

    @Test
    fun `step body observes synchronously persisted attempt record with STARTED status`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val observedInBody = AtomicReference<PersistedStepAttempt?>()
        val persistedStatusInBody = AtomicReference<StepAttemptStatus?>()
        val stepBodyEntered = CompletableDeferred<Unit>()
        val proceedStepBody = CompletableDeferred<Unit>()

        val testWorkflow =
            buildSyncTestWorkflow(
                observedInBody,
                store,
                persistedStatusInBody,
                stepBodyEntered,
                proceedStepBody,
            )
        val supervisor =
            createTestSupervisor(
                store,
                leaseStore,
                testWorkflow,
                SingleAttemptIdentitySource("att-42"),
            )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        supervisor.attachScope(scope)

        val initialCheckpoint = createSyncInitialCheckpoint(testWorkflow)
        runBlocking {
            store.save(initialCheckpoint)
            val lease = leaseStore.claim("sync-wf", "run-1", config.workerId, 1L, 5_000)
            supervisor.launch(initialCheckpoint, lease)

            withTimeout(5_000) {
                stepBodyEntered.await()
            }

            assertThat(observedInBody.get()).isNotNull
            assertThat(observedInBody.get()?.attemptId).isEqualTo("att-42")
            assertThat(observedInBody.get()?.stepName).isEqualTo("governed-step")
            assertThat(observedInBody.get()?.workflowId).isEqualTo("run-1")
            assertThat(persistedStatusInBody.get()).isEqualTo(StepAttemptStatus.STARTED)

            proceedStepBody.complete(Unit)

            withTimeout(5_000) {
                while (supervisor.activeExecutionCount() != 0) delay(10)
            }
        }
        scope.cancel()

        val finalRecord = runBlocking { store.latestStepAttempt("run-1", "governed-step") }
        assertThat(finalRecord?.status).isEqualTo(StepAttemptStatus.COMPLETED)
        assertThat(finalRecord?.attemptId).isEqualTo("att-42")
    }

    private fun buildSyncTestWorkflow(
        observedInBody: AtomicReference<PersistedStepAttempt?>,
        store: InMemoryWorkflowCheckpointStore,
        persistedStatusInBody: AtomicReference<StepAttemptStatus?>,
        stepBodyEntered: CompletableDeferred<Unit>,
        proceedStepBody: CompletableDeferred<Unit>,
    ) = workflow<State>("sync-wf") {
        localStep("governed-step") { state, _ ->
            observedInBody.set(currentStepAttempt())
            val record = runBlocking { store.latestStepAttempt("run-1", "governed-step") }
            persistedStatusInBody.set(record?.status)
            stepBodyEntered.complete(Unit)
            runBlocking { proceedStepBody.await() }
            state
        }
    }.build { it }

    private fun createTestSupervisor(
        store: InMemoryWorkflowCheckpointStore,
        leaseStore: InMemoryWorkflowLeaseStore,
        testWorkflow: Workflow<State, State>,
        identity: StepAttemptIdentitySource,
    ): WorkflowExecutionSupervisor {
        val observer = TestObserver()
        return WorkflowExecutionSupervisor(
            config = config,
            leaseStore = leaseStore,
            checkpointStore = store,
            stepAttemptStore = store,
            workflowBindings =
                WorkflowBindingRegistry {
                    bind(testWorkflow, WorkflowPersistence(checkpointStore = store, stateCodec = StateCodec))
                },
            observability = observer,
            leaseCoordinator = LeaseCoordinator(config, leaseStore, observer),
            recoveryCoordinator = WorkflowRecoveryCoordinator(leaseStore, store),
            leaseRenewalLoop = LeaseRenewalLoop(config, leaseStore, observer),
            shuttingDownGracefully = { false },
            stepAttemptIdentitySource = identity,
        )
    }

    private fun createSyncInitialCheckpoint(testWorkflow: Workflow<State, State>) =
        WorkflowCheckpoint(
            workflowName = "sync-wf",
            workflowId = "run-1",
            nextStepIndex = 0,
            stepExecutions = 0,
            lastCompletedStepName = null,
            statePayload = StateCodec.encode(State("init")),
            metadata = testWorkflow.checkpointMetadata() + encodeGovernedRunAttribution(GOVERNED),
        )

    @Test
    fun `nested step inherits enclosing attempt lineage from coroutine context`() {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val identity = SingleAttemptIdentitySource("att-parent")

        val nestedObserved = AtomicReference<PersistedStepAttempt?>()

        val testWorkflow =
            workflow<State>("nested-sync-wf") {
                branchStep("outer-step", select = { "branch-a" }) {
                    branch("branch-a") {
                        localStep("nested-leaf") { state, _ ->
                            nestedObserved.set(currentStepAttempt())
                            state
                        }
                    }
                }
            }.build { it }

        val observer = TestObserver()
        val supervisor =
            WorkflowExecutionSupervisor(
                config = config,
                leaseStore = leaseStore,
                checkpointStore = store,
                stepAttemptStore = store,
                workflowBindings =
                    WorkflowBindingRegistry {
                        bind(testWorkflow, WorkflowPersistence(checkpointStore = store, stateCodec = StateCodec))
                    },
                observability = observer,
                leaseCoordinator = LeaseCoordinator(config, leaseStore, observer),
                recoveryCoordinator = WorkflowRecoveryCoordinator(leaseStore, store),
                leaseRenewalLoop = LeaseRenewalLoop(config, leaseStore, observer),
                shuttingDownGracefully = { false },
                stepAttemptIdentitySource = identity,
            )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        supervisor.attachScope(scope)

        val initialCheckpoint =
            WorkflowCheckpoint(
                workflowName = "nested-sync-wf",
                workflowId = "run-2",
                nextStepIndex = 0,
                stepExecutions = 0,
                lastCompletedStepName = null,
                statePayload = StateCodec.encode(State("init")),
                metadata = testWorkflow.checkpointMetadata() + encodeGovernedRunAttribution(GOVERNED),
            )

        runBlocking {
            store.save(initialCheckpoint)
            val lease = leaseStore.claim("nested-sync-wf", "run-2", config.workerId, 1L, 5_000)
            supervisor.launch(initialCheckpoint, lease)

            withTimeout(5_000) {
                while (supervisor.activeExecutionCount() != 0) delay(10)
            }
        }
        scope.cancel()

        assertThat(nestedObserved.get()).isNotNull
        assertThat(nestedObserved.get()?.attemptId).isEqualTo("att-parent")
        assertThat(nestedObserved.get()?.stepName).isEqualTo("outer-step")
    }

    @Test
    fun `direct ungoverned run executes normally without step attempt authority`() {
        val observedInBody = AtomicReference<PersistedStepAttempt?>()
        val store = InMemoryWorkflowCheckpointStore()

        val testWorkflow =
            workflow<State>("ungoverned-wf") {
                localStep("simple-step") { state, _ ->
                    observedInBody.set(currentStepAttempt())
                    state
                }
            }.build { it }

        runBlocking {
            testWorkflow.run(
                initialState = State("hello"),
                context = WorkflowContext(workflowId = "direct-run"),
                persistence = WorkflowPersistence(checkpointStore = store, stateCodec = StateCodec),
            )
        }

        assertThat(observedInBody.get()).isNull()
        assertThat(runBlocking { store.listStepAttempts("direct-run") }).isEmpty()
    }

    private companion object {
        private val GOVERNED =
            GovernedRunIdentity(
                deployment =
                    WorkloadDeploymentIdentity(
                        workloadId = WorkloadId("test-workload"),
                        configuration =
                            WorkloadConfigurationIdentity(ConfigurationId("conf"), ConfigurationVersion("1")),
                        environmentId = EnvironmentId("test-env"),
                        deploymentId = DeploymentId("test-dep"),
                    ),
                runId = RunId("test-run"),
            )
    }
}
