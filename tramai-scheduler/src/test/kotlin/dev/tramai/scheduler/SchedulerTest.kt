package dev.tramai.scheduler

import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.InMemoryWorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowObserver
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowScheduleDefinition
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.orchestration.workflow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

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
    fun `cron schedule steps from explicit value through field maximum`() {
        val schedule = at("1/5 * * * *", ZoneId.of("UTC"))

        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T09:00:00Z")))
            .isEqualTo(Instant.parse("2026-05-03T09:01:00Z"))
        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T09:01:00Z")))
            .isEqualTo(Instant.parse("2026-05-03T09:06:00Z"))
        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T09:51:00Z")))
            .isEqualTo(Instant.parse("2026-05-03T09:56:00Z"))
    }

    @Test
    fun `invalid cron expressions are rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy { at("0 25 * * 1", ZoneId.of("UTC")) }
            .withMessageContaining("hours")
    }

    @Test
    fun `cron schedule adjusts for timezone correctly`() {
        val schedule = at("0 9 * * 1", zone = "Europe/Rome")

        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T10:15:00Z")))
            .isEqualTo(Instant.parse("2026-05-04T07:00:00Z"))
    }

    @Test
    fun `business-hours-only mode skips a two AM tick and fires at nine AM next business day`() {
        val skipped = mutableListOf<Pair<Instant, String>>()
        val schedule = at(
            expression = "0 2 * * *",
            zoneId = ZoneId.of("UTC"),
            businessHoursOnly = true,
        )

        val next = schedule.nextFireAfter(Instant.parse("2026-05-08T18:00:00Z")) { fireAt, reason ->
            skipped += fireAt to reason
        }

        assertThat(next).isEqualTo(Instant.parse("2026-05-11T09:00:00Z"))
        assertThat(skipped).containsExactly(Instant.parse("2026-05-09T02:00:00Z") to "business_hours")
    }

    @Test
    fun `fixed date calendar rule skips Christmas`() {
        val skipped = mutableListOf<Pair<Instant, String>>()
        val schedule = at(
            expression = "0 9 * * *",
            zoneId = ZoneId.of("UTC"),
            skipCalendar = listOf(CalendarRule.FixedDate(month = 12, dayOfMonth = 25)),
        )

        val next = schedule.nextFireAfter(Instant.parse("2026-12-24T10:00:00Z")) { fireAt, reason ->
            skipped += fireAt to reason
        }

        assertThat(next).isEqualTo(Instant.parse("2026-12-26T09:00:00Z"))
        assertThat(skipped).containsExactly(
            Instant.parse("2026-12-25T09:00:00Z") to "calendar_skip:fixed_date:12-25",
        )
    }

    @Test
    fun `dailyAt creates correct cron expression`() {
        val schedule = dailyAt(hour = 9, minute = 30, second = 15, zoneId = ZoneId.of("UTC"))

        assertThat(schedule.expression).isEqualTo("15 30 9 * * *")
        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T09:30:14Z")))
            .isEqualTo(Instant.parse("2026-05-03T09:30:15Z"))
    }

    @Test
    fun `every five minutes fires every five minutes`() {
        val schedule = every(5, ChronoUnit.MINUTES, ZoneId.of("UTC"))

        assertThat(schedule.expression).isEqualTo("0 */5 * * * *")
        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T09:04:59Z")))
            .isEqualTo(Instant.parse("2026-05-03T09:05:00Z"))
        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T09:05:00Z")))
            .isEqualTo(Instant.parse("2026-05-03T09:10:00Z"))
    }

    @Test
    fun `every thirty seconds fires every thirty seconds`() {
        val schedule = every(30, ChronoUnit.SECONDS, ZoneId.of("UTC"))

        assertThat(schedule.expression).isEqualTo("*/30 * * * * *")
        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T09:00:29Z")))
            .isEqualTo(Instant.parse("2026-05-03T09:00:30Z"))
        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T09:00:30Z")))
            .isEqualTo(Instant.parse("2026-05-03T09:01:00Z"))
    }

    @Test
    fun `every two hours fires every two hours`() {
        val schedule = every(2, ChronoUnit.HOURS, ZoneId.of("UTC"))

        assertThat(schedule.expression).isEqualTo("0 0 */2 * * *")
        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T09:59:59Z")))
            .isEqualTo(Instant.parse("2026-05-03T10:00:00Z"))
        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T10:00:00Z")))
            .isEqualTo(Instant.parse("2026-05-03T12:00:00Z"))
    }

    @Test
    fun `every one day fires daily at midnight`() {
        val schedule = every(1, ChronoUnit.DAYS, ZoneId.of("UTC"))

        assertThat(schedule.expression).isEqualTo("0 0 0 * * *")
        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T23:59:59Z")))
            .isEqualTo(Instant.parse("2026-05-04T00:00:00Z"))
    }

    @Test
    fun `every two days uses day-of-month cron stepping at month boundaries`() {
        val schedule = every(2, ChronoUnit.DAYS, ZoneId.of("UTC"))

        assertThat(schedule.expression).isEqualTo("0 0 0 */2 * *")
        assertThat(schedule.nextFireAfter(Instant.parse("2026-01-29T00:00:00Z")))
            .isEqualTo(Instant.parse("2026-01-31T00:00:00Z"))
        assertThat(schedule.nextFireAfter(Instant.parse("2026-01-31T00:00:00Z")))
            .isEqualTo(Instant.parse("2026-02-01T00:00:00Z"))
        assertThat(schedule.nextFireAfter(Instant.parse("2026-02-01T00:00:00Z")))
            .isEqualTo(Instant.parse("2026-02-03T00:00:00Z"))
    }

    @Test
    fun `every rejects unsupported chrono unit`() {
        assertThatIllegalArgumentException()
            .isThrownBy { every(1, ChronoUnit.WEEKS, ZoneId.of("UTC")) }
            .withMessageContaining("not supported")
    }

    @Test
    fun `every rejects zero amount`() {
        assertThatIllegalArgumentException()
            .isThrownBy { every(0, ChronoUnit.MINUTES, ZoneId.of("UTC")) }
            .withMessageContaining("at least 1")
    }

    @Test
    fun `every rejects amount that cannot fit cron step integer`() {
        val schedule = every(Int.MAX_VALUE.toLong(), ChronoUnit.SECONDS, ZoneId.of("UTC"))

        assertThat(schedule.expression).isEqualTo("*/${Int.MAX_VALUE} * * * * *")
        assertThatIllegalArgumentException()
            .isThrownBy { every(Int.MAX_VALUE.toLong() + 1, ChronoUnit.SECONDS, ZoneId.of("UTC")) }
            .withMessageContaining("must fit within Int")
    }

    @Test
    fun `every respects timezone`() {
        val schedule = every(1, ChronoUnit.DAYS, zone = "Europe/Rome")

        assertThat(schedule.nextFireAfter(Instant.parse("2026-05-03T21:59:59Z")))
            .isEqualTo(Instant.parse("2026-05-03T22:00:00Z"))
    }

    @Test
    fun `every hourly crosses spring DST gap using timezone rules`() {
        val schedule = every(1, ChronoUnit.HOURS, zone = "Europe/Rome")

        assertThat(schedule.nextFireAfter(Instant.parse("2026-03-29T00:59:59Z")))
            .isEqualTo(Instant.parse("2026-03-29T01:00:00Z"))
    }

    @Test
    fun `every hourly crosses fall DST overlap using timezone rules`() {
        val schedule = every(1, ChronoUnit.HOURS, zone = "Europe/Rome")

        assertThat(schedule.nextFireAfter(Instant.parse("2026-10-25T00:59:59Z")))
            .isEqualTo(Instant.parse("2026-10-25T01:00:00Z"))
    }

    @Test
    fun `invalid timezone ID rejected at build time`() {
        assertThatExceptionOfType(DateTimeException::class.java)
            .isThrownBy { at("0 9 * * 1", zone = "Not/A_Zone") }
    }

    @Test
    fun `calendar rules reject invalid dates at build time`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                at(
                    expression = "0 9 * * *",
                    zoneId = ZoneId.of("UTC"),
                    skipCalendar = listOf(CalendarRule.FixedDate(month = 2, dayOfMonth = 29)),
                )
            }
            .withMessageContaining("not valid every year")
    }

    @Test
    fun `nth weekday calendar rule skips third monday of december`() {
        val skipped = mutableListOf<Instant>()
        val schedule = at(
            expression = "0 9 * 12 1",
            zoneId = ZoneId.of("UTC"),
            skipCalendar = listOf(
                CalendarRule.NthWeekdayOfMonth(
                    month = 12,
                    nth = 3,
                    dayOfWeek = DayOfWeek.MONDAY,
                ),
            ),
        )

        val next = schedule.nextFireAfter(Instant.parse("2026-12-20T00:00:00Z")) { fireAt, _ ->
            skipped += fireAt
        }

        assertThat(skipped).containsExactly(Instant.parse("2026-12-21T09:00:00Z"))
        assertThat(next).isEqualTo(Instant.parse("2026-12-28T09:00:00Z"))
    }

    @Test
    fun `date range calendar rule skips inclusive range`() {
        val skipped = mutableListOf<Instant>()
        val schedule = at(
            expression = "0 9 * * *",
            zoneId = ZoneId.of("UTC"),
            skipCalendar = listOf(
                CalendarRule.DateRange(
                    startMonth = 12,
                    startDayOfMonth = 24,
                    endMonth = 12,
                    endDayOfMonth = 26,
                ),
            ),
        )

        val next = schedule.nextFireAfter(Instant.parse("2026-12-23T09:00:00Z")) { fireAt, _ ->
            skipped += fireAt
        }

        assertThat(skipped).containsExactly(
            Instant.parse("2026-12-24T09:00:00Z"),
            Instant.parse("2026-12-25T09:00:00Z"),
            Instant.parse("2026-12-26T09:00:00Z"),
        )
        assertThat(next).isEqualTo(Instant.parse("2026-12-27T09:00:00Z"))
    }

    @Test
    fun `business hour adjustment is not revalidated against cron expression`() {
        val schedule = at(
            expression = "0 2 * * *",
            zoneId = ZoneId.of("UTC"),
            businessHoursOnly = true,
        )

        val next = schedule.nextFireAfter(Instant.parse("2026-05-03T03:00:00Z"))

        assertThat(next).isEqualTo(Instant.parse("2026-05-04T09:00:00Z"))
        assertThat(schedule.matches(ZonedDateTime.ofInstant(next, ZoneId.of("UTC")))).isFalse()
    }

    @Test
    fun `business hour adjustment continues when adjusted date matches calendar rule`() {
        val skipped = mutableListOf<Pair<Instant, String>>()
        val schedule = at(
            expression = "0 2 * * *",
            zoneId = ZoneId.of("UTC"),
            skipCalendar = listOf(CalendarRule.FixedDate(month = 5, dayOfMonth = 4)),
            businessHoursOnly = true,
        )

        val next = schedule.nextFireAfter(Instant.parse("2026-05-02T03:00:00Z")) { fireAt, reason ->
            skipped += fireAt to reason
        }

        assertThat(next).isEqualTo(Instant.parse("2026-05-05T09:00:00Z"))
        assertThat(skipped).containsExactly(
            Instant.parse("2026-05-03T02:00:00Z") to "business_hours",
            Instant.parse("2026-05-04T09:00:00Z") to "calendar_skip:fixed_date:5-4",
            Instant.parse("2026-05-05T02:00:00Z") to "business_hours",
        )
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
                observer = observer,
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
    fun `timer resumes workflow from due delay wakeup`() {
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-05-03T09:00:00Z"))
            val schedulerStore = InMemoryWorkflowSchedulerStore()
            val checkpointStore = InMemoryWorkflowCheckpointStore()
            val observer = RecordingSchedulerObserver()
            val workflow = workflow<CounterState>("scheduled-delay") {
                schedule = at("5 0 9 * * *", ZoneId.of("UTC"))
                localStep("increment-before-delay") { state, _ -> state.copy(value = state.value + 1) }
                delayStep("pause", 5, TimeUnit.SECONDS)
                localStep("increment-after-delay") { state, _ -> state.copy(value = state.value + 1) }
            }.build(clock = clock) { it.value }
            val persistence = WorkflowPersistence(
                checkpointStore = checkpointStore,
                stateCodec = CounterStateCodec,
                delayWakeupScheduler = schedulerStore,
            )
            val timer = ScheduledWorkflowTimer(
                store = schedulerStore,
                clock = clock,
            )

            timer.register(
                workflow = workflow,
                initialState = { CounterState(0) },
                observer = observer,
                persistence = persistence,
            )
            clock.instant = Instant.parse("2026-05-03T09:00:05Z")
            timer.pollOnce()

            assertThat(observer.completedWorkflows).isEmpty()

            clock.instant = Instant.parse("2026-05-03T09:00:10Z")
            timer.pollOnce()

            assertThat(observer.completedWorkflows).containsExactly("scheduled-delay")
        }
    }

    @Test
    fun `timer completes delay wakeup and reports unknown claimed wakeup`() {
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-05-03T09:00:00Z"))
            val schedulerStore = InMemoryWorkflowSchedulerStore()
            val checkpointStore = InMemoryWorkflowCheckpointStore()
            val observer = RecordingSchedulerObserver()
            val workflow = workflow<CounterState>("scheduled-delay-with-unknown") {
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
                observer = observer,
            )

            timer.register(
                workflow = workflow,
                initialState = { CounterState(0) },
                observer = observer,
                persistence = persistence,
            )
            clock.instant = Instant.parse("2026-05-03T09:00:05Z")
            timer.pollOnce()
            schedulerStore.scheduleDelayWakeup(
                runId = "unknown-run",
                stepId = "pause",
                resumeAt = Instant.parse("2026-05-03T09:00:10Z"),
            )

            clock.instant = Instant.parse("2026-05-03T09:00:10Z")
            timer.pollOnce()
            timer.pollOnce()

            assertThat(observer.completedWorkflows).containsExactly("scheduled-delay-with-unknown")
            assertThat(observer.events).contains("tramai.scheduler.delay_wakeup.unregistered")
        }
    }

    @Test
    fun `in memory store reclaims expired tick and delay claims`() {
        runBlocking {
            val store = InMemoryWorkflowSchedulerStore()
            val schedule = at("*/5 * * * * *", ZoneId.of("UTC"))
            store.upsertSchedule(
                ScheduleRecord(
                    scheduleId = "workflow:reclaim",
                    workflowName = "reclaim",
                    schedule = schedule,
                    nextFireAt = Instant.parse("2026-05-03T09:00:05Z"),
                ),
            )
            val firstTickClaim = store.claimDueTicks(
                now = Instant.parse("2026-05-03T09:00:05Z"),
                ownerId = "owner-1",
                claimDuration = Duration.ofSeconds(1),
                limit = 10,
            ).single()

            assertThat(
                store.claimDueTicks(
                    now = Instant.parse("2026-05-03T09:00:05.500Z"),
                    ownerId = "owner-2",
                    claimDuration = Duration.ofSeconds(1),
                    limit = 10,
                ),
            ).isEmpty()

            val reclaimedTick = store.claimDueTicks(
                now = Instant.parse("2026-05-03T09:00:06Z"),
                ownerId = "owner-2",
                claimDuration = Duration.ofSeconds(1),
                limit = 10,
            ).single()
            assertThat(reclaimedTick.tickId).isEqualTo(firstTickClaim.tickId)
            assertThat(reclaimedTick.claimToken).isNotEqualTo(firstTickClaim.claimToken)

            store.scheduleDelayWakeup(
                runId = "run-1",
                stepId = "pause",
                resumeAt = Instant.parse("2026-05-03T09:00:05Z"),
            )
            val firstWakeupClaim = store.claimDueDelayWakeups(
                now = Instant.parse("2026-05-03T09:00:05Z"),
                ownerId = "owner-1",
                claimDuration = Duration.ofSeconds(1),
                limit = 10,
            ).single()

            assertThat(
                store.claimDueDelayWakeups(
                    now = Instant.parse("2026-05-03T09:00:05.500Z"),
                    ownerId = "owner-2",
                    claimDuration = Duration.ofSeconds(1),
                    limit = 10,
                ),
            ).isEmpty()

            val reclaimedWakeup = store.claimDueDelayWakeups(
                now = Instant.parse("2026-05-03T09:00:06Z"),
                ownerId = "owner-2",
                claimDuration = Duration.ofSeconds(1),
                limit = 10,
            ).single()
            assertThat(reclaimedWakeup.runId).isEqualTo(firstWakeupClaim.runId)
            assertThat(reclaimedWakeup.claimToken).isNotEqualTo(firstWakeupClaim.claimToken)
        }
    }

    @Test
    fun `timer releases expired tick claim before execution`() {
        runBlocking {
            val store = ExpiredTickClaimStore()
            val observer = RecordingSchedulerObserver()
            val timer = ScheduledWorkflowTimer(
                store = store,
                clock = MutableClock(Instant.parse("2026-05-03T09:00:05Z")),
                observer = observer,
            )

            timer.pollOnce()

            assertThat(store.releasedTickClaims).containsExactly("expired-tick:expired-token")
            assertThat(observer.skippedTicks).isEmpty()
            assertThat(observer.scheduledTicks).isEmpty()
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
                observer = observer,
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

    private object CounterStateCodec : WorkflowStateCodec<CounterState> {
        override fun encode(state: CounterState): String = state.value.toString()
        override fun decode(payload: String): CounterState = CounterState(payload.toInt())
    }

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
        val events = mutableListOf<String>()

        override fun onWorkflowEvent(
            workflowName: String,
            name: String,
            attributes: Map<String, Any?>,
            context: WorkflowContext,
        ) {
            events += name
        }

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

    private class ExpiredTickClaimStore : WorkflowSchedulerStore {
        val releasedTickClaims = mutableListOf<String>()

        override suspend fun upsertSchedule(schedule: ScheduleRecord) = Unit
        override suspend fun getSchedule(scheduleId: String): ScheduleRecord? = null
        override suspend fun listScheduleStatus(): List<ScheduleStatusView> = emptyList()
        override suspend fun claimDueTicks(
            now: Instant,
            ownerId: String,
            claimDuration: Duration,
            limit: Int,
        ): List<ClaimedScheduledTick> = listOf(
            ClaimedScheduledTick(
                tickId = "expired-tick",
                scheduleId = "workflow:expired",
                workflowName = "expired",
                scheduledFireAt = Instant.parse("2026-05-03T09:00:05Z"),
                claimToken = "expired-token",
                claimExpiresAt = Instant.parse("2026-05-03T09:00:04Z"),
            ),
        )

        override suspend fun markTickStarted(
            tickId: String,
            claimToken: String,
            runId: String,
        ) {
            error("Expired tick claims must not be started")
        }

        override suspend fun releaseTickClaim(
            tickId: String,
            claimToken: String,
        ) {
            releasedTickClaims += "$tickId:$claimToken"
        }

        override suspend fun markTickCompleted(
            tickId: String,
            claimToken: String,
        ) {
            error("Expired tick claims must not be completed")
        }

        override suspend fun markTickSkipped(
            tickId: String,
            claimToken: String,
            reason: String,
        ) {
            error("Expired tick claims must not be skipped as terminal")
        }

        override suspend fun markTickMisfired(
            tickId: String,
            claimToken: String,
            reason: String,
        ) {
            error("Expired tick claims must not be marked as misfired")
        }

        override suspend fun scheduleDelayWakeup(
            runId: String,
            stepId: String,
            resumeAt: Instant,
        ) = Unit

        override suspend fun claimDueDelayWakeups(
            now: Instant,
            ownerId: String,
            claimDuration: Duration,
            limit: Int,
        ): List<ClaimedDelayWakeup> = emptyList()

        override suspend fun releaseDelayWakeupClaim(
            runId: String,
            stepId: String,
            claimToken: String,
        ) = Unit

        override suspend fun markDelayWakeupCompleted(
            runId: String,
            stepId: String,
            claimToken: String,
        ) = Unit
    }
}
