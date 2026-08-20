package dev.tramai.scheduler


import dev.tramai.core.observation.event.RuntimeEvents

import dev.tramai.core.observation.event.RuntimeEvent

import dev.tramai.core.observation.event.RuntimeAttributes

import dev.tramai.orchestration.NoOpWorkflowObserver
import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowSuspendedException
import kotlinx.coroutines.CoroutineStart
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
    private val monitor = Any()
    private val registrations = linkedMapOf<String, ScheduledWorkflowRegistration<*, *>>()
    private val delayRegistrations = linkedMapOf<String, ScheduledWorkflowRegistration<*, *>>()
    private var loopJob: Job? = null
    private var running = false

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
        val registration = ScheduledWorkflowRegistration(
            workflow = workflow,
            initialState = initialState,
            observer = observer,
            persistence = persistence,
        )
        synchronized(monitor) {
            registrations[scheduleId] = registration
        }
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
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive) {
                pollOnce()
                delay(pollInterval.toMillis())
            }
        }
        synchronized(monitor) {
            check(!running && loopJob == null) { "ScheduledWorkflowTimer is already started" }
            running = true
            loopJob = job
        }
        job.start()
        return job
    }

    suspend fun stop() {
        val job = synchronized(monitor) {
            val job = loopJob
            loopJob = null
            running = false
            job
        } ?: return
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
        if (hasDelayRegistrations()) {
            val wakeups = store.claimDueDelayWakeups(
                now = now,
                ownerId = ownerId,
                claimDuration = claimDuration,
                limit = batchSize,
            )
            for (wakeup in wakeups) {
                handleDelayWakeup(wakeup)
            }
        }
    }

    override fun close() {
        val job = synchronized(monitor) {
            val job = loopJob
            loopJob = null
            running = false
            job
        }
        job?.cancel()
    }

    private suspend fun handleTick(
        tick: ClaimedScheduledTick,
        now: Instant,
    ) {
        if (!tick.claimExpiresAt.isAfter(clock.instant())) {
            store.releaseTickClaim(tick.tickId, tick.claimToken)
            return
        }
        val registration = registrationFor(tick.scheduleId)
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
        try {
            registration.run(tick, context)
        } catch (_: WorkflowSuspendedException) {
            synchronized(monitor) {
                delayRegistrations[runId] = registration
            }
            // The workflow has durably checkpointed itself; the delay wakeup will resume it.
        }
        store.markTickCompleted(tick.tickId, tick.claimToken)
    }

    private suspend fun handleDelayWakeup(wakeup: ClaimedDelayWakeup) {
        if (!wakeup.claimExpiresAt.isAfter(clock.instant())) {
            store.releaseDelayWakeupClaim(wakeup.runId, wakeup.stepId, wakeup.claimToken)
            return
        }
        val registration = synchronized(monitor) {
            delayRegistrations[wakeup.runId]
        }
        if (registration == null) {
            val unregisteredEvent = RuntimeEvent.of(RuntimeEvents.SCHEDULER_DELAY_WAKEUP_UNREGISTERED) {
                set(RuntimeAttributes.WORKFLOW_ID_BARE, wakeup.runId)
                set(RuntimeAttributes.STEP_ID, wakeup.stepId)
                set(RuntimeAttributes.RESUME_AT_EPOCH_MILLIS, wakeup.resumeAt.toEpochMilli())
            }
            observer.onWorkflowEvent(
                workflowName = "unknown",
                name = unregisteredEvent.name,
                attributes = unregisteredEvent.attributes(),
                context = WorkflowContext(workflowId = wakeup.runId),
            )
            store.releaseDelayWakeupClaim(wakeup.runId, wakeup.stepId, wakeup.claimToken)
            return
        }
        val context = WorkflowContext(
            workflowId = wakeup.runId,
            attributes = mapOf(
                RuntimeAttributes.SCHEDULER_DELAY_STEP_ID.name to wakeup.stepId,
                RuntimeAttributes.SCHEDULER_DELAY_RESUME_AT.name to wakeup.resumeAt.toEpochMilli(),
            ),
        )
        try {
            registration.resume(context)
            synchronized(monitor) {
                delayRegistrations.remove(wakeup.runId)
            }
            store.markDelayWakeupCompleted(wakeup.runId, wakeup.stepId, wakeup.claimToken)
        } catch (_: WorkflowSuspendedException) {
            // The workflow re-checkpointed the delay because its own clock says it is still waiting.
            store.releaseDelayWakeupClaim(wakeup.runId, wakeup.stepId, wakeup.claimToken)
        }
    }

    private fun hasDelayRegistrations(): Boolean = synchronized(monitor) {
        delayRegistrations.isNotEmpty()
    }

    private fun registrationFor(scheduleId: String): ScheduledWorkflowRegistration<*, *>? = synchronized(monitor) {
        registrations[scheduleId]
    }

    private fun scheduledTickContext(tick: ClaimedScheduledTick): WorkflowContext = WorkflowContext(
        attributes = mapOf(
            RuntimeAttributes.SCHEDULE_TICK_ID.name to tick.tickId,
            RuntimeAttributes.SCHEDULE_SCHEDULE_ID.name to tick.scheduleId,
            RuntimeAttributes.SCHEDULE_SCHEDULED_FIRE_AT.name to tick.scheduledFireAt.toEpochMilli(),
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
        suspend fun resume(context: WorkflowContext) {
            val persistence = persistence ?: return
            workflow.resume(
                context = context,
                observer = observer,
                persistence = persistence,
            )
        }
    }
}

private fun scheduleId(workflowName: String): String = "workflow:$workflowName"



