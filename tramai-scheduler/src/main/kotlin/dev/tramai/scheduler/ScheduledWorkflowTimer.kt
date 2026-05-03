package dev.tramai.scheduler

import dev.tramai.orchestration.NoOpWorkflowObserver
import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import dev.tramai.orchestration.WorkflowPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ScheduledWorkflowTimer(
    private val store: WorkflowSchedulerStore,
    private val ownerId: String = UUID.randomUUID().toString(),
    private val clock: Clock = Clock.systemUTC(),
    private val pollInterval: Duration = Duration.ofSeconds(1),
    private val claimDuration: Duration = Duration.ofSeconds(30),
    private val misfireThreshold: Duration = Duration.ofMinutes(5),
    private val batchSize: Int = 50,
    private val observer: WorkflowObserver = NoOpWorkflowObserver,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    private val registrations = linkedMapOf<String, ScheduledWorkflowRegistration<*, *>>()
    private var loopJob: Job? = null

    init {
        require(!pollInterval.isNegative && !pollInterval.isZero) {
            "ScheduledWorkflowTimer.pollInterval must be positive"
        }
        require(!claimDuration.isNegative && !claimDuration.isZero) {
            "ScheduledWorkflowTimer.claimDuration must be positive"
        }
        require(!misfireThreshold.isNegative) {
            "ScheduledWorkflowTimer.misfireThreshold must not be negative"
        }
        require(batchSize > 0) {
            "ScheduledWorkflowTimer.batchSize must be greater than zero"
        }
    }

    suspend fun <S, R> register(
        workflow: Workflow<S, R>,
        initialState: suspend (ClaimedScheduledTick) -> S,
        observer: WorkflowObserver = NoOpWorkflowObserver,
        persistence: WorkflowPersistence<S>? = null,
    ) {
        val schedule = workflow.schedule
            ?: throw IllegalArgumentException("Workflow '${workflow.name}' does not declare a schedule")
        require(schedule is CronSchedule) {
            "ScheduledWorkflowTimer currently supports cron schedules only; workflow '${workflow.name}' declared kind='${schedule.kind}'"
        }
        schedule.validate()
        val scheduleId = scheduleId(workflow.name)
        registrations[scheduleId] = ScheduledWorkflowRegistration(
            workflow = workflow,
            initialState = initialState,
            observer = observer,
            persistence = persistence,
        )
        store.upsertSchedule(
            ScheduleRecord(
                scheduleId = scheduleId,
                workflowName = workflow.name,
                schedule = schedule,
                nextFireAt = schedule.nextFireAfter(clock.instant()),
            ),
        )
    }

    fun start(): Job {
        check(loopJob == null) { "ScheduledWorkflowTimer is already started" }
        val job = scope.launch {
            while (isActive) {
                pollOnce()
                delay(pollInterval.toMillis())
            }
        }
        loopJob = job
        return job
    }

    suspend fun stop() {
        val job = loopJob ?: return
        loopJob = null
        job.cancelAndJoin()
    }

    suspend fun pollOnce() {
        val now = clock.instant()
        val ticks = store.claimDueTicks(
            now = now,
            ownerId = ownerId,
            claimDuration = claimDuration,
            limit = batchSize,
        )
        for (tick in ticks) {
            handleTick(tick = tick, now = now)
        }
    }

    override fun close() {
        loopJob?.cancel()
        loopJob = null
    }

    private suspend fun handleTick(
        tick: ClaimedScheduledTick,
        now: Instant,
    ) {
        val registration = registrations[tick.scheduleId]
        if (registration == null) {
            val reason = "workflow_not_registered"
            val context = scheduledTickContext(tick)
            observer.onSkippedTick(tick.workflowName, tick.scheduledFireAt, reason, context)
            store.markTickSkipped(tick.tickId, tick.claimToken, reason)
            return
        }
        val context = scheduledTickContext(tick)
        val observer = registration.observer
        if (Duration.between(tick.scheduledFireAt, now) > misfireThreshold) {
            val reason = "misfire_threshold_exceeded"
            observer.onMissedTick(tick.workflowName, tick.scheduledFireAt, reason, context)
            store.markTickMisfired(tick.tickId, tick.claimToken, reason)
            return
        }
        val runId = context.workflowId
        observer.onScheduledTick(tick.workflowName, tick.scheduledFireAt, context)
        store.markTickStarted(tick.tickId, tick.claimToken, runId)
        registration.run(tick, context)
        store.markTickCompleted(tick.tickId, tick.claimToken)
    }

    private fun scheduledTickContext(tick: ClaimedScheduledTick): WorkflowContext = WorkflowContext(
        attributes = mapOf(
            "tramai.schedule.tick_id" to tick.tickId,
            "tramai.schedule.schedule_id" to tick.scheduleId,
            "tramai.schedule.scheduled_fire_at_epoch_millis" to tick.scheduledFireAt.toEpochMilli(),
        ),
    )

    private data class ScheduledWorkflowRegistration<S, R>(
        val workflow: Workflow<S, R>,
        val initialState: suspend (ClaimedScheduledTick) -> S,
        val observer: WorkflowObserver,
        val persistence: WorkflowPersistence<S>?,
    ) {
        suspend fun run(
            tick: ClaimedScheduledTick,
            context: WorkflowContext,
        ) {
            workflow.run(
                initialState = initialState(tick),
                context = context,
                observer = observer,
                persistence = persistence,
            )
        }
    }
}

private fun scheduleId(workflowName: String): String = "workflow:$workflowName"
