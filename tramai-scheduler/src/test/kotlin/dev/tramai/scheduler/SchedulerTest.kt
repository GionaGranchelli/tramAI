package dev.tramai.scheduler

import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import dev.tramai.orchestration.WorkflowScheduleDefinition
import dev.tramai.orchestration.workflow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class SchedulerTest {
    @Test
    fun `cron schedule fires at nine on monday`() {
        val schedule = at("0 9 * * 1", ZoneId.of("UTC"))

        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T10:15:00Z")))
            .isEqualTo(Instant.parse("2026-05-04T09:00:00Z"))
    }

    @Test
    fun `cron schedule supports edge second alignment`() {
        val schedule = at("*/5 * * * * *", ZoneId.of("UTC"))

        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T09:00:04Z")))
            .isEqualTo(Instant.parse("2026-05-03T09:00:05Z"))
        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T09:00:05Z")))
            .isEqualTo(Instant.parse("2026-05-03T09:00:10Z"))
    }

    @Test
    fun `invalid cron expressions are rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy { at("0 25 * * 1", ZoneId.of("UTC")) }
            .withMessageContaining("hours")
    }

    @Test
    fun `workflow builder validates assigned schedule`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                workflow<Unit>("invalid-schedule") {
                    schedule = InvalidSchedule
                }.build { Unit }
            }
            .withMessageContaining("invalid test schedule")
    }

    @Test
    fun `timer emits scheduled tick and runs due workflow`() {
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-05-03T09:00:00Z"))
            val store = InMemoryWorkflowSchedulerStore()
            val observer = RecordingSchedulerObserver()
            val workflow = workflow<CounterState>("scheduled-counter") {
                schedule = at("*/5 * * * * *", ZoneId.of("UTC"))
                localStep("increment") { state, _ -> state.copy(value = state.value + 1) }
            }.build { it }
            val timer = ScheduledWorkflowTimer(
                store = store,
                clock = clock,
                pollInterval = Duration.ofMillis(10),
            )

            timer.register(
                workflow = workflow,
                initialState = { CounterState(0) },
                observer = observer,
            )
            clock.instant = Instant.parse("2026-05-03T09:00:05Z")
            timer.pollOnce()

            assertThat(observer.scheduledTicks).containsExactly(Instant.parse("2026-05-03T09:00:05Z"))
            assertThat(observer.completedWorkflows).containsExactly("scheduled-counter")
        }
    }

    @Test
    fun `timer emits missed tick when threshold is exceeded`() {
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-05-03T09:00:00Z"))
            val store = InMemoryWorkflowSchedulerStore()
            val observer = RecordingSchedulerObserver()
            val workflow = workflow<Unit>("missed-counter") {
                schedule = at("*/5 * * * * *", ZoneId.of("UTC"))
            }.build { Unit }
            val timer = ScheduledWorkflowTimer(
                store = store,
                clock = clock,
                misfireThreshold = Duration.ofSeconds(1),
            )

            timer.register(
                workflow = workflow,
                initialState = { Unit },
                observer = observer,
            )
            clock.instant = Instant.parse("2026-05-03T09:00:07Z")
            timer.pollOnce()

            assertThat(observer.missedTicks).containsExactly(Instant.parse("2026-05-03T09:00:05Z"))
            assertThat(observer.completedWorkflows).isEmpty()
        }
    }

    @Test
    fun `timer emits skipped tick for unregistered due workflow`() {
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-05-03T09:00:05Z"))
            val store = InMemoryWorkflowSchedulerStore()
            val observer = RecordingSchedulerObserver()
            val schedule = at("*/5 * * * * *", ZoneId.of("UTC"))
            store.upsertSchedule(
                ScheduleRecord(
                    scheduleId = "workflow:unregistered",
                    workflowName = "unregistered",
                    schedule = schedule,
                    nextFireAt = Instant.parse("2026-05-03T09:00:05Z"),
                ),
            )
            val timer = ScheduledWorkflowTimer(
                store = store,
                clock = clock,
                observer = observer,
            )

            timer.pollOnce()

            assertThat(observer.skippedTicks).containsExactly(Instant.parse("2026-05-03T09:00:05Z"))
        }
    }

    @Test
    fun `timer stops gracefully`() {
        runBlocking {
            val timer = ScheduledWorkflowTimer(
                store = InMemoryWorkflowSchedulerStore(),
                clock = MutableClock(Instant.parse("2026-05-03T09:00:00Z")),
                pollInterval = Duration.ofMillis(10),
            )

            val job = timer.start()
            timer.stop()

            assertThat(job.isCancelled).isTrue()
        }
    }

    private data class CounterState(val value: Int)

    private object InvalidSchedule : WorkflowScheduleDefinition {
        override val kind: String = "invalid"
        override val expression: String = "invalid"
        override val zoneId: ZoneId = ZoneId.of("UTC")
        override fun validate() {
            throw IllegalArgumentException("invalid test schedule")
        }
    }

    private class MutableClock(
        var instant: Instant,
        private val zoneId: ZoneId = ZoneId.of("UTC"),
    ) : Clock() {
        override fun instant(): Instant = instant
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
    }

    private class RecordingSchedulerObserver : WorkflowObserver {
        val scheduledTicks = mutableListOf<Instant>()
        val skippedTicks = mutableListOf<Instant>()
        val missedTicks = mutableListOf<Instant>()
        val completedWorkflows = mutableListOf<String>()

        override fun onScheduledTick(
            workflowName: String,
            scheduledFireAt: Instant,
            context: WorkflowContext,
        ) {
            scheduledTicks += scheduledFireAt
        }

        override fun onMissedTick(
            workflowName: String,
            scheduledFireAt: Instant,
            reason: String,
            context: WorkflowContext,
        ) {
            missedTicks += scheduledFireAt
        }

        override fun onSkippedTick(
            workflowName: String,
            scheduledFireAt: Instant,
            reason: String,
            context: WorkflowContext,
        ) {
            skippedTicks += scheduledFireAt
        }

        override fun onWorkflowCompleted(
            workflowName: String,
            context: WorkflowContext,
        ) {
            completedWorkflows += workflowName
        }
    }
}
