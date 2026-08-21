@file:OptIn(ExperimentalTramaiInternalApi::class)
package dev.tramai.scheduler

import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.orchestration.InMemoryWorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.orchestration.workflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Epic 5.3 isolation wiring: ScheduledWorkflowTimer wraps its observer at the
 * boundary (constructor and per-registration), so a throwing WorkflowObserver
 * can never prevent the durable tick transitions (markTickSkipped /
 * markTickMisfired / markTickStarted / releaseDelayWakeupClaim) and can never
 * terminate the poll loop.
 */
class SchedulerIsolationTest {
    @Test
    fun `throwing constructor observer cannot prevent durable skipped tick transition`() {
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-05-03T09:00:05Z"))
            val store = InMemoryWorkflowSchedulerStore()
            store.upsertSchedule(
                ScheduleRecord(
                    scheduleId = "workflow:isolation-unregistered",
                    workflowName = "isolation-unregistered",
                    schedule = at("*/5 * * * * *", ZoneId.of("UTC")),
                    nextFireAt = Instant.parse("2026-05-03T09:00:05Z"),
                ),
            )
            val timer = ScheduledWorkflowTimer(
                store = store,
                clock = clock,
                observer = ThrowingWorkflowObserver(),
            )

            timer.pollOnce()

            val status = store.listScheduleStatus().single()
            assertThat(status.lastRunStatus).isEqualTo("skipped")
            assertThat(status.lastTick).isEqualTo(Instant.parse("2026-05-03T09:00:05Z"))
        }
    }

    @Test
    fun `throwing constructor observer cannot prevent durable misfired tick transition`() {
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-05-03T09:00:00Z"))
            val store = InMemoryWorkflowSchedulerStore()
            val workflow = workflow<Unit>("isolation-misfired") {
                schedule = at("*/5 * * * * *", ZoneId.of("UTC"))
            }.build { Unit }
            val timer = ScheduledWorkflowTimer(
                store = store,
                clock = clock,
                misfireThreshold = Duration.ofSeconds(1),
                observer = ThrowingWorkflowObserver(),
            )
            timer.register(workflow = workflow, initialState = { Unit })

            clock.instant = Instant.parse("2026-05-03T09:00:07Z")
            timer.pollOnce()

            val status = store.listScheduleStatus().single()
            assertThat(status.lastRunStatus).isEqualTo("misfired")
            assertThat(status.misfireCount).isEqualTo(1)
        }
    }

    @Test
    fun `throwing constructor observer cannot prevent durable started tick transition`() {
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-05-03T09:00:00Z"))
            val schedulerStore = InMemoryWorkflowSchedulerStore()
            val checkpointStore = InMemoryWorkflowCheckpointStore()
            val workflow = workflow<CounterState>("isolation-started") {
                schedule = at("*/5 * * * * *", ZoneId.of("UTC"))
                delayStep("pause", 5, TimeUnit.SECONDS)
            }.build(clock = clock) { it.value }
            val persistence = WorkflowPersistence(
                checkpointStore = checkpointStore,
                stateCodec = CounterStateCodec,
                delayWakeupScheduler = schedulerStore,
            )
            val timer = ScheduledWorkflowTimer(
                store = schedulerStore,
                clock = clock,
                observer = ThrowingWorkflowObserver(),
            )
            timer.register(
                workflow = workflow,
                initialState = { CounterState(0) },
                persistence = persistence,
            )

            clock.instant = Instant.parse("2026-05-03T09:00:05Z")
            timer.pollOnce()

            // The tick is durably started and completed even though the
            // throwing observer fired first, and the delay wakeup was durably
            // scheduled so the suspended workflow can resume.
            val status = schedulerStore.listScheduleStatus().single()
            assertThat(status.lastRunStatus).isEqualTo("completed")
            val runId = status.lastRunId!!
            assertThat(
                schedulerStore.claimDueDelayWakeups(
                    now = Instant.parse("2026-05-03T09:00:10Z"),
                    ownerId = "owner-2",
                    claimDuration = Duration.ofSeconds(30),
                    limit = 10,
                ).map { it.runId },
            ).containsExactly(runId)
        }
    }

    @Test
    fun `throwing constructor observer cannot prevent durable delay wakeup claim release`() {
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-05-03T09:00:00Z"))
            val schedulerStore = InMemoryWorkflowSchedulerStore()
            val checkpointStore = InMemoryWorkflowCheckpointStore()
            val workflow = workflow<CounterState>("isolation-wakeup-holder") {
                schedule = at("5 0 9 * * *", ZoneId.of("UTC"))
                delayStep("pause", 5, TimeUnit.SECONDS)
            }.build(clock = clock) { it.value }
            val persistence = WorkflowPersistence(
                checkpointStore = checkpointStore,
                stateCodec = CounterStateCodec,
                delayWakeupScheduler = schedulerStore,
            )
            val timer = ScheduledWorkflowTimer(
                store = schedulerStore,
                clock = clock,
                observer = ThrowingWorkflowObserver(),
            )
            timer.register(
                workflow = workflow,
                initialState = { CounterState(0) },
                persistence = persistence,
            )
            clock.instant = Instant.parse("2026-05-03T09:00:05Z")
            timer.pollOnce()
            schedulerStore.scheduleDelayWakeup(
                runId = "isolation-unknown",
                stepId = "pause",
                resumeAt = Instant.parse("2026-05-03T09:00:10Z"),
            )

            clock.instant = Instant.parse("2026-05-03T09:00:10Z")
            timer.pollOnce()
            timer.pollOnce()

            // onWorkflowEvent threw before releaseDelayWakeupClaim; the wakeup
            // must be released (claimable again), not stuck in CLAIMED.
            val reclaimed = schedulerStore.claimDueDelayWakeups(
                now = Instant.parse("2026-05-03T09:00:10Z"),
                ownerId = "owner-2",
                claimDuration = Duration.ofSeconds(30),
                limit = 10,
            )
            assertThat(reclaimed.map { it.runId }).contains("isolation-unknown")
        }
    }

    @Test
    fun `throwing registered observer cannot prevent durable tick completion`() {
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-05-03T09:00:00Z"))
            val store = InMemoryWorkflowSchedulerStore()
            val workflow = workflow<CounterState>("isolation-registered-observer") {
                schedule = at("*/5 * * * * *", ZoneId.of("UTC"))
                localStep("increment") { state, _ -> state.copy(value = state.value + 1) }
            }.build { it }
            val timer = ScheduledWorkflowTimer(store = store, clock = clock)
            timer.register(
                workflow = workflow,
                initialState = { CounterState(0) },
                observer = ThrowingWorkflowObserver(),
            )

            clock.instant = Instant.parse("2026-05-03T09:00:05Z")
            timer.pollOnce()

            val status = store.listScheduleStatus().single()
            assertThat(status.lastRunStatus).isEqualTo("completed")
            assertThat(status.lastRunId).isNotBlank()
        }
    }

    @Test
    fun `throwing registered observer cannot prevent delay wakeup completion`() {
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-05-03T09:00:00Z"))
            val schedulerStore = InMemoryWorkflowSchedulerStore()
            val checkpointStore = InMemoryWorkflowCheckpointStore()
            val workflow = workflow<CounterState>("isolation-wakeup-observer") {
                schedule = at("5 0 9 * * *", ZoneId.of("UTC"))
                delayStep("pause", 5, TimeUnit.SECONDS)
            }.build(clock = clock) { it.value }
            val persistence = WorkflowPersistence(
                checkpointStore = checkpointStore,
                stateCodec = CounterStateCodec,
                delayWakeupScheduler = schedulerStore,
            )
            val timer = ScheduledWorkflowTimer(store = schedulerStore, clock = clock)
            timer.register(
                workflow = workflow,
                initialState = { CounterState(0) },
                observer = ThrowingWorkflowObserver(),
                persistence = persistence,
            )
            clock.instant = Instant.parse("2026-05-03T09:00:05Z")
            timer.pollOnce()

            clock.instant = Instant.parse("2026-05-03T09:00:10Z")
            timer.pollOnce()
            timer.pollOnce()

            // onWorkflowCompleted threw inside resume; the wakeup must still be
            // durably completed (removed from the store), not stuck CLAIMED.
            assertThat(
                schedulerStore.claimDueDelayWakeups(
                    now = Instant.parse("2026-05-03T09:00:10Z"),
                    ownerId = "owner-2",
                    claimDuration = Duration.ofSeconds(30),
                    limit = 10,
                ),
            ).isEmpty()
        }
    }

    @Test
    fun `poll loop survives a throwing observer`() {
        runBlocking {
            val countingStore = CountingSchedulerStore(InMemoryWorkflowSchedulerStore())
            val workflow = workflow<CounterState>("isolation-loop") {
                schedule = at("* * * * * *", ZoneId.of("UTC"))
                localStep("increment") { state, _ -> state.copy(value = state.value + 1) }
            }.build { it }
            val timer = ScheduledWorkflowTimer(
                store = countingStore,
                clock = Clock.systemUTC(),
                pollInterval = Duration.ofMillis(20),
                observer = ThrowingWorkflowObserver(),
            )
            timer.register(
                workflow = workflow,
                initialState = { CounterState(0) },
                observer = ThrowingWorkflowObserver(),
            )

            val job = timer.start()
            try {
                withTimeout(5_000) {
                    while (countingStore.completedTicks.get() < 2) {
                        delay(25)
                    }
                }
                assertThat(job.isActive).isTrue()
                assertThat(countingStore.completedTicks.get()).isGreaterThanOrEqualTo(2)
            } finally {
                timer.stop()
            }
        }
    }

    private data class CounterState(val value: Int)

    private object CounterStateCodec : WorkflowStateCodec<CounterState> {
        override fun encode(state: CounterState): String = state.value.toString()
        override fun decode(payload: String): CounterState = CounterState(payload.toInt())
    }

    private class MutableClock(
        var instant: Instant,
        private val zoneId: ZoneId = ZoneId.of("UTC"),
    ) : Clock() {
        override fun instant(): Instant = instant
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
    }

    private class ThrowingWorkflowObserver : WorkflowObserver {
        override fun onWorkflowStarted(workflowName: String, context: WorkflowContext) =
            throw IllegalStateException("throwing observer: onWorkflowStarted")

        override fun onWorkflowEvent(
            workflowName: String,
            name: String,
            attributes: Map<String, Any?>,
            context: WorkflowContext,
        ) = throw IllegalStateException("throwing observer: onWorkflowEvent")

        override fun onStepStarted(workflowName: String, stepName: String, context: WorkflowContext) =
            throw IllegalStateException("throwing observer: onStepStarted")

        override fun onStepCompleted(workflowName: String, stepName: String, context: WorkflowContext) =
            throw IllegalStateException("throwing observer: onStepCompleted")

        override fun onStepFailed(workflowName: String, stepName: String, error: Throwable, context: WorkflowContext) =
            throw IllegalStateException("throwing observer: onStepFailed")

        override fun onWorkflowCompleted(workflowName: String, context: WorkflowContext) =
            throw IllegalStateException("throwing observer: onWorkflowCompleted")

        override fun onWorkflowFailed(workflowName: String, error: Throwable, context: WorkflowContext) =
            throw IllegalStateException("throwing observer: onWorkflowFailed")

        override fun onScheduledTick(workflowName: String, scheduledFireAt: Instant, context: WorkflowContext) =
            throw IllegalStateException("throwing observer: onScheduledTick")

        override fun onSkippedTick(
            workflowName: String,
            scheduledFireAt: Instant,
            reason: String,
            context: WorkflowContext,
        ) = throw IllegalStateException("throwing observer: onSkippedTick")

        override fun onMissedTick(
            workflowName: String,
            scheduledFireAt: Instant,
            reason: String,
            context: WorkflowContext,
        ) = throw IllegalStateException("throwing observer: onMissedTick")
    }

    private class CountingSchedulerStore(
        private val delegate: WorkflowSchedulerStore,
    ) : WorkflowSchedulerStore by delegate {
        val completedTicks = AtomicInteger()

        override suspend fun markTickCompleted(tickId: String, claimToken: String) {
            delegate.markTickCompleted(tickId, claimToken)
            completedTicks.incrementAndGet()
        }
    }
}
