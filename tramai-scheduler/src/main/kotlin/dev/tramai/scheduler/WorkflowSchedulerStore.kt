package dev.tramai.scheduler

import dev.tramai.orchestration.WorkflowDelayWakeupScheduler
import dev.tramai.orchestration.WorkflowScheduleDefinition
import java.time.Duration
import java.time.Instant

/**
 * Durable schedule registration.
 *
 * The [skipCalendar] and [businessHoursOnly] defaults mirror [CronSchedule]
 * fields for convenience. Non-cron schedule implementations cannot expose
 * those cron-specific policies through the default cast, so callers must pass
 * explicit values if they need store-level metadata for another schedule kind.
 */
data class ScheduleRecord(
    val scheduleId: String,
    val workflowName: String,
    val schedule: WorkflowScheduleDefinition,
    val nextFireAt: Instant,
    val enabled: Boolean = true,
    val skipCalendar: List<CalendarRule> = (schedule as? CronSchedule)?.skipCalendar ?: emptyList(),
    val businessHoursOnly: Boolean = (schedule as? CronSchedule)?.businessHoursOnly ?: false,
)

data class ClaimedScheduledTick(
    val tickId: String,
    val scheduleId: String,
    val workflowName: String,
    val scheduledFireAt: Instant,
    val claimToken: String,
    val claimExpiresAt: Instant,
)

data class ClaimedDelayWakeup(
    val runId: String,
    val stepId: String,
    val resumeAt: Instant,
    val claimToken: String,
    val claimExpiresAt: Instant,
)

interface WorkflowSchedulerStore : WorkflowDelayWakeupScheduler {
    suspend fun upsertSchedule(schedule: ScheduleRecord)
    suspend fun getSchedule(scheduleId: String): ScheduleRecord?
    suspend fun claimDueTicks(
        now: Instant,
        ownerId: String,
        claimDuration: Duration,
        limit: Int,
    ): List<ClaimedScheduledTick>
    suspend fun markTickStarted(
        tickId: String,
        claimToken: String,
        runId: String,
    )
    suspend fun releaseTickClaim(
        tickId: String,
        claimToken: String,
    )
    suspend fun markTickCompleted(
        tickId: String,
        claimToken: String,
    )
    suspend fun markTickSkipped(
        tickId: String,
        claimToken: String,
        reason: String,
    )
    suspend fun markTickMisfired(
        tickId: String,
        claimToken: String,
        reason: String,
    )
    override suspend fun scheduleDelayWakeup(
        runId: String,
        stepId: String,
        resumeAt: Instant,
    )
    suspend fun claimDueDelayWakeups(
        now: Instant,
        ownerId: String,
        claimDuration: Duration,
        limit: Int,
    ): List<ClaimedDelayWakeup>
    suspend fun releaseDelayWakeupClaim(
        runId: String,
        stepId: String,
        claimToken: String,
    )
    suspend fun markDelayWakeupCompleted(
        runId: String,
        stepId: String,
        claimToken: String,
    )
}
