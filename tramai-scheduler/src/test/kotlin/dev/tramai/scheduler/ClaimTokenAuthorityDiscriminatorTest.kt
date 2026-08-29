package dev.tramai.scheduler

import dev.tramai.orchestration.NoOpWorkflowObserver
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.3d — scheduler claim-capability authority.
 *
 * Every newly-created tick/delay-wakeup claim token must originate from the
 * single [ClaimTokenSource]; neither the in-memory nor the JDBC scheduler
 * store manufactures claim tokens itself. Sentinel injection proves the
 * exact token propagates into tick claims and delay-wakeup claims.
 */
class ClaimTokenAuthorityDiscriminatorTest {

    private val sentinelSource = ClaimTokenSource { "sentinel-claim-token" }
    private val now: Instant = Instant.parse("2026-08-29T12:00:00Z")
    private val cron = CronSchedule.parse("* * * * *")

    @Test
    fun `InMemory tick claim carries the source-issued token`() {
        val store = InMemoryWorkflowSchedulerStore(claimTokenSource = sentinelSource)
        runBlocking {
            store.upsertSchedule(ScheduleRecord(scheduleId = "s1", workflowName = "wf", schedule = cron, nextFireAt = now.minusSeconds(60)))
            val claims = store.claimDueTicks(now, ownerId = "owner", claimDuration = Duration.ofMinutes(1), limit = 10)
            assertThat(claims).hasSize(1)
            assertThat(claims[0].claimToken).isEqualTo("sentinel-claim-token")
        }
    }

    @Test
    fun `InMemory delay-wakeup claim carries the source-issued token`() {
        val store = InMemoryWorkflowSchedulerStore(claimTokenSource = sentinelSource)
        runBlocking {
            store.scheduleDelayWakeup(runId = "run-1", stepId = "step-1", resumeAt = now.minusSeconds(60))
            val claims = store.claimDueDelayWakeups(now, ownerId = "owner", claimDuration = Duration.ofMinutes(1), limit = 10)
            assertThat(claims).hasSize(1)
            assertThat(claims[0].claimToken).isEqualTo("sentinel-claim-token")
        }
    }

    @Test
    fun `Jdbc tick claim carries the source-issued token`() {
        val store = jdbcStore()
        runBlocking {
            store.upsertSchedule(ScheduleRecord(scheduleId = "s1", workflowName = "wf", schedule = cron, nextFireAt = now.minusSeconds(60)))
            val claims = store.claimDueTicks(now, ownerId = "owner", claimDuration = Duration.ofMinutes(1), limit = 10)
            assertThat(claims).hasSize(1)
            assertThat(claims[0].claimToken).isEqualTo("sentinel-claim-token")
        }
    }

    @Test
    fun `Jdbc delay-wakeup claim carries the source-issued token`() {
        val store = jdbcStore()
        runBlocking {
            store.scheduleDelayWakeup(runId = "run-1", stepId = "step-1", resumeAt = now.minusSeconds(60))
            val claims = store.claimDueDelayWakeups(now, ownerId = "owner", claimDuration = Duration.ofMinutes(1), limit = 10)
            assertThat(claims).hasSize(1)
            assertThat(claims[0].claimToken).isEqualTo("sentinel-claim-token")
        }
    }

    private fun jdbcStore(): JdbcWorkflowSchedulerStore {
        val ds = org.h2.jdbcx.JdbcDataSource()
        ds.setURL("jdbc:h2:mem:claim_token;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
        ds.user = "sa"
        ds.password = ""
        val store = JdbcWorkflowSchedulerStore(ds, NoOpWorkflowObserver, sentinelSource)
        ds.connection.use { connection ->
            store.createTableSql().forEach { sql ->
                connection.createStatement().use { statement -> statement.execute(sql) }
            }
        }
        return store
    }
}
