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

    /**
     * Delta-based fake: a mark captures the source's reading at creation, so
     * elapsed = now - markOrigin. Distinguishes a mark captured once at start
     * (correct) from a fresh mark sampled per heartbeat (M03 defect).
     */
    private class DeltaMonotonicTimeSource : MonotonicTimeSource {
        var now = 0L
        override fun markNow(): MonotonicMark = object : MonotonicMark {
            private val origin = this@DeltaMonotonicTimeSource.now
            override fun elapsedMillis(): Long = (this@DeltaMonotonicTimeSource.now - origin).coerceAtLeast(0L)
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
        failingStep: Boolean = false,
        cancellableGate: Boolean = false,
        observer: TramaiWorkerObserver = object : TramaiWorkerObserver {},
    ): Pair<TramaiWorker, InMemoryWorkflowCheckpointStore> {
        val store = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workflow = workflow<LifecycleState>("time-semantics", definitionVersion = "v1") {
            localStep("hold") { state, _ ->
                if (failingStep) {
                    throw RuntimeException("boom")
                }
                if (cancellableGate) {
                    gate.await()
                } else {
                    withContext(NonCancellable) { gate.await() }
                }
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
            observability = observer,
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
        // Delta-based fake: a mark captures its creation instant, so a fresh
        // mark per heartbeat (M03) would report ~0 and fail this sequence.
        val observer = HeartbeatObserver()
        val publisher = WorkerHeartbeatPublisher(heartbeatConfig, null, observer)
        val delta = DeltaMonotonicTimeSource()
        val startedMark = delta.markNow()
        runBlocking {
            delta.now = 50L
            val job = launch {
                publisher.heartbeatLoop(startedAtMark = { startedMark }, claimedCount = { 0 })
            }
            while (observer.uptimes.size < 1) delay(5)
            delta.now = 100L
            while (observer.uptimes.size < 2) delay(5)
            job.cancelAndJoin()
        }
        assertThat(observer.uptimes)
            .withFailMessage("P0-A uptime must be monotonic elapsed (50ms), not wall-clock derived")
            .containsExactly(50L, 100L)
    }

    @Test
    fun `P0-A monotonic elapsed arithmetic is deterministic`() {
        // The NanoTimeSource elapsed calculation must be exactly
        // (currentReading - startReading): controlled raw readings prove the
        // arithmetic without depending on real elapsed time. Kills the M04
        // sign-swap mutant (startReading - currentReading -> clamped to 0).
        val readings = ArrayDeque(listOf(100_000_000L, 150_000_000L))
        val source = NanoTimeSource(nanoTime = { readings.removeFirst() })
        val mark = source.markNow()
        assertThat(mark.elapsedMillis())
            .withFailMessage("P0-A monotonic elapsed must be currentReading - startReading (50ms)")
            .isEqualTo(50L)
    }

    @Test
    fun `P0-A worker heartbeat uptime tracks the captured start mark`() {
        // Exercises the CONTROLLER's heartbeat wiring (the publisher-direct
        // test cannot see it): the worker must capture the start mark ONCE and
        // report growing uptime from it. Kills the M03 mutant (fresh mark per
        // heartbeat via timeSource.markNow() -> uptime always ~0).
        val delta = DeltaMonotonicTimeSource()
        val heartbeats = CopyOnWriteArrayList<Long>()
        val gate = CompletableDeferred<Unit>()
        val (worker, store) = gatedWorker(
            gate,
            drainTimeoutMillis = 500,
            observer = object : TramaiWorkerObserver {
                override fun onWorkerHeartbeat(workerId: String, uptimeMillis: Long, claimedCount: Int) {
                    heartbeats += uptimeMillis
                }
            },
        )
        worker.timeSourceForTest = delta
        try {
            runBlocking {
                worker.start()
                withTimeout(10_000) {
                    while (store.latestStepAttempt("w-1", "hold") == null) delay(5)
                }
                while (heartbeats.size < 2) delay(5)
                delta.now = 50L
                val before = heartbeats.size
                while (heartbeats.size <= before) delay(5)
                delta.now = 100L
                val before2 = heartbeats.size
                while (heartbeats.size <= before2) delay(5)
                gate.complete(Unit)
            }
        } finally {
            runBlocking { runCatching { worker.close() } }
        }
        assertThat(heartbeats.takeLast(3))
            .withFailMessage("P0-A worker uptime must track the captured start mark (fresh-mark M03 -> all ~0)")
            .contains(50L, 100L)
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
                // Wait for the WORKER's execution (STARTED attempt), not the
                // seeded checkpoint: the persistence session is created before
                // the initial save, so this guarantees it exists at clock=3000.
                withTimeout(10_000) {
                    while (store.latestStepAttempt("w-1", "hold") == null) delay(5)
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

    @Test
    fun `P0-B2 drain residual clamps to minimum when monotonic elapsed exceeds the budget`() {
        // Companion to P0-B: when the monotonic source reports MORE elapsed
        // than the budget (fake = 5000ms vs 500ms budget), the residual must
        // clamp to ~1ms — the drain must NOT re-consume the full budget.
        // Kills the M05 mutant (residual = full drainTimeout regardless).
        val gate = CompletableDeferred<Unit>()
        val fake = FakeMonotonicTimeSource().apply { elapsedMillis = 5_000L }
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
                    .withFailMessage("P0-B2 residual must clamp to ~1ms when elapsed exceeds the budget")
                    .isLessThan(800L)
                gate.complete(Unit)
            }
        } finally {
            runBlocking { runCatching { worker.close() } }
        }
    }

    @Test
    fun `P0-C failed attempt terminal timestamp comes from the injected clock`() {
        val clock = MutableClock(1_000L)
        val gate = CompletableDeferred<Unit>()
        val (worker, store) = gatedWorker(gate, drainTimeoutMillis = 60_000, clock = clock, failingStep = true)
        try {
            runBlocking {
                worker.start()
                withTimeout(10_000) {
                    while (store.latestStepAttempt("w-1", "hold")?.status != StepAttemptStatus.FAILED) delay(5)
                }
                val failed = store.latestStepAttempt("w-1", "hold")!!
                assertThat(failed.completedAt)
                    .withFailMessage("P0-C failed attempt completedAt must be the injected clock's reading")
                    .isEqualTo(1_000L)
            }
        } finally {
            runBlocking { runCatching { worker.close() } }
        }
    }

    @Test
    fun `P0-C cancelled attempt terminal timestamp comes from the injected clock`() {
        val clock = MutableClock(1_000L)
        val gate = CompletableDeferred<Unit>()
        val (worker, store) = gatedWorker(gate, drainTimeoutMillis = 500, clock = clock, cancellableGate = true)
        try {
            runBlocking {
                worker.start()
                withTimeout(10_000) {
                    while (store.latestStepAttempt("w-1", "hold")?.status != StepAttemptStatus.STARTED) delay(5)
                }
                worker.shutdown()
                withTimeout(10_000) {
                    while (store.latestStepAttempt("w-1", "hold")?.status != StepAttemptStatus.CANCELLED) delay(5)
                }
                val cancelled = store.latestStepAttempt("w-1", "hold")!!
                assertThat(cancelled.completedAt)
                    .withFailMessage("P0-C cancelled attempt completedAt must be the injected clock's reading")
                    .isEqualTo(1_000L)
            }
        } finally {
            runBlocking { runCatching { worker.close() } }
        }
    }

    @Test
    fun `P0-C UNKNOWN recovery completion timestamp comes from the injected clock`() {
        val clock = MutableClock(1_000L)
        val gate = CompletableDeferred<Unit>()
        val (worker, store) = gatedWorker(gate, drainTimeoutMillis = 500, clock = clock)
        try {
            runBlocking {
                // A previous worker's attempt is still STARTED; the new worker
                // must flip it to UNKNOWN with the injected clock's reading.
                store.recordStepAttempt(
                    StepAttemptRecord(
                        runId = "w-1",
                        stepName = "hold",
                        attemptId = "attempt-old",
                        workerId = "worker-old",
                        leaseToken = "lease-old",
                        status = StepAttemptStatus.STARTED,
                        startedAt = 10,
                        replayPolicy = ReplayPolicy.NON_REPLAYABLE,
                    ),
                )
                worker.start()
                withTimeout(10_000) {
                    while (store.latestStepAttempt("w-1", "hold")?.status != StepAttemptStatus.UNKNOWN) delay(5)
                }
                val unknown = store.latestStepAttempt("w-1", "hold")!!
                assertThat(unknown.completedAt)
                    .withFailMessage("P0-C UNKNOWN recovery completedAt must be the injected clock's reading")
                    .isEqualTo(1_000L)
                gate.complete(Unit)
            }
        } finally {
            runBlocking { runCatching { worker.close() } }
        }
    }

    @Test
    fun `P0-C retry-approval consumption stamps from or preserves the injected clock`() {
        val clock = MutableClock(1_000L)
        // Scenario A: completedAt absent -> stamped from the injected clock.
        val gateA = CompletableDeferred<Unit>()
        val (workerA, storeA) = gatedWorker(gateA, drainTimeoutMillis = 500, clock = clock)
        try {
            runBlocking {
                storeA.recordStepAttempt(
                    StepAttemptRecord(
                        runId = "w-1",
                        stepName = "hold",
                        attemptId = "attempt-1",
                        workerId = "worker-old",
                        leaseToken = "lease-old",
                        status = StepAttemptStatus.UNKNOWN,
                        startedAt = 10,
                        replayPolicy = ReplayPolicy.NON_REPLAYABLE,
                        resolutionAction = StepAttemptResolutionAction.RETRY_APPROVED,
                        resolutionReason = "safe after inspection",
                        approvedIdempotencyKey = null,
                    ),
                )
                workerA.start()
                withTimeout(10_000) {
                    while (storeA.listStepAttempts("w-1").firstOrNull { it.attemptId == "attempt-1" }?.status != StepAttemptStatus.FAILED) delay(5)
                }
                val consumed = storeA.listStepAttempts("w-1").first { it.attemptId == "attempt-1" }
                assertThat(consumed.completedAt)
                    .withFailMessage("P0-C retry-approval consumption must stamp completedAt from the injected clock")
                    .isEqualTo(1_000L)
                gateA.complete(Unit)
            }
        } finally {
            runBlocking { runCatching { workerA.close() } }
        }
        // Scenario B: completedAt already present (999) -> must remain unchanged (T3).
        val gateB = CompletableDeferred<Unit>()
        val (workerB, storeB) = gatedWorker(gateB, drainTimeoutMillis = 500, clock = clock)
        try {
            runBlocking {
                storeB.recordStepAttempt(
                    StepAttemptRecord(
                        runId = "w-1",
                        stepName = "hold",
                        attemptId = "attempt-1",
                        workerId = "worker-old",
                        leaseToken = "lease-old",
                        status = StepAttemptStatus.UNKNOWN,
                        startedAt = 10,
                        completedAt = 999L,
                        replayPolicy = ReplayPolicy.NON_REPLAYABLE,
                        resolutionAction = StepAttemptResolutionAction.RETRY_APPROVED,
                        resolutionReason = "safe after inspection",
                        approvedIdempotencyKey = null,
                    ),
                )
                workerB.start()
                withTimeout(10_000) {
                    while (storeB.listStepAttempts("w-1").firstOrNull { it.attemptId == "attempt-1" }?.status != StepAttemptStatus.FAILED) delay(5)
                }
                val consumed = storeB.listStepAttempts("w-1").first { it.attemptId == "attempt-1" }
                assertThat(consumed.completedAt)
                    .withFailMessage("P0-C retry-approval consumption must preserve an existing completedAt")
                    .isEqualTo(999L)
                gateB.complete(Unit)
            }
        } finally {
            runBlocking { runCatching { workerB.close() } }
        }
    }

    @Test
    fun `P0-C failWorkflow resolution timestamp comes from the injected clock`() {
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
            InMemoryWorkflowRecoveryController(store, store, clock = clock).failWorkflow(
                workflowName = "time-semantics",
                workflowId = "w-1",
                expectedRevision = saved.revision,
                expectedGeneration = saved.checkpointGeneration,
                reason = "resolved by operator",
            )
            val attempt = store.listStepAttempts("w-1").single()
            assertThat(attempt.resolutionAtEpochMillis)
                .withFailMessage("P0-C failWorkflow resolutionAtEpochMillis must be the injected clock's reading")
                .isEqualTo(1_000L)
            assertThat(attempt.completedAt)
                .withFailMessage("P0-C failWorkflow evidence completedAt must be the injected clock's reading")
                .isEqualTo(1_000L)
        }
    }

    @Test
    fun `P0-E legacy checkpoint missing savedAt decodes as UNKNOWN not read-time`() {
        // Contract: a legacy record with no savedAtEpochMillis property must
        // decode to 0L (historical save time unknown) — never
        // System.currentTimeMillis() at read time, and never a fresh Clock
        // reading. TramAI never fabricates a historical save timestamp.
        val encoded = encodeCheckpoint(
            WorkflowCheckpoint(
                workflowName = "time-semantics",
                workflowId = "w-1",
                nextStepIndex = 0,
                stepExecutions = 0,
                lastCompletedStepName = null,
                statePayload = LifecycleCodec.encode(LifecycleState("start")),
                savedAtEpochMillis = 123_456L,
            ),
        )
        val legacy = encoded.lines().filterNot { it.startsWith("savedAtEpochMillis") }.joinToString("\n")
        // RED: production synthesizes System.currentTimeMillis() here.
        assertThat(decodeCheckpoint(legacy).savedAtEpochMillis)
            .withFailMessage("P0-E legacy missing savedAt must decode as UNKNOWN (0L)")
            .isEqualTo(0L)
        // Deterministic across repeated decodes of the same bytes.
        assertThat(decodeCheckpoint(legacy).savedAtEpochMillis)
            .withFailMessage("P0-E legacy decode must be stable, never read-time")
            .isEqualTo(0L)
        // A present value must survive exactly.
        assertThat(decodeCheckpoint(encoded).savedAtEpochMillis)
            .withFailMessage("P0-E a present savedAtEpochMillis must be preserved exactly")
            .isEqualTo(123_456L)
    }
}
