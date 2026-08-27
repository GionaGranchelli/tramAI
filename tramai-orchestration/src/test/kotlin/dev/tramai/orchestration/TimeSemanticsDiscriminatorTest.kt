package dev.tramai.orchestration

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Epic 8.3a — wall-clock vs monotonic-time discriminators (RED-first).
 *
 * P0-A: worker uptime must be monotonic elapsed time, never wall-clock
 * derived (an NTP/operator wall-clock jump must not change uptime).
 * P0-B: the shutdown drain's residual budget must be monotonic elapsed
 * time, never wall-clock derived (a wall-clock jump must not inflate or
 * destroy the remaining drain budget).
 *
 * Both tests are RED on the current production code: the uptime formula
 * reads System.currentTimeMillis() and the drain residual reads
 * System.currentTimeMillis(), so neither can express the monotonic
 * contract.
 */
class TimeSemanticsDiscriminatorTest {

    private val heartbeatConfig = WorkerConfig(
        workerId = "time-semantics-heartbeat",
        poolName = "tests",
        pollIntervalMillis = 20,
        leaseDurationMillis = 5_000,
        drainTimeoutMillis = 60_000,
    )

    private val drainConfig = WorkerConfig(
        workerId = "time-semantics-drain",
        poolName = "tests",
        pollIntervalMillis = 20,
        leaseDurationMillis = 5_000,
        drainTimeoutMillis = 500,
    )

    private class HeartbeatObserver : TramaiWorkerObserver {
        val uptimes = CopyOnWriteArrayList<Long>()
        override fun onWorkerHeartbeat(workerId: String, uptimeMillis: Long, claimedCount: Int) {
            uptimes += uptimeMillis
        }
    }

    /** Fake monotonic source whose elapsed reading is fully test-controlled. */
    private class FakeMonotonicTimeSource : MonotonicTimeSource {
        var elapsedMillis = 0L
        override fun markNow(): MonotonicMark = object : MonotonicMark {
            override fun elapsedMillis(): Long = this@FakeMonotonicTimeSource.elapsedMillis
        }
    }

    /** Mutable wall clock for P0-C/P0-D persisted-timestamp discriminators. */
    private class MutableClock(var millis: Long) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
        override fun withZone(zone: ZoneId): Clock = this
        override fun getZone(): ZoneId = ZoneOffset.UTC
    }

    private data class LifecycleState(val value: String)

    private object LifecycleCodec : WorkflowStateCodec<LifecycleState> {
        override fun encode(state: LifecycleState): String = state.value
        override fun decode(payload: String): LifecycleState = LifecycleState(payload)
    }

    /**
     * Worker whose single step blocks on [gate] inside NonCancellable: once
     * draining starts, the execution ignores cancellation until the gate
     * opens, so the drain's second phase actually has to wait out its
     * residual budget (the observable under test).
     */
    private fun gatedWorker(
        gate: CompletableDeferred<Unit>,
        drainTimeoutMillis: Long,
        clock: Clock = Clock.systemUTC(),
        keepCheckpoint: Boolean = false,
    ): Pair<TramaiWorker, InMemoryWorkflowCheckpointStore> {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workflow<LifecycleState>("time-semantics", definitionVersion = "v1") {
            localStep("hold") { state, _ ->
                withContext(NonCancellable) { gate.await() }
                state
            }
        }.build(clock = clock) { it.value }
        val bindings = WorkflowBindingRegistry {
            bind(
                workflow,
                WorkflowPersistence(
                    checkpointStore = store,
                    stateCodec = LifecycleCodec,
                    deleteCheckpointOnCompletion = !keepCheckpoint,
                ),
            )
        }
        val worker = TramaiWorker(
            config = WorkerConfig(
                workerId = "time-semantics-drain",
                poolName = "tests",
                pollIntervalMillis = 20,
                leaseDurationMillis = 5_000,
                drainTimeoutMillis = drainTimeoutMillis,
            ),
            leaseStore = leaseStore,
            checkpointStore = store,
            checkpointCatalog = store,
            stepAttemptStore = store,
            workflowBindings = bindings,
            observability = object : TramaiWorkerObserver {},
        )
        runBlocking {
            store.save(
                WorkflowCheckpoint(
                    workflowName = "time-semantics",
                    workflowId = "w-1",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = LifecycleCodec.encode(LifecycleState("start")),
                    metadata = workflow.checkpointMetadata(),
                ),
            )
        }
        return worker to store
    }

    @Test
    fun `P0-A worker uptime is monotonic and ignores the wall clock`() {
        // Contract: the start mark is created at monotonic T=0. Heartbeats at
        // T=50 and T=100 must report [50, 100], independently of wall clock.
        val observer = HeartbeatObserver()
        val publisher = WorkerHeartbeatPublisher(heartbeatConfig, null, observer)
        val fake = FakeMonotonicTimeSource()
        val startedMark = fake.markNow()
        runBlocking {
            fake.elapsedMillis = 50L
            val job = launch {
                publisher.heartbeatLoop(startedAtMark = { startedMark }, claimedCount = { 0 })
            }
            while (observer.uptimes.size < 1) delay(5)
            fake.elapsedMillis = 100L
            while (observer.uptimes.size < 2) delay(5)
            job.cancelAndJoin()
        }
        assertThat(observer.uptimes)
            .withFailMessage("P0-A uptime must be monotonic elapsed (50ms), not wall-clock derived")
            .containsExactly(50L, 100L)
    }

    @Test
    fun `P0-B shutdown drain residual budget is monotonic and wall-clock independent`() {
        // Contract: drain budget = 500ms; the monotonic source reports only
        // 50ms consumed (the wall clock in reality jumped far ahead), so the
        // residual must be ~450ms — the drain must NOT re-consume the full
        // budget. RED: production computes residual from
        // System.currentTimeMillis(), which after a full first-phase timeout
        // reports ~500ms real elapsed -> residual clamps to 1ms, so the whole
        // shutdown finishes in ~drainTimeout (well under the 700ms threshold).
        val gate = CompletableDeferred<Unit>()
        val fake = FakeMonotonicTimeSource().apply { elapsedMillis = 50L }
        val (worker, store) = gatedWorker(gate, drainTimeoutMillis = 500)
        worker.timeSourceForTest = fake
        try {
            runBlocking {
                worker.start()
                withTimeout(10_000) {
                    while (store.latestStepAttempt("w-1", "hold") == null) delay(5)
                }
                val drainStartedNanos = System.nanoTime()
                worker.shutdown()
                val totalMillis = (System.nanoTime() - drainStartedNanos) / 1_000_000
                assertThat(totalMillis)
                    .withFailMessage("P0-B residual must use monotonic elapsed (50ms consumed -> ~450ms residual), not wall-clock derived")
                    .isGreaterThanOrEqualTo(700L)
                gate.complete(Unit)
            }
        } finally {
            runBlocking { runCatching { worker.close() } }
        }
    }

    @Test
    fun `P0-C step-attempt timestamps come from the injected workflow clock`() {
        // Contract: startedAt = clock at start (T0), completedAt = clock at
        // completion (T1). RED: production stamps System.currentTimeMillis(),
        // so the persisted values never equal the injected clock's readings.
        val clock = MutableClock(1_000L)
        val gate = CompletableDeferred<Unit>()
        val (worker, store) = gatedWorker(gate, drainTimeoutMillis = 60_000, clock = clock)
        try {
            runBlocking {
                worker.start()
                withTimeout(10_000) {
                    while (store.latestStepAttempt("w-1", "hold") == null) delay(5)
                }
                val started = store.latestStepAttempt("w-1", "hold")!!
                clock.millis = 2_000L
                gate.complete(Unit)
                withTimeout(10_000) {
                    while (store.latestStepAttempt("w-1", "hold")?.status != StepAttemptStatus.COMPLETED) delay(5)
                }
                val completed = store.latestStepAttempt("w-1", "hold")!!
                assertThat(started.startedAt)
                    .withFailMessage("P0-C startedAt must be the injected clock's T0")
                    .isEqualTo(1_000L)
                assertThat(completed.completedAt)
                    .withFailMessage("P0-C completedAt must be the injected clock's T1")
                    .isEqualTo(2_000L)
                worker.shutdown()
            }
        } finally {
            runBlocking { runCatching { worker.close() } }
        }
    }

    @Test
    fun `P0-C recovery resolution timestamps come from the injected clock`() {
        // Contract: an operator retry approval records resolutionAtEpochMillis
        // from the injected clock. RED: production stamps
        // System.currentTimeMillis(), never equal to the injected reading.
        val clock = MutableClock(1_000L)
        val store = InMemoryWorkflowCheckpointStore()
        runBlocking {
            store.save(
                WorkflowCheckpoint(
                    workflowName = "time-semantics",
                    workflowId = "w-1",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = LifecycleCodec.encode(LifecycleState("start")),
                    metadata = emptyMap(),
                    recoveryState = WorkflowRecoveryState.Required(
                        WorkflowRecoveryRecord(
                            reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                            stepName = "hold",
                            attemptId = "attempt-1",
                            priorWorkerId = "worker-a",
                            detectedAtEpochMillis = 20,
                        ),
                    ),
                ),
            )
            store.recordStepAttempt(
                StepAttemptRecord(
                    runId = "w-1",
                    stepName = "hold",
                    attemptId = "attempt-1",
                    workerId = "worker-a",
                    leaseToken = "lease-a",
                    status = StepAttemptStatus.UNKNOWN,
                    startedAt = 10,
                    replayPolicy = ReplayPolicy.NON_REPLAYABLE,
                ),
            )
            val saved = store.load("time-semantics", "w-1")!!
            InMemoryWorkflowRecoveryController(store, store, clock = clock).retryStep(
                workflowName = "time-semantics",
                workflowId = "w-1",
                expectedRevision = saved.revision,
                expectedGeneration = saved.checkpointGeneration,
                reason = "safe after inspection",
            )
            val attempt = store.listStepAttempts("w-1").single()
            assertThat(attempt.resolutionAtEpochMillis)
                .withFailMessage("P0-C resolutionAtEpochMillis must be the injected clock's reading")
                .isEqualTo(1_000L)
        }
    }

    @Test
    fun `P0-D checkpoint savedAt is supplied by the injected clock`() {
        // Contract: the persistence coordinator stamps savedAtEpochMillis from
        // the injected clock at each save. RED: production lets the
        // WorkflowCheckpoint default fire (System.currentTimeMillis()), so the
        // persisted value never equals the injected reading.
        val clock = MutableClock(3_000L)
        val gate = CompletableDeferred<Unit>()
        val (worker, store) = gatedWorker(gate, drainTimeoutMillis = 60_000, clock = clock, keepCheckpoint = true)
        try {
            runBlocking {
                worker.start()
                withTimeout(10_000) {
                    while (store.load("time-semantics", "w-1") == null) delay(5)
                }
                clock.millis = 4_000L
                gate.complete(Unit)
                withTimeout(10_000) {
                    while (store.load("time-semantics", "w-1")?.lastCompletedStepName != "hold") delay(5)
                }
                val saved = store.load("time-semantics", "w-1")!!
                assertThat(saved.savedAtEpochMillis)
                    .withFailMessage("P0-D savedAtEpochMillis must be the injected clock's reading at save time")
                    .isEqualTo(4_000L)
                worker.shutdown()
            }
        } finally {
            runBlocking { runCatching { worker.close() } }
        }
    }
}
