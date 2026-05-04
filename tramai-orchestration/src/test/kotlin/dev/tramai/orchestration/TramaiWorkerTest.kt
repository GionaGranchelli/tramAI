package dev.tramai.orchestration

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

class TramaiWorkerTest {
    @Test
    fun `two workers claim different workflows concurrently`() = runBlocking {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workerWorkflow("distributed-claims") {
            localStep(
                name = "work",
                transform = { state, _ ->
                    delay(200)
                    state.copy(value = "${state.value}:done")
                },
            )
        }
        val worker0Run = runIdForPartition(0, 2)
        val worker1Run = runIdForPartition(1, 2)
        seedCheckpoint(checkpointStore, workflow, worker0Run, WorkerState("a"))
        seedCheckpoint(checkpointStore, workflow, worker1Run, WorkerState("b"))

        val worker0 = worker("worker-0", leaseStore, checkpointStore, workflow, workerCount = 2, partitionEnabled = true)
        val worker1 = worker("worker-1", leaseStore, checkpointStore, workflow, workerCount = 2, partitionEnabled = true)
        worker0.start()
        worker1.start()
        try {
            waitUntil {
                checkpointStore.load(workflow.name, worker0Run) == null &&
                    checkpointStore.load(workflow.name, worker1Run) == null
            }

            val worker0Attempt = checkpointStore.listStepAttempts(worker0Run).single()
            val worker1Attempt = checkpointStore.listStepAttempts(worker1Run).single()
            assertThat(worker0Attempt.status).isEqualTo(StepAttemptStatus.COMPLETED)
            assertThat(worker1Attempt.status).isEqualTo(StepAttemptStatus.COMPLETED)
            assertThat(worker0Attempt.workerId).isEqualTo("worker-0")
            assertThat(worker1Attempt.workerId).isEqualTo("worker-1")
        } finally {
            worker0.shutdown()
            worker1.shutdown()
        }
    }

    @Test
    fun `worker crash leaves non replayable step in unknown state and takeover fails`() = runBlocking {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        var now = 1_000L
        val leaseStore = InMemoryWorkflowLeaseStore(clockMillis = { now })
        val workflow = workflow<WorkerState>("non-replayable") {
            shellStep(
                name = "deploy",
                command = { _, _ -> ShellCommand(command = listOf("sh", "-c", "sleep 2")) },
                merge = { state, _, _ -> state.copy(value = "${state.value}:deployed") },
            )
        }.build { it.value }.registerWorkerBinding(WorkerStateCodec)
        val runId = "run-non-replayable"
        seedCheckpoint(checkpointStore, workflow, runId, WorkerState("start"))

        val workerA = worker("worker-a", leaseStore, checkpointStore, workflow, leaseDurationMillis = 100, pollIntervalMillis = 20)
        workerA.start()
        waitUntil {
            checkpointStore.latestStepAttempt(runId, "deploy")?.status == StepAttemptStatus.STARTED
        }
        workerA.crash()

        now += 250
        val workerB = worker("worker-b", leaseStore, checkpointStore, workflow, leaseDurationMillis = 100, pollIntervalMillis = 20)
        workerB.start()
        try {
            waitUntil {
                workerB.latestFailure(runId) is NonReplayableStepStateUnknownException
            }

            val latestAttempt = checkpointStore.latestStepAttempt(runId, "deploy")!!
            val failure = workerB.latestFailure(runId)
            assertThat(latestAttempt.status).isEqualTo(StepAttemptStatus.UNKNOWN)
            assertThat(failure).isInstanceOf(NonReplayableStepStateUnknownException::class.java)
            assertThat(checkpointStore.load(workflow.name, runId)).isNotNull()
        } finally {
            workerB.shutdown()
        }
    }

    @Test
    fun `worker takeover re executes pure step after crash`() = runBlocking {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        var now = 2_000L
        val leaseStore = InMemoryWorkflowLeaseStore(clockMillis = { now })
        val executions = AtomicInteger()
        val workflow = workflow<WorkerState>("pure-retry") {
            localStep(
                name = "compute",
                transform = { state, _ ->
                    executions.incrementAndGet()
                    delay(200)
                    state.copy(value = "${state.value}:computed")
                },
            )
        }.build { it.value }.registerWorkerBinding(WorkerStateCodec)
        val runId = "run-pure"
        seedCheckpoint(checkpointStore, workflow, runId, WorkerState("start"))

        val workerA = worker("worker-a", leaseStore, checkpointStore, workflow, leaseDurationMillis = 100, pollIntervalMillis = 20)
        workerA.start()
        waitUntil {
            executions.get() == 1 && checkpointStore.latestStepAttempt(runId, "compute")?.status == StepAttemptStatus.STARTED
        }
        workerA.crash()

        now += 250
        val workerB = worker("worker-b", leaseStore, checkpointStore, workflow, leaseDurationMillis = 100, pollIntervalMillis = 20)
        workerB.start()
        try {
            waitUntil {
                executions.get() == 2 && checkpointStore.load(workflow.name, runId) == null
            }

            val attempts = checkpointStore.listStepAttempts(runId)
            assertThat(attempts).hasSize(2)
            assertThat(attempts.map { it.status }).containsExactly(StepAttemptStatus.UNKNOWN, StepAttemptStatus.COMPLETED)
            assertThat(attempts.last().workerId).isEqualTo("worker-b")
        } finally {
            workerB.shutdown()
        }
    }

    @Test
    fun `graceful shutdown drains in progress work and releases leases`() = runBlocking {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workflow<WorkerState>("graceful-drain") {
            localStep(
                name = "slow",
                transform = { state, _ ->
                    delay(200)
                    state.copy(value = "${state.value}:done")
                },
            )
        }.build { it.value }.registerWorkerBinding(WorkerStateCodec)
        val runId = "run-drain"
        seedCheckpoint(checkpointStore, workflow, runId, WorkerState("start"))

        val worker = worker("worker-0", leaseStore, checkpointStore, workflow, drainTimeoutMillis = 1_000, pollIntervalMillis = 20)
        worker.start()
        waitUntil {
            checkpointStore.latestStepAttempt(runId, "slow")?.status == StepAttemptStatus.STARTED
        }

        worker.shutdown()

        assertThat(checkpointStore.load(workflow.name, runId)).isNull()
        assertThat(leaseStore.currentLease(workflow.name, runId)).isNull()
        assertThat(leaseStore.listActiveWorkers()).isEmpty()
        assertThat(checkpointStore.latestStepAttempt(runId, "slow")?.status).isEqualTo(StepAttemptStatus.COMPLETED)
    }

    @Test
    fun `partition pinning distributes workflows by stable hash`() = runBlocking {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workerWorkflow("partitioned-work") {
            localStep(
                name = "process",
                transform = { state, _ -> state.copy(value = "${state.value}:done") },
            )
        }
        val runIds = (0 until 12).map { index -> "partition-run-$index" }
        runIds.forEach { runId ->
            seedCheckpoint(checkpointStore, workflow, runId, WorkerState(runId))
        }

        val worker0 = worker("worker-0", leaseStore, checkpointStore, workflow, workerCount = 2, partitionEnabled = true, pollIntervalMillis = 20)
        val worker1 = worker("worker-1", leaseStore, checkpointStore, workflow, workerCount = 2, partitionEnabled = true, pollIntervalMillis = 20)
        worker0.start()
        worker1.start()
        try {
            waitUntil {
                runIds.all { checkpointStore.load(workflow.name, it) == null }
            }

            val attemptsByRun = runIds.associateWith { runId -> checkpointStore.listStepAttempts(runId).single() }
            val worker0Runs = attemptsByRun.filterValues { it.workerId == "worker-0" }.keys
            val worker1Runs = attemptsByRun.filterValues { it.workerId == "worker-1" }.keys
            assertThat(worker0Runs).isNotEmpty()
            assertThat(worker1Runs).isNotEmpty()
            attemptsByRun.forEach { (runId, attempt) ->
                val expectedWorker = "worker-${stablePartition(runId, 2)}"
                assertThat(attempt.workerId).isEqualTo(expectedWorker)
            }
        } finally {
            worker0.shutdown()
            worker1.shutdown()
        }
    }

    @Test
    fun `worker heartbeats are visible and stale workers are detectable`() = runBlocking {
        var now = 5_000L
        val leaseStore = InMemoryWorkflowLeaseStore(clockMillis = { now })
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val workflow = workerWorkflow("heartbeat") {
            localStep(
                name = "noop",
                transform = { state, _ -> state },
            )
        }
        val worker = worker("worker-0", leaseStore, checkpointStore, workflow, pollIntervalMillis = 1_000)
        worker.start()
        try {
            waitUntil {
                leaseStore.listActiveWorkers().singleOrNull()?.workerId == "worker-0"
            }
            now += 2_000
            assertThat(leaseStore.listStaleWorkers(1_000).map { it.workerId })
                .containsExactly("worker-0")
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun `step attempt records are persisted and inspectable`() = runBlocking {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workerWorkflow("attempt-records") {
            localStep(
                name = "transform",
                transform = { state, _ -> state.copy(value = state.value.uppercase()) },
            )
        }
        val runId = "run-attempts"
        seedCheckpoint(checkpointStore, workflow, runId, WorkerState("hello"))

        val worker = worker("worker-0", leaseStore, checkpointStore, workflow, pollIntervalMillis = 20)
        worker.start()
        try {
            waitUntil {
                checkpointStore.load(workflow.name, runId) == null
            }

            val attempt = checkpointStore.listStepAttempts(runId).single()
            assertThat(attempt.status).isEqualTo(StepAttemptStatus.COMPLETED)
            assertThat(attempt.replayPolicy).isEqualTo(ReplayPolicy.PURE)
            assertThat(attempt.inputFingerprint).isNotBlank()
            assertThat(attempt.leaseToken).isNotBlank()
            assertThat(attempt.completedAt).isNotNull()
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun `non replayable step exception includes full context`() {
        val exception = NonReplayableStepStateUnknownException(
            runId = "run-123",
            stepName = "deploy",
            priorWorkerId = "worker-a",
            attemptTime = 42L,
        )

        assertThat(exception.runId).isEqualTo("run-123")
        assertThat(exception.stepName).isEqualTo("deploy")
        assertThat(exception.priorWorkerId).isEqualTo("worker-a")
        assertThat(exception.attemptTime).isEqualTo(42L)
        assertThat(exception.recoveryInstructions).contains("Inspect the external side effect")
        assertThat(exception.message)
            .contains("run-123")
            .contains("deploy")
            .contains("worker-a")
            .contains("1970-01-01T00:00:00.042Z")
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
        workflowRegistry = mapOf(workflow.name to workflow),
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
        withTimeout(5_000) {
            while (!block()) {
                delay(10)
            }
        }
    }
}

private data class WorkerState(
    val value: String,
)

private object WorkerStateCodec : WorkflowStateCodec<WorkerState> {
    override fun encode(state: WorkerState): String = state.value

    override fun decode(payload: String): WorkerState = WorkerState(payload)
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
