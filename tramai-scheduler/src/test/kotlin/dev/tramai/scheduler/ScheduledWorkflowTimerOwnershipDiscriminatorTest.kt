@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.tramai.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 8.3c — scheduler lifecycle ownership discriminators.
 *
 * Invariant: ScheduledWorkflowTimer has exactly one authoritative lifecycle
 * owner for its polling execution. The timer always owns its polling child.
 * The default-created scope is timer-owned (closed on close); a caller-supplied
 * scope is borrowed and never cancelled. stop() is restartable and joins.
 * close() is terminal and idempotent. No start/stop/close interleaving may
 * leave an unowned child, two polling loops, or a loop alive after close.
 *
 * Deterministic only: CompletableDeferred gates/latches, one bounded
 * state-await handshake (no Thread.sleep, no fixed-duration waits).
 */
@Timeout(30)
class ScheduledWorkflowTimerOwnershipDiscriminatorTest {

    private fun timer(
        store: WorkflowSchedulerStore,
        scope: CoroutineScope,
    ): ScheduledWorkflowTimer = ScheduledWorkflowTimer(
        store = store,
        scope = scope,
        pollInterval = Duration.ofMillis(1),
    )

    /** Fake store: counts claims, optionally parks the poll loop on a gate. */
    private class GateStore(
        private val gate: CompletableDeferred<Unit>? = null,
        private val holdNonCancellable: Boolean = false,
    ) : WorkflowSchedulerStore {
        val claims = AtomicInteger(0)
        val enteredGate = CompletableDeferred<Unit>()
        private val latches = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

        fun latchAt(count: Int): CompletableDeferred<Unit> =
            latches.computeIfAbsent(count) { CompletableDeferred() }

        override suspend fun claimDueTicks(
            now: Instant,
            ownerId: String,
            claimDuration: Duration,
            limit: Int,
        ): List<ClaimedScheduledTick> {
            val n = claims.incrementAndGet()
            if (n == 1) enteredGate.complete(Unit)
            latches[n]?.complete(Unit)
            if (gate != null) {
                if (holdNonCancellable) {
                    withContext(NonCancellable) { gate.await() }
                } else {
                    gate.await()
                }
            }
            return emptyList()
        }

        override suspend fun upsertSchedule(schedule: ScheduleRecord) = Unit
        override suspend fun getSchedule(scheduleId: String): ScheduleRecord? = null
        override suspend fun listScheduleStatus(): List<ScheduleStatusView> = emptyList()
        override suspend fun markTickStarted(tickId: String, claimToken: String, runId: String) = Unit
        override suspend fun releaseTickClaim(tickId: String, claimToken: String) = Unit
        override suspend fun markTickCompleted(tickId: String, claimToken: String) = Unit
        override suspend fun markTickSkipped(tickId: String, claimToken: String, reason: String) = Unit
        override suspend fun markTickMisfired(tickId: String, claimToken: String, reason: String) = Unit
        override suspend fun scheduleDelayWakeup(runId: String, stepId: String, resumeAt: Instant) = Unit
        override suspend fun claimDueDelayWakeups(
            now: Instant,
            ownerId: String,
            claimDuration: Duration,
            limit: Int,
        ): List<ClaimedDelayWakeup> = emptyList()
        override suspend fun releaseDelayWakeupClaim(runId: String, stepId: String, claimToken: String) = Unit
        override suspend fun markDelayWakeupCompleted(runId: String, stepId: String, claimToken: String) = Unit
    }

    @Test
    fun `P0-A default scope is owned and cancelled on close`() {
        val store = GateStore()
        // No scope argument: the constructor default creates the timer-owned scope.
        val timer = ScheduledWorkflowTimer(store = store, pollInterval = Duration.ofMillis(1))
        val child = timer.start()
        val root = child.parent!!

        timer.close()
        runBlocking { child.join() }

        assertThat(child.isCancelled).isTrue()
        assertThat(child.isCompleted).isTrue()
        assertThat(root.isCancelled).withFailMessage("timer-owned default root must be cancelled by close()").isTrue()
    }

    @Test
    fun `P0-B supplied scope is borrowed and never cancelled`() {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = GateStore()
        val timer = timer(store, scope = callerScope)
        val child = timer.start()
        val root = callerScope.coroutineContext[Job]!!

        timer.close()
        runBlocking { child.join() }

        assertThat(child.isCancelled).isTrue()
        assertThat(root.isCancelled).withFailMessage("caller-supplied root must survive close()").isFalse()
        val probe = callerScope.launch { 42 }
        assertThat(runBlocking { probe.join(); probe.isCompleted }).isTrue()
        callerScope.cancel()
    }

    @Test
    fun `P0-C stop is restartable and generations do not overlap`() {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = GateStore()
        val timer = timer(store, scope = callerScope)
        val a = timer.start()
        runBlocking { store.enteredGate.await() }

        runBlocking { timer.stop() }
        assertThat(runBlocking { a.join(); a.isCompleted }).isTrue()
        assertThat(a.isCancelled).isTrue()

        val claimsAfterA = store.claims.get()
        val bFirstClaim = store.latchAt(claimsAfterA + 1) // register BEFORE B starts
        val b = timer.start()
        runBlocking { bFirstClaim.await() } // B's first poll executed

        assertThat(b).isNotSameAs(a)
        assertThat(b.isActive).isTrue()
        timer.close()
        callerScope.cancel()
    }

    @Test
    fun `P0-D duplicate start creates no orphan child`() {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = GateStore()
        val timer = timer(store, scope = callerScope)
        val a = timer.start()
        val root = callerScope.coroutineContext[Job]!!

        assertThatThrownBy { timer.start() }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(runBlocking { root.children.count() })
            .withFailMessage("rejected second start must create zero additional child jobs")
            .isEqualTo(1)
        assertThat(a.isActive).isTrue()
        timer.close()
        callerScope.cancel()
    }

    @Test
    fun `P0-E start while stop is joining cannot overlap`() {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val store = GateStore(gate = gate, holdNonCancellable = true)
        val timer = timer(store, scope = callerScope)
        val a = timer.start()
        val root = callerScope.coroutineContext[Job]!!
        runBlocking {
            store.enteredGate.await() // A parked inside a non-cancellable claim

            val stopJob = launch { timer.stop() }
            // stop() has claimed STOPPING and requested cancellation; A cannot
            // complete until the gate opens, so stop() is mid-join here.
            withTimeout(10_000) { while (!a.isCancelled) yield() }

            try {
                assertThatThrownBy { timer.start() }
                    .isInstanceOf(IllegalStateException::class.java)
                assertThat(root.children.count())
                    .withFailMessage("start while STOPPING must create zero new child jobs")
                    .isEqualTo(1)
            } finally {
                gate.complete(Unit)
            }
            stopJob.join()
        }
        val b = timer.start()
        assertThat(b.isActive).isTrue()
        timer.close()
        callerScope.cancel()
    }

    @Test
    fun `P0-F close racing start cannot resurrect (both serializations)`() {
        // Serialization 1: start owns a child, then close cancels it.
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val store = GateStore(gate = gate)
        val timer = timer(store, scope = callerScope)
        val child = timer.start()
        runBlocking { store.enteredGate.await() }

        timer.close()
        // Await the cancelled child's completion so its completion handler has
        // deterministically fired before we probe terminality (the handler must
        // never rewrite CLOSED back to a restartable state).
        runBlocking { child.join() }
        assertThat(child.isCancelled).isTrue()
        assertThatThrownBy { timer.start() }
            .isInstanceOf(IllegalStateException::class.java)
        gate.complete(Unit)
        callerScope.cancel()

        // Serialization 2: close wins, then start is rejected.
        val store2 = GateStore()
        val callerScope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val timer2 = timer(store2, scope = callerScope2)
        timer2.close()
        assertThatThrownBy { timer2.start() }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(callerScope2.coroutineContext[Job]!!.children.count()).isEqualTo(0)
        callerScope2.cancel()
    }

    @Test
    fun `P0-G start after close fails closed`() {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val timer = timer(GateStore(), scope = callerScope)
        val root = callerScope.coroutineContext[Job]!!

        timer.close()

        assertThatThrownBy { timer.start() }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(runBlocking { root.children.count() }).isEqualTo(0)
        callerScope.cancel()
    }

    @Test
    fun `P0-H close is idempotent`() {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val timer = timer(GateStore(), scope = callerScope)
        timer.start()
        timer.close()
        timer.close()
        timer.close()
        assertThat(callerScope.coroutineContext[Job]!!.isActive).isTrue()
        assertThat(runBlocking { callerScope.launch { 1 }.join(); callerScope.coroutineContext[Job]!!.isActive }).isTrue()
        callerScope.cancel()
    }

    @Test
    fun `P0-I externally cancelled returned Job releases only its own generation`() {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = GateStore()
        val timer = timer(store, scope = callerScope)
        val a = timer.start()
        runBlocking { store.enteredGate.await() }

        runBlocking { a.cancel(); a.join() } // external cancellation

        val b = timer.start() // must not be stuck in a false RUNNING state
        assertThat(b.isActive).isTrue()
        assertThat(b).isNotSameAs(a)

        timer.close()
        runBlocking { b.join() }
        assertThat(b.isCancelled).isTrue() // B's ownership was not cleared by A's completion
        callerScope.cancel()
    }

    @Test
    fun `P0-J cancelled stop caller never wedges the owner`() {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val store = GateStore(gate = gate, holdNonCancellable = true)
        val timer = timer(store, scope = callerScope)
        val a = timer.start()
        val root = callerScope.coroutineContext[Job]!!
        runBlocking {
            store.enteredGate.await() // A parked in a non-cancellable claim

            val stopJob = launch { timer.stop() }
            withTimeout(10_000) { while (!a.isCancelled) yield() } // stop claimed + cancelled A
            stopJob.cancelAndJoin() // cancel the stop caller mid-join

            // The gate is still closed, so A is NOT completed. Publishing
            // STOPPED here (premature) would let start() create an overlapping
            // generation while A is still alive — it must be rejected.
            assertThatThrownBy { timer.start() }
                .isInstanceOf(IllegalStateException::class.java)
        }
        // A completes when the gate opens; its completion must restore STOPPED
        // (the cancelled stop() caller cannot complete the transition itself).
        gate.complete(Unit)
        runBlocking { a.join() }

        val b = timer.start()
        assertThat(b.isActive).isTrue()
        assertThat(runBlocking { root.children.count() }).isEqualTo(1)
        timer.close()
        callerScope.cancel()
    }
}
