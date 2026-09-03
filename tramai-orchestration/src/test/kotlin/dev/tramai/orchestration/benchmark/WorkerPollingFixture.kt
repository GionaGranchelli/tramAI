package dev.tramai.orchestration.benchmark

import dev.tramai.orchestration.CheckpointPoller
import dev.tramai.orchestration.InMemoryWorkflowCheckpointStore
import dev.tramai.orchestration.InMemoryWorkflowLeaseStore
import dev.tramai.orchestration.LeaseCoordinator
import dev.tramai.orchestration.LeaseRenewalLoop
import dev.tramai.orchestration.ModHashPartitionStrategy
import dev.tramai.orchestration.TramaiWorkerObserver
import dev.tramai.orchestration.WORKFLOW_DEFINITION_VERSION_METADATA_KEY
import dev.tramai.orchestration.WorkerConfig
import dev.tramai.orchestration.WorkflowBindingRegistry
import dev.tramai.orchestration.WorkflowCheckpoint
import dev.tramai.orchestration.WorkflowExecutionSupervisor
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowRecoveryCoordinator
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.orchestration.workflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared real-polling fixture for the orchestration benchmarks (B10/B11).
 *
 * Builds the actual worker poll path used in production: CheckpointPoller
 * over in-memory persistence with a LeaseCoordinator claim and a
 * WorkflowExecutionSupervisor dispatch of a registered one-step workflow.
 * Nothing here is a synthetic loop over an in-memory primitive — pollOnce()
 * runs enumeration -> deterministic ordering -> partition/active/lease/
 * recovery filtering -> lease claim -> supervisor dispatch for every due
 * checkpoint.
 *
 * Boundary documentation: workflow step BODIES and lease RENEWAL run
 * asynchronously after dispatch and are intentionally outside the timed
 * region (deterministic in-process one-step workflows only; the supervisor
 * scope is attached, so dispatch is real). The measured quantity is the poll
 * cycle over the due catalog.
 */
internal object WorkerPollingFixture {
    internal data class Fixture(
        val poller: CheckpointPoller,
        val observer: ClaimRecordingObserver,
        val scope: CoroutineScope,
        val store: InMemoryWorkflowCheckpointStore,
        val leaseStore: InMemoryWorkflowLeaseStore,
        val supervisor: WorkflowExecutionSupervisor,
    ) {
        /**
         * One poll cycle at stable depth: poll the due catalog, then drain the
         * asynchronously-dispatched executions until the supervisor is idle so
         * the next cycle observes the same pending population (leases released).
         */
        suspend fun pollCycleDrained() {
            poller.pollOnce()
            withTimeout(15_000) {
                while (supervisor.activeExecutionCount() > 0) {
                    delay(1)
                }
            }
        }
    }

    internal class ClaimRecordingObserver : TramaiWorkerObserver {
        val leaseAcquired = CopyOnWriteArrayList<String>()

        override fun onLeaseAcquired(
            workflowId: String,
            workerId: String,
        ) {
            leaseAcquired += workflowId
        }
    }

    private data class TickState(
        val value: String,
    )

    private object TickCodec : WorkflowStateCodec<TickState> {
        override fun encode(state: TickState): String = state.value

        override fun decode(payload: String): TickState = TickState(payload)
    }

    /** One poller + supervisor over [dueCount] due checkpoints (0 = empty queue). */
    internal fun build(
        dueCount: Int,
        workflowName: String = "bench-wf",
    ): Fixture {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val observer = ClaimRecordingObserver()
        val config =
            WorkerConfig(
                workerId = "bench-worker",
                poolName = "bench-pool",
                pollIntervalMillis = 10,
                leaseDurationMillis = 60_000,
                drainTimeoutMillis = 1_000,
            )
        seedCheckpoints(store, workflowName, dueCount)
        val coordinator = LeaseCoordinator(config, leaseStore, observer)
        val definition =
            workflow<TickState>(workflowName, definitionVersion = "v1") {
                localStep("tick") { state, _ -> state }
            }.build { it.value }
        val binding =
            WorkflowBindingRegistry {
                bind(definition, WorkflowPersistence(checkpointStore = store, stateCodec = TickCodec))
            }
        val supervisor =
            WorkflowExecutionSupervisor(
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        supervisor.attachScope(scope)
        val poller =
            CheckpointPoller(
                config = config,
                checkpointCatalog = store,
                workerRegistryStore = leaseStore,
                partitionStrategy = ModHashPartitionStrategy(),
                leaseCoordinator = coordinator,
                executionSupervisor = supervisor,
                observability = observer,
                acceptingWork = { true },
            )
        return Fixture(poller, observer, scope, store, leaseStore, supervisor)
    }

    private fun seedCheckpoints(
        store: InMemoryWorkflowCheckpointStore,
        workflowName: String,
        dueCount: Int,
    ) {
        repeat(dueCount) { index ->
            val checkpoint =
                WorkflowCheckpoint(
                    workflowName = workflowName,
                    workflowId = "w-$index",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = "start",
                    metadata = mapOf(WORKFLOW_DEFINITION_VERSION_METADATA_KEY to "v1"),
                )
            runBlocking { store.save(checkpoint) }
        }
    }
}
