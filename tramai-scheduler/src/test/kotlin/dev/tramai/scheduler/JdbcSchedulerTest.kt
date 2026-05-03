package dev.tramai.scheduler

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class JdbcSchedulerTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var store: JdbcWorkflowSchedulerStore
    private lateinit var observer: RecordingRecoveryObserver

    @BeforeEach
    fun setUp() {
        observer = RecordingRecoveryObserver()
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
                maximumPoolSize = 4
            },
        )
        store = JdbcWorkflowSchedulerStore(dataSource, observer)
        dataSource.connection.use { connection ->
            store.createTableSql().forEach { sql ->
                connection.createStatement().use { statement ->
                    statement.execute(sql)
                }
            }
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM workflow_delay_wakeups")
                statement.executeUpdate("DELETE FROM workflow_schedule_ticks")
                statement.executeUpdate("DELETE FROM workflow_schedules")
            }
        }
    }

    @AfterEach
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `jdbc store claims due scheduled tick and advances schedule`() {
        runBlocking {
            val firstFireAt = Instant.parse("2026-05-03T09:00:05Z")
            store.upsertSchedule(scheduleRecord("basic", firstFireAt))

            val claim = store.claimDueTicks(
                now = firstFireAt,
                ownerId = "owner-1",
                claimDuration = Duration.ofSeconds(30),
                limit = 10,
            ).single()

            assertThat(claim.scheduleId).isEqualTo("workflow:basic")
            assertThat(claim.workflowName).isEqualTo("basic")
            assertThat(claim.scheduledFireAt).isEqualTo(firstFireAt)
            assertThat(claim.claimExpiresAt).isEqualTo(Instant.parse("2026-05-03T09:00:35Z"))
            assertThat(store.getSchedule("workflow:basic")!!.nextFireAt)
                .isEqualTo(Instant.parse("2026-05-03T09:00:10Z"))
            assertThat(rowCount("workflow_schedule_ticks")).isEqualTo(1)
        }
    }

    @Test
    fun `jdbc store reclaims expired scheduled tick`() {
        runBlocking {
            store.upsertSchedule(scheduleRecord("reclaim", Instant.parse("2026-05-03T09:00:05Z")))
            val first = store.claimDueTicks(
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

            val reclaimed = store.claimDueTicks(
                now = Instant.parse("2026-05-03T09:00:06Z"),
                ownerId = "owner-2",
                claimDuration = Duration.ofSeconds(1),
                limit = 10,
            ).single()

            assertThat(reclaimed.tickId).isEqualTo(first.tickId)
            assertThat(reclaimed.claimToken).isNotEqualTo(first.claimToken)
        }
    }

    @Test
    fun `jdbc store schedules claims and completes delay wakeup`() {
        runBlocking {
            store.scheduleDelayWakeup(
                runId = "run-1",
                stepId = "pause",
                resumeAt = Instant.parse("2026-05-03T09:00:05Z"),
            )

            val claim = store.claimDueDelayWakeups(
                now = Instant.parse("2026-05-03T09:00:05Z"),
                ownerId = "owner-1",
                claimDuration = Duration.ofSeconds(30),
                limit = 10,
            ).single()

            assertThat(claim.runId).isEqualTo("run-1")
            assertThat(claim.stepId).isEqualTo("pause")
            assertThat(claim.resumeAt).isEqualTo(Instant.parse("2026-05-03T09:00:05Z"))

            store.markDelayWakeupCompleted(claim.runId, claim.stepId, claim.claimToken)

            assertThat(statusForDelayWakeup("run-1", "pause")).isEqualTo("COMPLETED")
            assertThat(
                store.claimDueDelayWakeups(
                    now = Instant.parse("2026-05-03T09:01:00Z"),
                    ownerId = "owner-2",
                    claimDuration = Duration.ofSeconds(30),
                    limit = 10,
                ),
            ).isEmpty()
        }
    }

    @Test
    fun `jdbc store reclaims expired delay wakeup`() {
        runBlocking {
            store.scheduleDelayWakeup(
                runId = "run-2",
                stepId = "pause",
                resumeAt = Instant.parse("2026-05-03T09:00:05Z"),
            )
            val first = store.claimDueDelayWakeups(
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

            val reclaimed = store.claimDueDelayWakeups(
                now = Instant.parse("2026-05-03T09:00:06Z"),
                ownerId = "owner-2",
                claimDuration = Duration.ofSeconds(1),
                limit = 10,
            ).single()

            assertThat(reclaimed.runId).isEqualTo(first.runId)
            assertThat(reclaimed.claimToken).isNotEqualTo(first.claimToken)
        }
    }

    @Test
    fun `jdbc store releases tick and delay claims for reclaiming`() {
        runBlocking {
            store.upsertSchedule(scheduleRecord("release", Instant.parse("2026-05-03T09:00:05Z")))
            val tick = store.claimDueTicks(
                now = Instant.parse("2026-05-03T09:00:05Z"),
                ownerId = "owner-1",
                claimDuration = Duration.ofMinutes(1),
                limit = 10,
            ).single()
            store.releaseTickClaim(tick.tickId, tick.claimToken)

            val reclaimedTick = store.claimDueTicks(
                now = Instant.parse("2026-05-03T09:00:06Z"),
                ownerId = "owner-2",
                claimDuration = Duration.ofMinutes(1),
                limit = 10,
            ).single()
            assertThat(reclaimedTick.tickId).isEqualTo(tick.tickId)
            assertThat(reclaimedTick.claimToken).isNotEqualTo(tick.claimToken)

            store.scheduleDelayWakeup("run-3", "pause", Instant.parse("2026-05-03T09:00:05Z"))
            val wakeup = store.claimDueDelayWakeups(
                now = Instant.parse("2026-05-03T09:00:05Z"),
                ownerId = "owner-1",
                claimDuration = Duration.ofMinutes(1),
                limit = 10,
            ).single()
            store.releaseDelayWakeupClaim(wakeup.runId, wakeup.stepId, wakeup.claimToken)

            val reclaimedWakeup = store.claimDueDelayWakeups(
                now = Instant.parse("2026-05-03T09:00:06Z"),
                ownerId = "owner-2",
                claimDuration = Duration.ofMinutes(1),
                limit = 10,
            ).single()
            assertThat(reclaimedWakeup.runId).isEqualTo(wakeup.runId)
            assertThat(reclaimedWakeup.claimToken).isNotEqualTo(wakeup.claimToken)
        }
    }

    @Test
    fun `jdbc store marks scheduled tick lifecycle states`() {
        runBlocking {
            val completed = claimTick("completed")
            store.markTickStarted(completed.tickId, completed.claimToken, "run-completed")
            store.markTickCompleted(completed.tickId, completed.claimToken)
            assertThat(statusForTick(completed.tickId)).isEqualTo("COMPLETED")
            assertThat(workflowRunIdForTick(completed.tickId)).isEqualTo("run-completed")

            val skipped = claimTick("skipped")
            store.markTickSkipped(skipped.tickId, skipped.claimToken, "no_registration")
            assertThat(statusForTick(skipped.tickId)).isEqualTo("SKIPPED")
            assertThat(terminalReasonForTick(skipped.tickId)).isEqualTo("no_registration")

            val misfired = claimTick("misfired")
            store.markTickMisfired(misfired.tickId, misfired.claimToken, "too_late")
            assertThat(statusForTick(misfired.tickId)).isEqualTo("MISFIRED")
            assertThat(terminalReasonForTick(misfired.tickId)).isEqualTo("too_late")
        }
    }

    @Test
    fun `jdbc store startup recovery creates missed tick and advances schedule idempotently`() {
        runBlocking {
            store.upsertSchedule(
                scheduleRecord(
                    workflowName = "recover",
                    nextFireAt = Instant.parse("2026-05-03T09:00:05Z"),
                ),
            )

            val recovered = store.recover(now = Instant.parse("2026-05-03T09:00:07Z"))
            val recoveredAgain = store.recover(now = Instant.parse("2026-05-03T09:00:07Z"))

            assertThat(recovered).isEqualTo(1)
            assertThat(recoveredAgain).isEqualTo(0)
            assertThat(rowCount("workflow_schedule_ticks")).isEqualTo(1)
            assertThat(store.getSchedule("workflow:recover")!!.nextFireAt)
                .isEqualTo(Instant.parse("2026-05-03T09:00:10Z"))
            assertThat(observer.missedTicks).containsExactly(Instant.parse("2026-05-03T09:00:05Z"))

            val claim = store.claimDueTicks(
                now = Instant.parse("2026-05-03T09:00:07Z"),
                ownerId = "owner-1",
                claimDuration = Duration.ofSeconds(30),
                limit = 10,
            ).single()
            assertThat(claim.workflowName).isEqualTo("recover")
        }
    }

    @Test
    fun `jdbc store persists calendar rules and business hours mode`() {
        runBlocking {
            val schedule = at(
                expression = "0 9 * * *",
                zoneId = ZoneId.of("UTC"),
                skipCalendar = listOf(CalendarRule.FixedDate(month = 12, dayOfMonth = 25)),
                businessHoursOnly = true,
            )
            store.upsertSchedule(
                ScheduleRecord(
                    scheduleId = "workflow:calendar",
                    workflowName = "calendar",
                    schedule = schedule,
                    nextFireAt = Instant.parse("2026-12-24T09:00:00Z"),
                ),
            )

            val restored = store.getSchedule("workflow:calendar")!!
            val restoredSchedule = restored.schedule as CronSchedule

            assertThat(restored.skipCalendar).containsExactly(CalendarRule.FixedDate(month = 12, dayOfMonth = 25))
            assertThat(restored.businessHoursOnly).isTrue()
            assertThat(restoredSchedule.skipCalendar).containsExactly(CalendarRule.FixedDate(month = 12, dayOfMonth = 25))
            assertThat(restoredSchedule.businessHoursOnly).isTrue()
        }
    }

    @Test
    fun `jdbc store emits skipped tick when advancing past calendar rule`() {
        runBlocking {
            val schedule = at(
                expression = "0 9 * * *",
                zoneId = ZoneId.of("UTC"),
                skipCalendar = listOf(CalendarRule.FixedDate(month = 12, dayOfMonth = 25)),
            )
            store.upsertSchedule(
                ScheduleRecord(
                    scheduleId = "workflow:calendar-skip",
                    workflowName = "calendar-skip",
                    schedule = schedule,
                    nextFireAt = Instant.parse("2026-12-24T09:00:00Z"),
                ),
            )

            store.claimDueTicks(
                now = Instant.parse("2026-12-24T09:00:00Z"),
                ownerId = "owner-1",
                claimDuration = Duration.ofSeconds(30),
                limit = 10,
            ).single()

            assertThat(store.getSchedule("workflow:calendar-skip")!!.nextFireAt)
                .isEqualTo(Instant.parse("2026-12-26T09:00:00Z"))
            assertThat(observer.skippedTicks)
                .containsExactly(Instant.parse("2026-12-25T09:00:00Z") to "calendar_skip:fixed_date:12-25")
        }
    }

    private suspend fun claimTick(workflowName: String): ClaimedScheduledTick {
        val nextFireAt = Instant.parse("2026-05-03T09:00:05Z")
        store.upsertSchedule(scheduleRecord(workflowName, nextFireAt))
        return store.claimDueTicks(
            now = nextFireAt,
            ownerId = "owner-1",
            claimDuration = Duration.ofMinutes(1),
            limit = 10,
        ).single()
    }

    private fun scheduleRecord(
        workflowName: String,
        nextFireAt: Instant,
    ): ScheduleRecord = ScheduleRecord(
        scheduleId = "workflow:$workflowName",
        workflowName = workflowName,
        schedule = at("*/5 * * * * *", ZoneId.of("UTC")),
        nextFireAt = nextFireAt,
    )

    private fun rowCount(table: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { resultSet ->
                    resultSet.next()
                    resultSet.getInt(1)
                }
            }
        }

    private fun statusForTick(tickId: String): String? = tickColumn(tickId, "status")

    private fun workflowRunIdForTick(tickId: String): String? = tickColumn(tickId, "workflow_run_id")

    private fun terminalReasonForTick(tickId: String): String? = tickColumn(tickId, "terminal_reason")

    private fun tickColumn(
        tickId: String,
        column: String,
    ): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT $column FROM workflow_schedule_ticks WHERE tick_id = ?").use { statement ->
                statement.setString(1, tickId)
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.getString(1)
                }
            }
        }

    private fun statusForDelayWakeup(
        runId: String,
        stepId: String,
    ): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT status FROM workflow_delay_wakeups WHERE run_id = ? AND step_id = ?",
            ).use { statement ->
                statement.setString(1, runId)
                statement.setString(2, stepId)
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.getString(1)
                }
            }
        }

    private class RecordingRecoveryObserver : WorkflowObserver {
        val missedTicks = mutableListOf<Instant>()
        val skippedTicks = mutableListOf<Pair<Instant, String>>()

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
            skippedTicks += scheduledFireAt to reason
        }
    }
}
