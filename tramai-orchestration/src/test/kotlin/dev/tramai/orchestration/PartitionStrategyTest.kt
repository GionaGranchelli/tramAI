package dev.tramai.orchestration

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.Test

class PartitionStrategyTest {
    @Test
    fun `empty active workers returns false`() { runBlocking {
        val strategy = ModHashPartitionStrategy()
        assertThat(strategy.ownsPartition("workflow-1", "worker-1", emptyList())).isFalse
    }
    }

    @Test
    fun `single worker owns all partitions`() { runBlocking {
        val strategy = ModHashPartitionStrategy()
        assertThat(strategy.ownsPartition("workflow-1", "worker-1", listOf("worker-1"))).isTrue
        assertThat(strategy.ownsPartition("workflow-999", "worker-1", listOf("worker-1"))).isTrue
    }
    }

    @Test
    fun `same worker always gets same partition for same workflow`() { runBlocking {
        val strategy = ModHashPartitionStrategy()
        val workers = listOf("worker-0", "worker-1", "worker-2")
        val result1 = strategy.ownsPartition("workflow-42", "worker-0", workers)
        val result2 = strategy.ownsPartition("workflow-42", "worker-0", workers)
        assertThat(result1).isEqualTo(result2)
    }
    }

    @Test
    fun `two workers each own different workflows`() { runBlocking {
        val strategy = ModHashPartitionStrategy()
        val workers = listOf("worker-0", "worker-1")
        val wf0 = runIdForPartition(0, 2)
        val wf1 = runIdForPartition(1, 2)
        assertThat(strategy.ownsPartition(wf0, "worker-0", workers)).isTrue
        assertThat(strategy.ownsPartition(wf0, "worker-1", workers)).isFalse
        assertThat(strategy.ownsPartition(wf1, "worker-1", workers)).isTrue
    }
    }

    @Test
    fun `worker not in active workers list returns false`() { runBlocking {
        val strategy = ModHashPartitionStrategy()
        val workers = listOf("worker-0", "worker-1")
        assertThat(strategy.ownsPartition("workflow-1", "worker-2", workers)).isFalse
    }
    }

    @Test
    fun `custom strategy can be injected into tramai worker`() { runBlocking {
        val alwaysTrue = PartitionAssignmentStrategy { _, _, _ -> true }
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = makeWorkflow("custom-strategy") {
            localStep(
                name = "process",
                transform = { state, _ -> PartitionStrategyState(state.value + ":done") },
            )
        }
        val runId = "run-custom-strategy"
        makeSeed(checkpointStore, workflow, runId, PartitionStrategyState("start"))

        val worker = TramaiWorker(
            config = WorkerConfig(
                workerId = "worker-always",
                poolName = "tests",
                pollIntervalMillis = 20,
                leaseDurationMillis = 200,
                drainTimeoutMillis = 1_000,
                partitionEnabled = true,
                workerCount = 3,
            ),
            leaseStore = leaseStore,
            checkpointStore = checkpointStore,
            workflowBindings = WorkflowBindingRegistry {
                bind(
                    workflow = workflow,
                    persistence = WorkflowPersistence(
                        checkpointStore = checkpointStore,
                        stateCodec = PartitionStrategyStateCodec,
                    ),
                )
            },
            observability = NoOpTramaiWorkerObserver,
            partitionStrategy = alwaysTrue,
        )
        worker.start()
        try {
            waitUntil { checkpointStore.load(workflow.name, runId) == null }
            val attempt = checkpointStore.listStepAttempts(runId).single()
            assertThat(attempt.status).isEqualTo(StepAttemptStatus.COMPLETED)
            assertThat(attempt.workerId).isEqualTo("worker-always")
        } finally {
            worker.shutdown()
        }
    }
    }

    private suspend fun waitUntil(block: suspend () -> Boolean) {
        withTimeout(5_000) {
            while (!block()) {
                delay(10)
            }
        }
    }

    private fun makeWorkflow(
        name: String,
        configure: WorkflowBuilder<PartitionStrategyState>.() -> Unit,
    ): Workflow<PartitionStrategyState, String> = workflow<PartitionStrategyState>(name, configure = configure)
        .build { it.value }
        

    private suspend fun makeSeed(
        checkpointStore: WorkflowCheckpointStore,
        workflow: Workflow<PartitionStrategyState, String>,
        workflowId: String,
        state: PartitionStrategyState,
    ) {
        checkpointStore.save(
            checkpoint = WorkflowCheckpoint(
                workflowName = workflow.name,
                workflowId = workflowId,
                nextStepIndex = 0,
                stepExecutions = 0,
                lastCompletedStepName = null,
                statePayload = PartitionStrategyStateCodec.encode(state),
                metadata = workflow.checkpointMetadata(),
            ),
        )
    }
}

private data class PartitionStrategyState(
    val value: String,
)

private object PartitionStrategyStateCodec : WorkflowStateCodec<PartitionStrategyState> {
    override fun encode(state: PartitionStrategyState): String = state.value
    override fun decode(payload: String): PartitionStrategyState = PartitionStrategyState(payload)
}

private fun stablePartition(
    workflowId: String,
    workerCount: Int,
): Int {
    val digest = MessageDigest.getInstance("SHA-256").digest(workflowId.toByteArray(Charsets.UTF_8))
    val hash = ByteBuffer.wrap(digest.copyOfRange(0, Long.SIZE_BYTES)).long and Long.MAX_VALUE
    return (hash % workerCount.toLong()).toInt()
}

private fun runIdForPartition(
    partition: Int,
    workerCount: Int,
): String {
    var index = 0
    while (true) {
        val candidate = "run-$partition-$index"
        if (stablePartition(candidate, workerCount) == partition) {
            return candidate
        }
        index += 1
    }
}
