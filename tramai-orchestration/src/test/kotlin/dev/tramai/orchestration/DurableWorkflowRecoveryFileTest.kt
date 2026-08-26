package dev.tramai.orchestration

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

abstract class DurableWorkflowRecoveryContractTest {
    protected lateinit var persistence: DurableRecoveryPersistence

    protected abstract fun createPersistence(): DurableRecoveryPersistence

    @BeforeEach
    fun setUpPersistence() {
        persistence = createPersistence()
    }

    @AfterEach
    fun closePersistence() {
        persistence.close()
    }

    @Test
    fun `retry approval survives restart and worker consumes it once`() {
        runBlocking {
            val executions = AtomicInteger()
            val workflow = recoveryWorkflow("restart-approval", executions, ReplayPolicy.NON_REPLAYABLE)
            val runId = "run-approval"
            seedRequired(workflow, runId, WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN)

            persistence.recreate()
            val required = persistence.checkpointStore.load(workflow.name, runId)!!
            InMemoryWorkflowRecoveryController(persistence.checkpointStore, persistence.attemptStore).retryStep(
                workflow.name,
                runId,
                required.revision,
                "safe after inspection",
            )

            persistence.recreate()
            val worker = worker(workflow, persistence)
            worker.start()
            try {
                waitUntil { persistence.checkpointStore.load(workflow.name, runId) == null }
            } finally {
                worker.shutdown()
            }
            val attempts = persistence.attemptStore.listStepAttempts(runId)
            assertThat(executions.get()).isEqualTo(1)
            assertThat(attempts.map { it.status }).containsExactly(StepAttemptStatus.FAILED, StepAttemptStatus.COMPLETED)
            assertThat(attempts.first().resolutionAction).isEqualTo(StepAttemptResolutionAction.RETRY_APPROVED)
        }
    }

    @Test
    fun `approved idempotency key survives restart and mismatch is voided`() {
        runBlocking {
            val matchingExecutions = AtomicInteger()
            val matchingWorkflow = recoveryWorkflow(
                "matching-key",
                matchingExecutions,
                ReplayPolicy.EXTERNALLY_IDEMPOTENT,
                currentKey = "approved-key",
            )
            seedRequired(matchingWorkflow, "run-matching", WorkflowRecoveryReason.IDEMPOTENCY_KEY_MISMATCH)
            approveKey(matchingWorkflow, "run-matching", "approved-key")
            persistence.recreate()
            val matchingWorker = worker(matchingWorkflow, persistence)
            matchingWorker.start()
            try {
                waitUntil { persistence.checkpointStore.load(matchingWorkflow.name, "run-matching") == null }
            } finally {
                matchingWorker.shutdown()
            }
            assertThat(matchingExecutions.get()).isEqualTo(1)

            val mismatchingExecutions = AtomicInteger()
            val mismatchingWorkflow = recoveryWorkflow(
                "mismatching-key",
                mismatchingExecutions,
                ReplayPolicy.EXTERNALLY_IDEMPOTENT,
                currentKey = "current-key",
            )
            seedRequired(mismatchingWorkflow, "run-mismatching", WorkflowRecoveryReason.IDEMPOTENCY_KEY_MISMATCH)
            approveKey(mismatchingWorkflow, "run-mismatching", "approved-key")
            persistence.recreate()
            val mismatchingWorker = worker(mismatchingWorkflow, persistence)
            mismatchingWorker.start()
            try {
                waitUntil {
                    persistence.checkpointStore.load(mismatchingWorkflow.name, "run-mismatching")
                        ?.recoveryState is WorkflowRecoveryState.Required
                }
            } finally {
                mismatchingWorker.shutdown()
            }
            val attempt = persistence.attemptStore.listStepAttempts("run-mismatching").single()
            assertThat(mismatchingExecutions.get()).isZero()
            assertThat(attempt.resolutionAction).isNull()
            assertThat(attempt.approvedIdempotencyKey).isNull()
        }
    }

    @Test
    fun `partial approval transition is safely repeatable after restart`() {
        runBlocking {
            val workflow = recoveryWorkflow("partial-transition", AtomicInteger(), ReplayPolicy.NON_REPLAYABLE)
            val runId = "run-partial"
            seedRequired(workflow, runId, WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN)
            val required = persistence.checkpointStore.load(workflow.name, runId)!!
            val failingController = InMemoryWorkflowRecoveryController(
                FailingClearCheckpointStore(persistence.checkpointStore),
                persistence.attemptStore,
            )
            assertThatThrownBy {
                runBlocking {
                    failingController.retryStep(workflow.name, runId, required.revision, "same approval")
                }
            }.isInstanceOf(IllegalStateException::class.java).hasMessage("clear failed")

            persistence.recreate()
            val stillRequired = persistence.checkpointStore.load(workflow.name, runId)!!
            val approved = persistence.attemptStore.listStepAttempts(runId).single()
            assertThat(stillRequired.recoveryState).isInstanceOf(WorkflowRecoveryState.Required::class.java)
            assertThat(approved.resolutionAction).isEqualTo(StepAttemptResolutionAction.RETRY_APPROVED)

            val controller = InMemoryWorkflowRecoveryController(persistence.checkpointStore, persistence.attemptStore)
            assertThatThrownBy {
                runBlocking {
                    controller.retryStep(workflow.name, runId, stillRequired.revision, "conflicting approval")
                }
            }.isInstanceOf(WorkflowRecoveryStateException::class.java)
            val cleared = controller.retryStep(workflow.name, runId, stillRequired.revision, "same approval")
            assertThat(cleared.recoveryState).isSameAs(WorkflowRecoveryState.Normal)
        }
    }

    @Test
    fun `failed workflow evidence remains queryable after restart`() {
        runBlocking {
            val workflow = recoveryWorkflow("failed-evidence", AtomicInteger(), ReplayPolicy.NON_REPLAYABLE)
            val runId = "run-failed"
            seedRequired(workflow, runId, WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN)
            val required = persistence.checkpointStore.load(workflow.name, runId)!!
            InMemoryWorkflowRecoveryController(persistence.checkpointStore, persistence.attemptStore).failWorkflow(
                workflow.name,
                runId,
                required.revision,
                "operator failed workflow",
            )

            persistence.recreate()
            assertThat(persistence.checkpointStore.load(workflow.name, runId)).isNull()
            val attempt = persistence.attemptStore.listStepAttempts(runId).single()
            assertThat(attempt.status).isEqualTo(StepAttemptStatus.FAILED)
            assertThat(attempt.resolutionAction).isEqualTo(StepAttemptResolutionAction.WORKFLOW_FAILED)
            assertThat(attempt.resolutionReason).isEqualTo("operator failed workflow")
        }
    }

    private suspend fun approveKey(workflow: Workflow<RecoveryWorkerState, String>, runId: String, key: String) {
        val required = persistence.checkpointStore.load(workflow.name, runId)!!
        InMemoryWorkflowRecoveryController(persistence.checkpointStore, persistence.attemptStore).retryStep(
            workflow.name,
            runId,
            required.revision,
            "operator approved retry",
            key,
        )
    }

    private suspend fun seedRequired(
        workflow: Workflow<RecoveryWorkerState, String>,
        runId: String,
        reason: WorkflowRecoveryReason,
    ) {
        persistence.checkpointStore.save(
            WorkflowCheckpoint(
                workflowName = workflow.name,
                workflowId = runId,
                nextStepIndex = 0,
                stepExecutions = 0,
                lastCompletedStepName = null,
                statePayload = RecoveryWorkerStateCodec.encode(RecoveryWorkerState("start")),
                metadata = workflow.checkpointMetadata(),
                recoveryState = WorkflowRecoveryState.Required(
                    WorkflowRecoveryRecord(
                        reason = reason,
                        stepName = "plan",
                        attemptId = "attempt-1",
                        priorWorkerId = "worker-a",
                        detectedAtEpochMillis = 20,
                    ),
                ),
            ),
        )
        persistence.attemptStore.recordStepAttempt(
            StepAttemptRecord(
                runId = runId,
                stepName = "plan",
                attemptId = "attempt-1",
                workerId = "worker-a",
                leaseToken = "lease-a",
                status = StepAttemptStatus.UNKNOWN,
                startedAt = 10,
                replayPolicy = if (reason == WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN) {
                    ReplayPolicy.NON_REPLAYABLE
                } else {
                    ReplayPolicy.EXTERNALLY_IDEMPOTENT
                },
            ),
        )
    }

    private fun recoveryWorkflow(
        name: String,
        executions: AtomicInteger,
        replayPolicy: ReplayPolicy,
        currentKey: String? = null,
    ): Workflow<RecoveryWorkerState, String> {
        return workflow<RecoveryWorkerState>(name) {
            if (currentKey == null) {
                aiStep(
                    name = "plan",
                    replayPolicy = replayPolicy,
                    input = { it.value },
                    invoke = { executions.incrementAndGet(); "$it:planned" },
                    merge = { state, result -> state.copy(value = result) },
                )
            } else {
                aiStep(
                    name = "plan",
                    replayPolicy = replayPolicy,
                    idempotencyKey = { _, _ -> currentKey },
                    input = { it.value },
                    invoke = { executions.incrementAndGet(); "$it:planned" },
                    merge = { state, result -> state.copy(value = result) },
                )
            }
        }.build { it.value }
    }

    private fun worker(
        workflow: Workflow<RecoveryWorkerState, String>,
        persistence: DurableRecoveryPersistence,
    ): TramaiWorker = TramaiWorker(
        config = WorkerConfig(
            workerId = "worker-b",
            poolName = "durable-recovery-tests",
            pollIntervalMillis = 20,
            leaseDurationMillis = 200,
            drainTimeoutMillis = 1_000,
        ),
        leaseStore = InMemoryWorkflowLeaseStore(),
        checkpointStore = persistence.checkpointStore,
        checkpointCatalog = persistence.checkpointStore as WorkflowCheckpointCatalog,
        stepAttemptStore = persistence.attemptStore,
        workflowBindings = WorkflowBindingRegistry {
            bind(
                workflow = workflow,
                persistence = WorkflowPersistence(
                    checkpointStore = persistence.checkpointStore,
                    stateCodec = RecoveryWorkerStateCodec,
                ),
            )
        },
    )

    private suspend fun waitUntil(block: suspend () -> Boolean) {
        withTimeout(20_000) {
            while (!block()) delay(10)
        }
    }
}

class DurableWorkflowRecoveryFileTest : DurableWorkflowRecoveryContractTest() {
    @TempDir
    lateinit var root: Path

    override fun createPersistence(): DurableRecoveryPersistence = FileDurableRecoveryPersistence(root)
}

interface DurableRecoveryPersistence {
    val checkpointStore: WorkflowCheckpointStore
    val attemptStore: StepAttemptRecordStore
    fun recreate()
    fun close() = Unit
}

private class FileDurableRecoveryPersistence(root: Path) : DurableRecoveryPersistence {
    private val checkpointRoot = root.resolve("checkpoints")
    private val attemptRoot = root.resolve("attempts")
    override var checkpointStore: WorkflowCheckpointStore = FileWorkflowCheckpointStore(checkpointRoot)
    override var attemptStore: StepAttemptRecordStore = FileStepAttemptRecordStore(attemptRoot)

    override fun recreate() {
        checkpointStore = FileWorkflowCheckpointStore(checkpointRoot)
        attemptStore = FileStepAttemptRecordStore(attemptRoot)
    }
}

private class FailingClearCheckpointStore(
    delegate: WorkflowCheckpointStore,
) : WorkflowCheckpointStore by delegate {
    override suspend fun clearRecovery(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        expectedGeneration: String?,
    ): WorkflowCheckpoint = throw IllegalStateException("clear failed")
}

data class RecoveryWorkerState(val value: String)

object RecoveryWorkerStateCodec : WorkflowStateCodec<RecoveryWorkerState> {
    override fun encode(state: RecoveryWorkerState): String = state.value
    override fun decode(payload: String): RecoveryWorkerState = RecoveryWorkerState(payload)
}
