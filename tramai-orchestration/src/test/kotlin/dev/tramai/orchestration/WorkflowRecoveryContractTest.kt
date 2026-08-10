package dev.tramai.orchestration

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
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
    fun `retryStep persists approval on the exact unresolved attempt`() {
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
            assertThat(attempt.status).isEqualTo(StepAttemptStatus.UNKNOWN)
            assertThat(attempt.resolutionAction).isEqualTo(StepAttemptResolutionAction.RETRY_APPROVED)
            assertThat(attempt.resolutionReason).isEqualTo("operator confirmed safe to retry")
            assertThat(attempt.resolutionAtEpochMillis).isNotNull
            assertThat(attempt.approvedIdempotencyKey).isNull()
            val after = store.load(saved.workflowName, saved.workflowId)!!
            assertThat(after.recoveryState).isSameAs(WorkflowRecoveryState.Normal)
        }
    }

    @Test
    fun `stale approval write cannot overwrite a concurrent successful authorization`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val original = StepAttemptRecord(
                runId = "run-cas",
                stepName = "step",
                attemptId = "attempt-1",
                workerId = "w",
                leaseToken = "l",
                status = StepAttemptStatus.UNKNOWN,
                startedAt = 0,
                replayPolicy = ReplayPolicy.EXTERNALLY_IDEMPOTENT,
            )
            store.recordStepAttempt(original)

            // Operator A approves key-A — the successful authorization.
            val keyA = original.copy(
                resolutionAction = StepAttemptResolutionAction.RETRY_APPROVED,
                resolutionReason = "reason-a",
                resolutionAtEpochMillis = 1,
                approvedIdempotencyKey = "key-A",
            )
            assertThat(store.compareAndSetStepAttempt(expected = original, updated = keyA)).isTrue()

            // Operator B holds the pre-A snapshot and writes key-B AFTER A succeeded —
            // the read-before-write interleaving from the concurrent-approval race.
            val keyB = original.copy(
                resolutionAction = StepAttemptResolutionAction.RETRY_APPROVED,
                resolutionReason = "reason-b",
                resolutionAtEpochMillis = 2,
                approvedIdempotencyKey = "key-B",
            )
            assertThat(store.compareAndSetStepAttempt(expected = original, updated = keyB)).isFalse()

            // B's failed write must not have replaced A's authorization.
            val current = store.listStepAttempts("run-cas").single()
            assertThat(current.approvedIdempotencyKey).isEqualTo("key-A")
            assertThat(current.resolutionReason).isEqualTo("reason-a")
        }
    }

    @Test
    fun `concurrent retry approvals — the approval whose checkpoint clear succeeds is the effective authorization`() {
        runBlocking {
            val delegate = InMemoryWorkflowCheckpointStore()
            val saved = delegate.save(sampleCheckpoint())
            delegate.recordStepAttempt(
                StepAttemptRecord(
                    runId = saved.workflowId,
                    stepName = "step",
                    attemptId = "attempt-1",
                    workerId = "w",
                    leaseToken = "l",
                    status = StepAttemptStatus.UNKNOWN,
                    startedAt = 0,
                    replayPolicy = ReplayPolicy.EXTERNALLY_IDEMPOTENT,
                ),
            )
            val required = delegate.requireRecovery(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = saved.revision,
                record = WorkflowRecoveryRecord(
                    reason = WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING,
                    stepName = "step",
                    attemptId = "attempt-1",
                    priorWorkerId = "w",
                    detectedAtEpochMillis = 0,
                ),
            )
            // B's pre-read: the attempt exactly as it exists before any authorization.
            val preRead = delegate.listStepAttempts(saved.workflowId).single()

            val reached = Channel<Unit>(1)
            val release = Channel<Unit>(1)
            val store = FrozenAttemptGateStore(delegate, preRead, reached, release)
            val controllerA = InMemoryWorkflowRecoveryController(store, store)
            val controllerB = InMemoryWorkflowRecoveryController(store, store)

            // B loads the same Required checkpoint, then blocks at its attempt read until A
            // has completed — the read-before-write interleaving from the concurrent-approval race.
            val bOutcome = async {
                runCatching {
                    controllerB.retryStep(
                        workflowName = saved.workflowName,
                        workflowId = saved.workflowId,
                        expectedRevision = required.revision,
                        reason = "reason-b",
                        approvedIdempotencyKey = "key-B",
                    )
                }
            }
            reached.receive()

            // A approves key-A and clears recovery — the effective authorization.
            controllerA.retryStep(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = required.revision,
                reason = "reason-a",
                approvedIdempotencyKey = "key-A",
            )
            release.send(Unit)

            // B resumes with its pre-A snapshot: its CAS must fail and its call must fail
            // closed without overwriting A's authorization.
            val bFailure = bOutcome.await().exceptionOrNull()
            assertThat(bFailure).isInstanceOf(WorkflowRecoveryStateException::class.java)

            val attempt = store.listStepAttempts(saved.workflowId).single()
            assertThat(attempt.resolutionAction).isEqualTo(StepAttemptResolutionAction.RETRY_APPROVED)
            assertThat(attempt.approvedIdempotencyKey).isEqualTo("key-A")
            assertThat(attempt.resolutionReason).isEqualTo("reason-a")
            assertThat(store.load(saved.workflowName, saved.workflowId)!!.recoveryState)
                .isSameAs(WorkflowRecoveryState.Normal)
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
    fun `retryStep rejects IDEMPOTENCY_KEY_MISMATCH recovery and keeps checkpoint required`() {
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
                    idempotencyKey = "recorded-key",
                    replayPolicy = ReplayPolicy.EXTERNALLY_IDEMPOTENT,
                ),
            )
            val required = store.requireRecovery(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = saved.revision,
                record = WorkflowRecoveryRecord(
                    reason = WorkflowRecoveryReason.IDEMPOTENCY_KEY_MISMATCH,
                    stepName = "step",
                    attemptId = "a",
                    priorWorkerId = "w",
                    detectedAtEpochMillis = 0,
                    idempotencyKey = "recorded-key",
                ),
            )

            assertThatThrownBy {
                runBlocking {
                    controller.retryStep(
                        workflowName = saved.workflowName,
                        workflowId = saved.workflowId,
                        expectedRevision = required.revision,
                        reason = "retry must not bypass key verification",
                    )
                }
            }.isInstanceOf(WorkflowRecoveryStateException::class.java)

            val after = store.load(saved.workflowName, saved.workflowId)!!
            assertThat(after.recoveryState).isInstanceOf(WorkflowRecoveryState.Required::class.java)
            assertThat(after.revision).isEqualTo(required.revision)
            // The guard fires before the mandatory transition — the attempt stays UNKNOWN.
            assertThat(store.listStepAttempts(saved.workflowId).single().status)
                .isEqualTo(StepAttemptStatus.UNKNOWN)
        }
    }

    @Test
    fun `retryStep rejects EXTERNAL_IDEMPOTENCY_KEY_MISSING recovery and keeps checkpoint required`() {
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
                    replayPolicy = ReplayPolicy.EXTERNALLY_IDEMPOTENT,
                ),
            )
            val required = store.requireRecovery(
                workflowName = saved.workflowName,
                workflowId = saved.workflowId,
                expectedRevision = saved.revision,
                record = WorkflowRecoveryRecord(
                    reason = WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING,
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
                        reason = "retry must not bypass key verification",
                    )
                }
            }.isInstanceOf(WorkflowRecoveryStateException::class.java)

            val after = store.load(saved.workflowName, saved.workflowId)!!
            assertThat(after.recoveryState).isInstanceOf(WorkflowRecoveryState.Required::class.java)
            assertThat(after.revision).isEqualTo(required.revision)
            assertThat(store.listStepAttempts(saved.workflowId).single().status)
                .isEqualTo(StepAttemptStatus.UNKNOWN)
        }
    }

    @Test
    fun `key-bound retry accepts externally idempotent recovery reasons`() {
        runBlocking {
            for (recoveryReason in listOf(
                WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING,
                WorkflowRecoveryReason.IDEMPOTENCY_KEY_MISMATCH,
            )) {
                val store = InMemoryWorkflowCheckpointStore()
                val saved = store.save(sampleCheckpoint())
                store.recordStepAttempt(unresolvedAttempt(saved.workflowId, ReplayPolicy.EXTERNALLY_IDEMPOTENT))
                val required = store.requireRecovery(
                    saved.workflowName,
                    saved.workflowId,
                    saved.revision,
                    recoveryRecord(recoveryReason),
                )

                InMemoryWorkflowRecoveryController(store, store).retryStep(
                    saved.workflowName,
                    saved.workflowId,
                    required.revision,
                    "operator approved exact key",
                    "approved-key",
                )

                val attempt = store.listStepAttempts(saved.workflowId).single()
                assertThat(attempt.status).isEqualTo(StepAttemptStatus.UNKNOWN)
                assertThat(attempt.resolutionAction).isEqualTo(StepAttemptResolutionAction.RETRY_APPROVED)
                assertThat(attempt.approvedIdempotencyKey).isEqualTo("approved-key")
            }
        }
    }

    @Test
    fun `key-bound retry rejects blank approved key without mutation`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val saved = store.save(sampleCheckpoint())
            store.recordStepAttempt(unresolvedAttempt(saved.workflowId, ReplayPolicy.EXTERNALLY_IDEMPOTENT))
            val required = store.requireRecovery(saved.workflowName, saved.workflowId, saved.revision, recoveryRecord(
                WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING,
            ))

            assertThatThrownBy {
                runBlocking {
                    InMemoryWorkflowRecoveryController(store, store).retryStep(
                        saved.workflowName, saved.workflowId, required.revision, "reason", "   ",
                    )
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(store.listStepAttempts(saved.workflowId).single().resolutionAction).isNull()
            assertThat(store.load(saved.workflowName, saved.workflowId)!!.recoveryState)
                .isInstanceOf(WorkflowRecoveryState.Required::class.java)
        }
    }

    @Test
    fun `retry approval rejects terminal attempts and preserves required checkpoint`() {
        runBlocking {
            for (status in listOf(StepAttemptStatus.COMPLETED, StepAttemptStatus.FAILED, StepAttemptStatus.CANCELLED)) {
                val store = InMemoryWorkflowCheckpointStore()
                val saved = store.save(sampleCheckpoint())
                store.recordStepAttempt(unresolvedAttempt(saved.workflowId).copy(status = status))
                val required = store.requireRecovery(
                    saved.workflowName, saved.workflowId, saved.revision, recoveryRecord(),
                )
                val result = runCatching {
                    InMemoryWorkflowRecoveryController(store, store).retryStep(
                        saved.workflowName, saved.workflowId, required.revision, "unsafe terminal mutation",
                    )
                }
                assertThat(result.exceptionOrNull()).isInstanceOf(WorkflowRecoveryStateException::class.java)
                assertThat(store.listStepAttempts(saved.workflowId).single().resolutionAction).isNull()
                assertThat(store.load(saved.workflowName, saved.workflowId)!!.recoveryState)
                    .isInstanceOf(WorkflowRecoveryState.Required::class.java)
            }
        }
    }

    @Test
    fun `approval survives clear failure and identical retry clears while conflict is rejected`() {
        runBlocking {
            val delegate = InMemoryWorkflowCheckpointStore()
            val checkpointStore = FailingClearOnceStore(delegate)
            val saved = delegate.save(sampleCheckpoint())
            delegate.recordStepAttempt(unresolvedAttempt(saved.workflowId))
            val required = delegate.requireRecovery(saved.workflowName, saved.workflowId, saved.revision, recoveryRecord())
            val controller = InMemoryWorkflowRecoveryController(checkpointStore, delegate)

            assertThat(runCatching {
                controller.retryStep(saved.workflowName, saved.workflowId, required.revision, "approved")
            }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(delegate.listStepAttempts(saved.workflowId).single().resolutionAction)
                .isEqualTo(StepAttemptResolutionAction.RETRY_APPROVED)
            assertThat(delegate.load(saved.workflowName, saved.workflowId)!!.recoveryState)
                .isInstanceOf(WorkflowRecoveryState.Required::class.java)

            assertThat(runCatching {
                controller.retryStep(saved.workflowName, saved.workflowId, required.revision, "different")
            }.exceptionOrNull()).isInstanceOf(WorkflowRecoveryStateException::class.java)

            val cleared = controller.retryStep(saved.workflowName, saved.workflowId, required.revision, "approved")
            assertThat(cleared.recoveryState).isSameAs(WorkflowRecoveryState.Normal)
        }
    }

    @Test
    fun `failWorkflow records WORKFLOW_FAILED evidence only after successful deletion`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val saved = store.save(sampleCheckpoint())
            store.recordStepAttempt(
                unresolvedAttempt(saved.workflowId).copy(approvedIdempotencyKey = "must-be-cleared"),
            )
            val required = store.requireRecovery(saved.workflowName, saved.workflowId, saved.revision, recoveryRecord())

            InMemoryWorkflowRecoveryController(store, store).failWorkflow(
                saved.workflowName, saved.workflowId, required.revision, "operator failed workflow",
            )

            val evidence = store.listStepAttempts(saved.workflowId).single()
            assertThat(store.load(saved.workflowName, saved.workflowId)).isNull()
            assertThat(evidence.status).isEqualTo(StepAttemptStatus.FAILED)
            assertThat(evidence.resolutionAction).isEqualTo(StepAttemptResolutionAction.WORKFLOW_FAILED)
            assertThat(evidence.approvedIdempotencyKey).isNull()
        }
    }

    @Test
    fun `failed deletion writes no workflow-failed evidence`() {
        runBlocking {
            val delegate = InMemoryWorkflowCheckpointStore()
            val saved = delegate.save(sampleCheckpoint())
            delegate.recordStepAttempt(unresolvedAttempt(saved.workflowId))
            val required = delegate.requireRecovery(saved.workflowName, saved.workflowId, saved.revision, recoveryRecord())

            assertThat(runCatching {
                InMemoryWorkflowRecoveryController(FailingDeleteStore(delegate), delegate).failWorkflow(
                    saved.workflowName, saved.workflowId, required.revision, "fail",
                )
            }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(delegate.listStepAttempts(saved.workflowId).single().resolutionAction).isNull()
            assertThat(delegate.load(saved.workflowName, saved.workflowId)!!.recoveryState)
                .isInstanceOf(WorkflowRecoveryState.Required::class.java)
        }
    }

    @Test
    fun `evidence write failure after deletion does not recreate checkpoint`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val saved = store.save(sampleCheckpoint())
            store.recordStepAttempt(unresolvedAttempt(saved.workflowId))
            val required = store.requireRecovery(saved.workflowName, saved.workflowId, saved.revision, recoveryRecord())

            InMemoryWorkflowRecoveryController(store, FailingAttemptUpdateStore(store)).failWorkflow(
                saved.workflowName, saved.workflowId, required.revision, "fail",
            )

            assertThat(store.load(saved.workflowName, saved.workflowId)).isNull()
            assertThat(store.listStepAttempts(saved.workflowId).single().resolutionAction).isNull()
        }
    }

    @Test
    fun `resolution fields round trip and old records retain null defaults`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val old = unresolvedAttempt("old-run")
            store.recordStepAttempt(old)
            assertThat(store.listStepAttempts("old-run").single().resolutionAction).isNull()
            assertThat(store.listStepAttempts("old-run").single().approvedIdempotencyKey).isNull()

            val updated = old.copy(
                resolutionAction = StepAttemptResolutionAction.RETRY_APPROVED,
                approvedIdempotencyKey = "key",
            )
            store.updateStepAttempt(updated)
            assertThat(store.listStepAttempts("old-run").single()).isEqualTo(updated)
        }
    }

    @Test
    fun `unknown persisted resolution action fails closed`() {
        assertThat(decodeResolutionAction(null)).isNull()
        assertThat(decodeResolutionAction("RETRY_APPROVED")).isEqualTo(StepAttemptResolutionAction.RETRY_APPROVED)
        assertThatThrownBy { decodeResolutionAction("NOPE") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Unknown StepAttemptResolutionAction: 'NOPE'")
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
        // decodeRecoveryState is internal; it fails closed with the internal
        // corruption carrier. The public fixed-text
        // WorkflowCheckpointCorruptionException is proven at the store boundary
        // (PersistenceSafeFailureBoundaryTest).
        assertThatThrownBy { decodeRecoveryState("not-a-valid-properties-format") }
            .isInstanceOf(CorruptCheckpointException::class.java)
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

    private fun recoveryRecord(
        reason: WorkflowRecoveryReason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
    ): WorkflowRecoveryRecord = WorkflowRecoveryRecord(
        reason = reason,
        stepName = "step",
        attemptId = "attempt-1",
        priorWorkerId = "worker-a",
        detectedAtEpochMillis = 10,
    )

    private fun unresolvedAttempt(
        runId: String,
        replayPolicy: ReplayPolicy = ReplayPolicy.NON_REPLAYABLE,
    ): StepAttemptRecord = StepAttemptRecord(
        runId = runId,
        stepName = "step",
        attemptId = "attempt-1",
        workerId = "worker-a",
        leaseToken = "lease-a",
        status = StepAttemptStatus.UNKNOWN,
        startedAt = 10,
        replayPolicy = replayPolicy,
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

/**
 * Checkpoint/attempt store that makes the concurrent-approval race deterministic.
 *
 * The FIRST [listStepAttempts] caller (the slow operator B) is served a frozen
 * pre-authorization attempt snapshot after the test releases it, so B builds its
 * approval from a read that predates the winning authorization; all later calls
 * delegate to the real store. Checkpoint operations always delegate.
 */
private class FrozenAttemptGateStore(
    private val delegate: InMemoryWorkflowCheckpointStore,
    private val frozenSnapshot: StepAttemptRecord,
    private val reached: Channel<Unit>,
    private val release: Channel<Unit>,
) : WorkflowCheckpointStore by delegate, StepAttemptRecordStore by delegate {
    private var servedStale = false

    override suspend fun listStepAttempts(runId: String): List<StepAttemptRecord> {
        if (!servedStale) {
            servedStale = true
            reached.send(Unit)
            release.receive()
            return listOf(frozenSnapshot)
        }
        return delegate.listStepAttempts(runId)
    }
}

/** Step-attempt store whose [updateStepAttempt] always fails — simulates persistence failure. */
private class FailingAttemptUpdateStore(
    private val delegate: StepAttemptRecordStore,
) : StepAttemptRecordStore {
    override suspend fun recordStepAttempt(attempt: StepAttemptRecord) = delegate.recordStepAttempt(attempt)

    override suspend fun updateStepAttempt(attempt: StepAttemptRecord): StepAttemptRecord =
        throw IllegalStateException("simulated attempt update failure")

    override suspend fun compareAndSetStepAttempt(
        expected: StepAttemptRecord,
        updated: StepAttemptRecord,
    ): Boolean = throw IllegalStateException("simulated attempt update failure")

    override suspend fun latestStepAttempt(runId: String, stepName: String): StepAttemptRecord? =
        delegate.latestStepAttempt(runId, stepName)

    override suspend fun listStepAttempts(runId: String): List<StepAttemptRecord> =
        delegate.listStepAttempts(runId)
}

private class FailingClearOnceStore(
    private val delegate: WorkflowCheckpointStore,
) : WorkflowCheckpointStore by delegate {
    private var fail = true

    override suspend fun clearRecovery(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
    ): WorkflowCheckpoint {
        if (fail) {
            fail = false
            throw IllegalStateException("simulated clear failure")
        }
        return delegate.clearRecovery(workflowName, workflowId, expectedRevision)
    }
}

private class FailingDeleteStore(
    private val delegate: WorkflowCheckpointStore,
) : WorkflowCheckpointStore by delegate {
    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
    ) {
        throw IllegalStateException("simulated delete failure")
    }
}
