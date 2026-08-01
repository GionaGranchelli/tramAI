package dev.tramai.orchestration

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

class WorkflowRecoveryContractTest {

    @Test
    fun `Worker skips RECOVERY_REQUIRED checkpoint`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val leaseStore = InMemoryWorkflowLeaseStore()
            val config = WorkerConfig(
                workerId = "test-worker",
                poolName = "test-pool",
                pollIntervalMillis = 50,
                leaseDurationMillis = 30_000,
            )
            val record = WorkflowRecoveryRecord(
                reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                stepName = "step-1",
                attemptId = "attempt-1",
                priorWorkerId = "prior-worker",
                detectedAtEpochMillis = 1000L,
            )
            val checkpoint = WorkflowCheckpoint(
                workflowName = "recovery-test",
                workflowId = "wf-recovery-1",
                nextStepIndex = 1,
                stepExecutions = 1,
                lastCompletedStepName = "step-0",
                statePayload = "test-state",
                revision = 0,
                recoveryState = WorkflowRecoveryState.Required(record),
            )
            store.save(checkpoint, expectedRevision = null)
            val worker = TramaiWorker(
                config = config,
                leaseStore = leaseStore,
                checkpointStore = store,
                workflowRegistry = emptyMap(),
            )
            worker.start()
            delay(300)
            assertThat(leaseStore.currentLease("recovery-test", "wf-recovery-1")).isNull()
            worker.shutdown()
        }
    }

    @Test
    fun `requireRecovery persists NON_REPLAYABLE recovery reason`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val saved = store.save(sampleCheckpoint())
            val recovered = store.requireRecovery(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = saved.revision,
                record = WorkflowRecoveryRecord(
                    reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                    stepName = "process-payment",
                    attemptId = "attempt-1",
                    priorWorkerId = "worker-a",
                    detectedAtEpochMillis = 1000,
                ),
            )
            assertThat(recovered.revision).isEqualTo(saved.revision + 1)
            assertThat(recovered.recoveryState).isInstanceOf(WorkflowRecoveryState.Required::class.java)
            val required = recovered.recoveryState as WorkflowRecoveryState.Required
            assertThat(required.record.reason).isEqualTo(WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN)
            assertThat(required.record.stepName).isEqualTo("process-payment")
            assertThat(required.record.attemptId).isEqualTo("attempt-1")
            assertThat(required.record.priorWorkerId).isEqualTo("worker-a")
            assertThat(required.record.detectedAtEpochMillis).isEqualTo(1000)
        }
    }

    @Test
    fun `requireRecovery persists EXTERNAL_IDEMPOTENCY_KEY_MISSING reason`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val saved = store.save(sampleCheckpoint())
            val recovered = store.requireRecovery(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = saved.revision,
                record = WorkflowRecoveryRecord(
                    reason = WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING,
                    stepName = "send-email",
                    attemptId = "attempt-2",
                    priorWorkerId = "worker-b",
                    detectedAtEpochMillis = 2000,
                ),
            )
            assertThat(recovered.revision).isEqualTo(saved.revision + 1)
            val required = recovered.recoveryState as WorkflowRecoveryState.Required
            assertThat(required.record.reason).isEqualTo(WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING)
        }
    }

    @Test
    fun `requireRecovery with wrong revision throws conflict`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val saved = store.save(sampleCheckpoint())
            assertThatThrownBy {
                runBlocking {
                    store.requireRecovery(
                        workflowName = saved.workflowName,
                        workflowId = saved.workflowId,
                        expectedRevision = 42,
                        record = WorkflowRecoveryRecord(
                            reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                            stepName = "step",
                            attemptId = "a",
                            priorWorkerId = "w",
                            detectedAtEpochMillis = 0,
                        ),
                    )
                }
            }.isInstanceOf(WorkflowCheckpointConflictException::class.java)
        }
    }

    @Test
    fun `retryStep clears recovery state`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val controller = InMemoryWorkflowRecoveryController(store, store)
            val saved = store.save(sampleCheckpoint())
            store.recordStepAttempt(
                StepAttemptRecord(
                    runId = saved.workflowId,
                    stepName = "step",
                    attemptId = "a",
                    workerId = "w",
                    leaseToken = "l",
                    status = StepAttemptStatus.UNKNOWN,
                    startedAt = 0,
                    replayPolicy = ReplayPolicy.NON_REPLAYABLE,
                ),
            )
            val required = store.requireRecovery(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = saved.revision,
                record = WorkflowRecoveryRecord(
                    reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                    stepName = "step",
                    attemptId = "a",
                    priorWorkerId = "w",
                    detectedAtEpochMillis = 0,
                ),
            )
            val retried = controller.retryStep(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = required.revision,
                reason = "operator confirmed side effect did not complete",
            )
            assertThat(retried.recoveryState).isSameAs(WorkflowRecoveryState.Normal)
            assertThat(retried.revision).isEqualTo(required.revision + 1)
        }
    }

    @Test
    fun `failWorkflow deletes checkpoint`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val controller = InMemoryWorkflowRecoveryController(store)
            val saved = store.save(sampleCheckpoint())
            val required = store.requireRecovery(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = saved.revision,
                record = WorkflowRecoveryRecord(
                    reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                    stepName = "step",
                    attemptId = "a",
                    priorWorkerId = "w",
                    detectedAtEpochMillis = 0,
                ),
            )
            controller.failWorkflow(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = required.revision,
                reason = "irrecoverable error",
            )
            assertThat(store.load(saved.workflowName, saved.workflowId)).isNull()
        }
    }

    @Test
    fun `retryStep and failWorkflow with wrong revision throw conflict`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val controller = InMemoryWorkflowRecoveryController(store)
            val saved = store.save(sampleCheckpoint())
            val required = store.requireRecovery(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = saved.revision,
                record = WorkflowRecoveryRecord(
                    reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                    stepName = "step",
                    attemptId = "a",
                    priorWorkerId = "w",
                    detectedAtEpochMillis = 0,
                ),
            )
            assertThat(required.revision).isEqualTo(saved.revision + 1)
            assertThatThrownBy {
                runBlocking {
                    controller.retryStep(
                        workflowName = saved.workflowName,
                        workflowId = saved.workflowId,
                        expectedRevision = 42,
                        reason = "wrong revision",
                    )
                }
            }.isInstanceOf(WorkflowCheckpointConflictException::class.java)
            assertThatThrownBy {
                runBlocking {
                    controller.failWorkflow(
                        workflowName = saved.workflowName,
                        workflowId = saved.workflowId,
                        expectedRevision = 99,
                        reason = "wrong revision",
                    )
                }
            }.isInstanceOf(WorkflowCheckpointConflictException::class.java)
            val after = store.load(saved.workflowName, saved.workflowId)
            assertThat(after).isNotNull
            assertThat(after!!.revision).isEqualTo(saved.revision + 1)
            assertThat(after.recoveryState).isInstanceOf(WorkflowRecoveryState.Required::class.java)
        }
    }

    @Test
    fun `retryStep rejects checkpoint that is not recovery-required`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val controller = InMemoryWorkflowRecoveryController(store)
            val saved = store.save(sampleCheckpoint())
            assertThat(saved.recoveryState).isSameAs(WorkflowRecoveryState.Normal)

            assertThatThrownBy {
                runBlocking {
                    controller.retryStep(
                        workflowName = saved.workflowName,
                        workflowId = saved.workflowId,
                        expectedRevision = saved.revision,
                        reason = "should not apply",
                    )
                }
            }.isInstanceOf(WorkflowRecoveryStateException::class.java)

            val after = store.load(saved.workflowName, saved.workflowId)!!
            assertThat(after.revision).isEqualTo(saved.revision)
            assertThat(after.recoveryState).isSameAs(WorkflowRecoveryState.Normal)
        }
    }

    @Test
    fun `failWorkflow rejects checkpoint that is not recovery-required and does not delete it`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val controller = InMemoryWorkflowRecoveryController(store)
            val saved = store.save(sampleCheckpoint())

            assertThatThrownBy {
                runBlocking {
                    controller.failWorkflow(
                        workflowName = saved.workflowName,
                        workflowId = saved.workflowId,
                        expectedRevision = saved.revision,
                        reason = "should not apply",
                    )
                }
            }.isInstanceOf(WorkflowRecoveryStateException::class.java)

            val after = store.load(saved.workflowName, saved.workflowId)
            assertThat(after).isNotNull
            assertThat(after!!.revision).isEqualTo(saved.revision)
            assertThat(after.recoveryState).isSameAs(WorkflowRecoveryState.Normal)
        }
    }

    @Test
    fun `retryStep marks the exact unresolved attempt failed with resolution evidence`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val controller = InMemoryWorkflowRecoveryController(store, store)
            val saved = store.save(sampleCheckpoint())
            store.recordStepAttempt(
                StepAttemptRecord(
                    runId = saved.workflowId,
                    stepName = "step",
                    attemptId = "attempt-1",
                    workerId = "w",
                    leaseToken = "l",
                    status = StepAttemptStatus.UNKNOWN,
                    startedAt = 0,
                    replayPolicy = ReplayPolicy.NON_REPLAYABLE,
                ),
            )
            val required = store.requireRecovery(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = saved.revision,
                record = WorkflowRecoveryRecord(
                    reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                    stepName = "step",
                    attemptId = "attempt-1",
                    priorWorkerId = "w",
                    detectedAtEpochMillis = 0,
                ),
            )
            controller.retryStep(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = required.revision,
                reason = "operator confirmed safe to retry",
            )

            val attempt = store.listStepAttempts(saved.workflowId).single()
            assertThat(attempt.status).isEqualTo(StepAttemptStatus.FAILED)
            assertThat(attempt.resolutionReason).isEqualTo("operator confirmed safe to retry")
            assertThat(attempt.resolutionAtEpochMillis).isNotNull
            val after = store.load(saved.workflowName, saved.workflowId)!!
            assertThat(after.recoveryState).isSameAs(WorkflowRecoveryState.Normal)
        }
    }

    @Test
    fun `retryStep without a step attempt store throws and keeps checkpoint required`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val controller = InMemoryWorkflowRecoveryController(checkpointStore = store, stepAttemptStore = null)
            val saved = store.save(sampleCheckpoint())
            val required = store.requireRecovery(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = saved.revision,
                record = WorkflowRecoveryRecord(
                    reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                    stepName = "step",
                    attemptId = "a",
                    priorWorkerId = "w",
                    detectedAtEpochMillis = 0,
                ),
            )

            assertThatThrownBy {
                runBlocking {
                    controller.retryStep(
                        workflowName = saved.workflowName,
                        workflowId = saved.workflowId,
                        expectedRevision = required.revision,
                        reason = "cannot retry without a store",
                    )
                }
            }.isInstanceOf(WorkflowRecoveryStateException::class.java)

            val after = store.load(saved.workflowName, saved.workflowId)!!
            assertThat(after.recoveryState).isInstanceOf(WorkflowRecoveryState.Required::class.java)
            assertThat(after.revision).isEqualTo(required.revision)
        }
    }

    @Test
    fun `retryStep with missing referenced attempt throws and keeps checkpoint required`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val controller = InMemoryWorkflowRecoveryController(store, store)
            val saved = store.save(sampleCheckpoint())
            // No attempt is recorded — the recovery record references "missing-attempt".
            val required = store.requireRecovery(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = saved.revision,
                record = WorkflowRecoveryRecord(
                    reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                    stepName = "step",
                    attemptId = "missing-attempt",
                    priorWorkerId = "w",
                    detectedAtEpochMillis = 0,
                ),
            )

            assertThatThrownBy {
                runBlocking {
                    controller.retryStep(
                        workflowName = saved.workflowName,
                        workflowId = saved.workflowId,
                        expectedRevision = required.revision,
                        reason = "attempt is gone",
                    )
                }
            }.isInstanceOf(WorkflowRecoveryStateException::class.java)

            val after = store.load(saved.workflowName, saved.workflowId)!!
            assertThat(after.recoveryState).isInstanceOf(WorkflowRecoveryState.Required::class.java)
            assertThat(after.revision).isEqualTo(required.revision)
        }
    }

    @Test
    fun `retryStep propagates attempt update failure and keeps checkpoint required`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val controller = InMemoryWorkflowRecoveryController(
                checkpointStore = store,
                stepAttemptStore = FailingAttemptUpdateStore(store),
            )
            val saved = store.save(sampleCheckpoint())
            store.recordStepAttempt(
                StepAttemptRecord(
                    runId = saved.workflowId,
                    stepName = "step",
                    attemptId = "a",
                    workerId = "w",
                    leaseToken = "l",
                    status = StepAttemptStatus.UNKNOWN,
                    startedAt = 0,
                    replayPolicy = ReplayPolicy.NON_REPLAYABLE,
                ),
            )
            val required = store.requireRecovery(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = saved.revision,
                record = WorkflowRecoveryRecord(
                    reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                    stepName = "step",
                    attemptId = "a",
                    priorWorkerId = "w",
                    detectedAtEpochMillis = 0,
                ),
            )

            assertThatThrownBy {
                runBlocking {
                    controller.retryStep(
                        workflowName = saved.workflowName,
                        workflowId = saved.workflowId,
                        expectedRevision = required.revision,
                        reason = "update will fail",
                    )
                }
            }.isInstanceOf(IllegalStateException::class.java)

            val after = store.load(saved.workflowName, saved.workflowId)!!
            assertThat(after.recoveryState).isInstanceOf(WorkflowRecoveryState.Required::class.java)
            assertThat(after.revision).isEqualTo(required.revision)
            // The unresolved attempt was NOT resolved by the failed retry.
            assertThat(store.listStepAttempts(saved.workflowId).single().status)
                .isEqualTo(StepAttemptStatus.UNKNOWN)
        }
    }

    @Test
    fun `Two concurrent requireRecovery calls — first wins`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val saved = store.save(sampleCheckpoint())
            supervisorScope {
                val deferred1 = async {
                    store.requireRecovery(
                        workflowName = saved.workflowName,
                        workflowId = saved.workflowId,
                        expectedRevision = saved.revision,
                        record = WorkflowRecoveryRecord(
                            reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                            stepName = "step-A",
                            attemptId = "a1",
                            priorWorkerId = "w1",
                            detectedAtEpochMillis = 100,
                        ),
                    )
                }
                val deferred2 = async {
                    store.requireRecovery(
                        workflowName = saved.workflowName,
                        workflowId = saved.workflowId,
                        expectedRevision = saved.revision,
                        record = WorkflowRecoveryRecord(
                            reason = WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING,
                            stepName = "step-B",
                            attemptId = "b1",
                            priorWorkerId = "w2",
                            detectedAtEpochMillis = 200,
                        ),
                    )
                }
                val result1 = runCatching { deferred1.await() }
                val result2 = runCatching { deferred2.await() }
                val successCount = listOf(result1, result2).count { it.isSuccess }
                val conflictCount = listOf(result1, result2).count {
                    it.isFailure && it.exceptionOrNull() is WorkflowCheckpointConflictException
                }
                assertThat(successCount).isEqualTo(1)
                assertThat(conflictCount).isEqualTo(1)
            }
            val finalCp = store.load(saved.workflowName, saved.workflowId)!!
            assertThat(finalCp.recoveryState).isInstanceOf(WorkflowRecoveryState.Required::class.java)
            val required = finalCp.recoveryState as WorkflowRecoveryState.Required
            assertThat(required.record.reason).isIn(
                WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING,
            )
        }
    }

    @Test
    fun `File store round trips recovery state`() {
        runBlocking {
            val directory = createTempDirectory("tramai-file-recovery")
            try {
                val store = FileWorkflowCheckpointStore(directory)
                val record = recoveryRecord()
                val saved = store.save(
                    sampleCheckpoint().copy(recoveryState = WorkflowRecoveryState.Required(record)),
                )
                store.requireRecovery(
                    workflowName = saved.workflowName,
                    workflowId = saved.workflowId,
                    expectedRevision = saved.revision,
                    record = record,
                )
                val reloaded = store.load(saved.workflowName, saved.workflowId)!!
                assertRequiredRecord(reloaded, record)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `Markdown store round trips recovery state`() {
        runBlocking {
            val directory = createTempDirectory("tramai-markdown-recovery")
            try {
                val store = MarkdownWorkflowCheckpointStore(directory)
                val record = recoveryRecord()
                val saved = store.save(
                    sampleCheckpoint().copy(recoveryState = WorkflowRecoveryState.Required(record)),
                )
                store.requireRecovery(
                    workflowName = saved.workflowName,
                    workflowId = saved.workflowId,
                    expectedRevision = saved.revision,
                    record = record,
                )
                val reloaded = store.load(saved.workflowName, saved.workflowId)!!
                assertRequiredRecord(reloaded, record)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `File store treats absent recovery data as Normal`() {
        runBlocking {
            val directory = createTempDirectory("tramai-file-normal")
            try {
                val store = FileWorkflowCheckpointStore(directory)
                store.save(sampleCheckpoint().copy(recoveryState = WorkflowRecoveryState.Normal))
                val reloaded = store.load("recovery-test", "wf-recovery-1")!!
                assertThat(reloaded.recoveryState).isSameAs(WorkflowRecoveryState.Normal)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `Markdown store treats absent recovery data as Normal`() {
        runBlocking {
            val directory = createTempDirectory("tramai-markdown-normal")
            try {
                val store = MarkdownWorkflowCheckpointStore(directory)
                store.save(sampleCheckpoint().copy(recoveryState = WorkflowRecoveryState.Normal))
                val reloaded = store.load("recovery-test", "wf-recovery-1")!!
                assertThat(reloaded.recoveryState).isSameAs(WorkflowRecoveryState.Normal)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `decoding malformed recovery payload fails closed`() {
        assertThatThrownBy { decodeRecoveryState("not-a-valid-properties-format") }
            .isInstanceOf(WorkflowCheckpointCorruptionException::class.java)
        assertThat(decodeRecoveryState(null)).isSameAs(WorkflowRecoveryState.Normal)
    }

    @Test
    fun `jdbc migration sql adds recovery column`() {
        val store = JdbcWorkflowCheckpointStore(NoopDataSource())
        assertThat(store.migrationSql())
            .contains("ALTER TABLE")
            .contains("ADD COLUMN recovery_state TEXT NULL")
    }

    private fun assertRequiredRecord(
        checkpoint: WorkflowCheckpoint,
        record: WorkflowRecoveryRecord,
    ) {
        val required = checkpoint.recoveryState as WorkflowRecoveryState.Required
        assertThat(required.record).isEqualTo(record)
    }

    private fun recoveryRecord(): WorkflowRecoveryRecord = WorkflowRecoveryRecord(
        reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
        stepName = "process-payment",
        attemptId = "attempt-42",
        priorWorkerId = "worker-7",
        detectedAtEpochMillis = 123456,
        idempotencyKey = "idem-key-1",
        instructions = "confirm with operator",
    )

    private fun sampleCheckpoint(): WorkflowCheckpoint = WorkflowCheckpoint(
        workflowName = "recovery-test",
        workflowId = "wf-recovery-1",
        nextStepIndex = 1,
        stepExecutions = 0,
        lastCompletedStepName = null,
        statePayload = "initial",
        metadata = mapOf("tenant" to "test"),
        savedAtEpochMillis = 1000,
    )
}
private class NoopDataSource : DataSource {
    override fun getConnection(): Connection = error("Not used by migrationSql")
    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = error("Not used by migrationSql")
    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) = Unit
    override fun setLoginTimeout(seconds: Int) = Unit
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): Logger = Logger.getGlobal()
    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("Unsupported")
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}

/** Step-attempt store whose [updateStepAttempt] always fails — simulates persistence failure. */
private class FailingAttemptUpdateStore(
    private val delegate: StepAttemptRecordStore,
) : StepAttemptRecordStore {
    override suspend fun recordStepAttempt(attempt: StepAttemptRecord) = delegate.recordStepAttempt(attempt)

    override suspend fun updateStepAttempt(attempt: StepAttemptRecord): StepAttemptRecord =
        throw IllegalStateException("simulated attempt update failure")

    override suspend fun latestStepAttempt(runId: String, stepName: String): StepAttemptRecord? =
        delegate.latestStepAttempt(runId, stepName)

    override suspend fun listStepAttempts(runId: String): List<StepAttemptRecord> =
        delegate.listStepAttempts(runId)
}
