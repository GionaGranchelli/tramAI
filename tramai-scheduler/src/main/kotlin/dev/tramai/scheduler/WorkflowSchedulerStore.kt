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

data class ScheduleStatusView(
    val scheduleId: String,
    val workflowName: String,
    val cronExpression: String,
    val nextTick: Instant?,
    val lastTick: Instant?,
    val lastRunStatus: String?,
    val lastRunId: String?,
    val misfireCount: Int,
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

/**
 * Durable binding of a governed schedule to the workload deployment it runs (0.7.1d).
 *
 * A schedule is not a run, so it never carries a [dev.tramai.core.identity.GovernedRunIdentity]:
 * it carries the deployment whose runs it will create. Every tick creates a FRESH run id,
 * so `deployment + freshRunId = GovernedRunIdentity` at execution time.
 */
data class GovernedScheduleBinding(
    val scheduleId: String,
    val deploymentIdentity: dev.tramai.core.identity.WorkloadDeploymentIdentity,
)

/**
 * Optional durable capability: governed schedule bindings.
 *
 * Kept separate from [ScheduleRecord] on purpose — the schedule's public shape does not
 * change, and a schedule that was declared governed stays governed even if a later
 * registration forgets to declare its deployment: silently downgrading a governed
 * schedule to ungoverned ticks is exactly the attribution loss 0.7.1d removes.
 */
interface GovernedScheduleBindingStore {
    suspend fun putGovernedScheduleBinding(binding: GovernedScheduleBinding)

    suspend fun getGovernedScheduleBinding(scheduleId: String): GovernedScheduleBinding?
}

interface WorkflowSchedulerStore : WorkflowDelayWakeupScheduler {
    suspend fun upsertSchedule(schedule: ScheduleRecord)

    suspend fun getSchedule(scheduleId: String): ScheduleRecord?

    suspend fun listScheduleStatus(): List<ScheduleStatusView>

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
