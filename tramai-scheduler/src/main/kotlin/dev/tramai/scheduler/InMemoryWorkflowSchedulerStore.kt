package dev.tramai.scheduler

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

class InMemoryWorkflowSchedulerStore : WorkflowSchedulerStore {
    private val schedules = linkedMapOf<String, MutableScheduleRecord>()
    private val ticks = linkedMapOf<String, MutableTickRecord>()
    private val delayWakeups = linkedMapOf<String, MutableDelayWakeupRecord>()

    override suspend fun upsertSchedule(schedule: ScheduleRecord) {
        synchronized(this) {
            schedules[schedule.scheduleId] = MutableScheduleRecord(
                scheduleId = schedule.scheduleId,
                workflowName = schedule.workflowName,
                schedule = schedule.schedule,
                nextFireAt = schedule.nextFireAt,
                enabled = schedule.enabled,
            )
        }
    }

    override suspend fun getSchedule(scheduleId: String): ScheduleRecord? = synchronized(this) {
        schedules[scheduleId]?.toRecord()
    }

    override suspend fun claimDueTicks(
        now: Instant,
        ownerId: String,
        claimDuration: Duration,
        limit: Int,
    ): List<ClaimedScheduledTick> {
        require(limit > 0) { "WorkflowSchedulerStore.claimDueTicks limit must be greater than zero" }
        require(!claimDuration.isNegative && !claimDuration.isZero) {
            "WorkflowSchedulerStore.claimDueTicks claimDuration must be positive"
        }
        return synchronized(this) {
            val reclaimed = ticks.values
                .asSequence()
                .filter { it.status.isClaimed && !it.claimExpiresAt.isAfter(now) }
                .sortedBy { it.scheduledFireAt }
                .take(limit)
                .map { tick -> tick.reclaim(ownerId, now.plus(claimDuration)) }
                .toList()
            if (reclaimed.size == limit) {
                return@synchronized reclaimed
            }
            val remaining = limit - reclaimed.size
            reclaimed + schedules.values
                .asSequence()
                .filter { it.enabled && !it.nextFireAt.isAfter(now) }
                .sortedBy { it.nextFireAt }
                .take(remaining)
                .map { schedule ->
                    val scheduledFireAt = schedule.nextFireAt
                    val tickId = tickId(schedule.scheduleId, scheduledFireAt)
                    val claimToken = UUID.randomUUID().toString()
                    val claimExpiresAt = now.plus(claimDuration)
                    ticks[tickId] = MutableTickRecord(
                        tickId = tickId,
                        scheduleId = schedule.scheduleId,
                        workflowName = schedule.workflowName,
                        scheduledFireAt = scheduledFireAt,
                        ownerId = ownerId,
                        claimToken = claimToken,
                        claimExpiresAt = claimExpiresAt,
                        status = TickStatus.CLAIMED,
                    )
                    schedule.nextFireAt = nextFireAfter(schedule.schedule, scheduledFireAt)
                    ClaimedScheduledTick(
                        tickId = tickId,
                        scheduleId = schedule.scheduleId,
                        workflowName = schedule.workflowName,
                        scheduledFireAt = scheduledFireAt,
                        claimToken = claimToken,
                        claimExpiresAt = claimExpiresAt,
                    )
                }
                .toList()
        }
    }

    override suspend fun markTickStarted(
        tickId: String,
        claimToken: String,
        runId: String,
    ) {
        updateTick(tickId, claimToken) {
            status = TickStatus.STARTED
            workflowRunId = runId
        }
    }

    override suspend fun releaseTickClaim(
        tickId: String,
        claimToken: String,
    ) {
        updateTick(tickId, claimToken) {
            status = TickStatus.CLAIMED
            ownerId = null
            this.claimToken = null
            claimExpiresAt = Instant.EPOCH
            workflowRunId = null
        }
    }

    override suspend fun markTickCompleted(
        tickId: String,
        claimToken: String,
    ) {
        updateTick(tickId, claimToken) {
            status = TickStatus.COMPLETED
        }
    }

    override suspend fun markTickSkipped(
        tickId: String,
        claimToken: String,
        reason: String,
    ) {
        updateTick(tickId, claimToken) {
            status = TickStatus.SKIPPED
            terminalReason = reason
        }
    }

    override suspend fun markTickMisfired(
        tickId: String,
        claimToken: String,
        reason: String,
    ) {
        updateTick(tickId, claimToken) {
            status = TickStatus.MISFIRED
            terminalReason = reason
        }
    }

    override suspend fun scheduleDelayWakeup(
        runId: String,
        stepId: String,
        resumeAt: Instant,
    ) {
        require(runId.isNotBlank()) { "Delay wakeup runId must not be blank" }
        require(stepId.isNotBlank()) { "Delay wakeup stepId must not be blank" }
        synchronized(this) {
            delayWakeups[delayWakeupId(runId, stepId)] = MutableDelayWakeupRecord(
                runId = runId,
                stepId = stepId,
                resumeAt = resumeAt,
                status = WakeupStatus.PENDING,
            )
        }
    }

    override suspend fun claimDueDelayWakeups(
        now: Instant,
        ownerId: String,
        claimDuration: Duration,
        limit: Int,
    ): List<ClaimedDelayWakeup> {
        require(ownerId.isNotBlank()) { "Delay wakeup ownerId must not be blank" }
        require(limit > 0) { "WorkflowSchedulerStore.claimDueDelayWakeups limit must be greater than zero" }
        require(!claimDuration.isNegative && !claimDuration.isZero) {
            "WorkflowSchedulerStore.claimDueDelayWakeups claimDuration must be positive"
        }
        return synchronized(this) {
            delayWakeups.values
                .asSequence()
                .filter {
                    !it.resumeAt.isAfter(now) &&
                        (it.status == WakeupStatus.PENDING || (it.status == WakeupStatus.CLAIMED && !it.claimExpiresAt!!.isAfter(now)))
                }
                .sortedBy { it.resumeAt }
                .take(limit)
                .map { wakeup ->
                    val claimToken = UUID.randomUUID().toString()
                    val claimExpiresAt = now.plus(claimDuration)
                    wakeup.status = WakeupStatus.CLAIMED
                    wakeup.ownerId = ownerId
                    wakeup.claimToken = claimToken
                    wakeup.claimExpiresAt = claimExpiresAt
                    ClaimedDelayWakeup(
                        runId = wakeup.runId,
                        stepId = wakeup.stepId,
                        resumeAt = wakeup.resumeAt,
                        claimToken = claimToken,
                        claimExpiresAt = claimExpiresAt,
                    )
                }
                .toList()
        }
    }

    override suspend fun releaseDelayWakeupClaim(
        runId: String,
        stepId: String,
        claimToken: String,
    ) {
        updateDelayWakeup(runId, stepId, claimToken) {
            status = WakeupStatus.PENDING
            ownerId = null
            this.claimToken = null
            claimExpiresAt = null
        }
    }

    override suspend fun markDelayWakeupCompleted(
        runId: String,
        stepId: String,
        claimToken: String,
    ) {
        synchronized(this) {
            updateDelayWakeupLocked(runId, stepId, claimToken) {
                status = WakeupStatus.COMPLETED
            }
            delayWakeups.remove(delayWakeupId(runId, stepId))
        }
    }

    private fun updateTick(
        tickId: String,
        claimToken: String,
        update: MutableTickRecord.() -> Unit,
    ) {
        synchronized(this) {
            val tick = ticks[tickId]
                ?: throw IllegalArgumentException("Unknown scheduled tick '$tickId'")
            require(tick.claimToken == claimToken && tick.status.isClaimed) {
                "Scheduled tick '$tickId' claim token does not match"
            }
            tick.update()
        }
    }

    private fun updateDelayWakeup(
        runId: String,
        stepId: String,
        claimToken: String,
        update: MutableDelayWakeupRecord.() -> Unit,
    ) {
        synchronized(this) {
            updateDelayWakeupLocked(runId, stepId, claimToken, update)
        }
    }

    private fun updateDelayWakeupLocked(
        runId: String,
        stepId: String,
        claimToken: String,
        update: MutableDelayWakeupRecord.() -> Unit,
    ) {
        val wakeupId = delayWakeupId(runId, stepId)
        val wakeup = delayWakeups[wakeupId]
            ?: throw IllegalArgumentException("Unknown delay wakeup '$wakeupId'")
        require(wakeup.claimToken == claimToken && wakeup.status == WakeupStatus.CLAIMED) {
            "Delay wakeup '$wakeupId' claim token does not match"
        }
        wakeup.update()
    }

    private fun nextFireAfter(
        schedule: dev.tramai.orchestration.WorkflowScheduleDefinition,
        after: Instant,
    ): Instant = when (schedule) {
        is CronSchedule -> schedule.nextFireAfter(after)
        else -> throw IllegalArgumentException(
            "InMemoryWorkflowSchedulerStore only supports CronSchedule records; got kind='${schedule.kind}'",
        )
    }

    private data class MutableScheduleRecord(
        val scheduleId: String,
        val workflowName: String,
        val schedule: dev.tramai.orchestration.WorkflowScheduleDefinition,
        var nextFireAt: Instant,
        val enabled: Boolean,
    ) {
        fun toRecord(): ScheduleRecord = ScheduleRecord(
            scheduleId = scheduleId,
            workflowName = workflowName,
            schedule = schedule,
            nextFireAt = nextFireAt,
            enabled = enabled,
        )
    }

    private data class MutableTickRecord(
        val tickId: String,
        val scheduleId: String,
        val workflowName: String,
        val scheduledFireAt: Instant,
        var ownerId: String?,
        var claimToken: String?,
        var claimExpiresAt: Instant,
        var status: TickStatus,
        var workflowRunId: String? = null,
        var terminalReason: String? = null,
    ) {
        fun reclaim(ownerId: String, claimExpiresAt: Instant): ClaimedScheduledTick {
            val claimToken = UUID.randomUUID().toString()
            this.ownerId = ownerId
            this.claimToken = claimToken
            this.claimExpiresAt = claimExpiresAt
            status = TickStatus.CLAIMED
            workflowRunId = null
            return ClaimedScheduledTick(
                tickId = tickId,
                scheduleId = scheduleId,
                workflowName = workflowName,
                scheduledFireAt = scheduledFireAt,
                claimToken = claimToken,
                claimExpiresAt = claimExpiresAt,
            )
        }
    }

    private data class MutableDelayWakeupRecord(
        val runId: String,
        val stepId: String,
        val resumeAt: Instant,
        var status: WakeupStatus,
        var ownerId: String? = null,
        var claimToken: String? = null,
        var claimExpiresAt: Instant? = null,
    )

    private enum class TickStatus {
        CLAIMED,
        STARTED,
        COMPLETED,
        SKIPPED,
        MISFIRED,
        ;

        val isClaimed: Boolean
            get() = this == CLAIMED || this == STARTED
    }

    private enum class WakeupStatus {
        PENDING,
        CLAIMED,
        COMPLETED,
    }
}

private fun tickId(
    scheduleId: String,
    scheduledFireAt: Instant,
): String = MessageDigest
    .getInstance("SHA-256")
    .digest("$scheduleId:${scheduledFireAt.toEpochMilli()}".toByteArray())
    .joinToString(separator = "") { byte ->
        byte.toInt().and(0xff).toString(16).padStart(2, '0')
    }

private fun delayWakeupId(
    runId: String,
    stepId: String,
): String = "$runId:$stepId"
