@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.scheduler

import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.orchestration.FailureIsolatingWorkflowObserver
import dev.tramai.orchestration.GovernedRun
import dev.tramai.orchestration.NoOpWorkflowObserver
import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowSuspendedException
import dev.tramai.orchestration.recoverGovernedRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val scope: CoroutineScope = OwnedSchedulerScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    // Epic 5.3: the scheduler owns WorkflowObserver instances and invokes
    // tick callbacks BEFORE durable scheduler transitions (markTickSkipped /
    // markTickMisfired / markTickStarted / releaseDelayWakeupClaim). A
    // throwing observer must be contained (non-authoritative FAIL_OPEN) so it
    // can never leave a claimed tick in the wrong state or terminate the poll
    // loop.
    private val isolatedObserver = FailureIsolatingWorkflowObserver(observer)
    private val monitor = Any()
    private val registrations = linkedMapOf<String, ScheduledWorkflowRegistration<*, *>>()
    private val delayRegistrations = linkedMapOf<String, ScheduledWorkflowRegistration<*, *>>()

    // 8.3c: one lifecycle owner. The default-created scope is timer-owned
    // (marked via OwnedSchedulerScope) and is cancelled on close(); a
    // caller-supplied scope is borrowed and never cancelled.
    private val loopOwner =
        SchedulerLoopOwner(
            parentScope = scope,
            ownsParentScope = scope is OwnedSchedulerScope,
        )

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
    ) = register(workflow, initialState, observer, persistence, deploymentIdentity = null)

    /**
     * Registers a GOVERNED schedule (0.7.1d): the schedule is durably bound to the
     * workload deployment whose runs it creates.
     *
     * The schedule stores no run identity — a schedule is not a run. Each tick creates a
     * fresh run id, and `deployment + freshRunId` is the governed identity of that tick's
     * execution. A binding, once declared, is never silently dropped by a later
     * registration that omits it: downgrading a governed schedule to ungoverned ticks is
     * exactly the attribution loss 0.7.1d removes.
     */
    suspend fun <S, R> register(
        workflow: Workflow<S, R>,
        initialState: suspend (ClaimedScheduledTick) -> S,
        observer: WorkflowObserver,
        persistence: WorkflowPersistence<S>?,
        deploymentIdentity: WorkloadDeploymentIdentity?,
    ) {
        val schedule =
            workflow.schedule
                ?: throw IllegalArgumentException("Workflow '${workflow.name}' does not declare a schedule")
        require(schedule is CronSchedule) {
            "ScheduledWorkflowTimer currently supports cron schedules only; workflow '${workflow.name}' declared kind='${schedule.kind}'"
        }
        schedule.validate()
        val scheduleId = scheduleId(workflow.name)
        val registration =
            ScheduledWorkflowRegistration(
                workflow = workflow,
                initialState = initialState,
                // Epic 5.3: per-registration observer wrapped at the boundary too
                // (registration.observer is invoked directly by pollOnce).
                observer = FailureIsolatingWorkflowObserver(observer),
                persistence = persistence,
                deploymentIdentity = deploymentIdentity,
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
        if (deploymentIdentity != null) {
            (store as? GovernedScheduleBindingStore)?.putGovernedScheduleBinding(
                GovernedScheduleBinding(scheduleId = scheduleId, deploymentIdentity = deploymentIdentity),
            )
        }
    }

    fun start(): Job =
        loopOwner.start {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                pollOnce()
                delay(pollInterval.toMillis())
            }
        }

    suspend fun stop() {
        loopOwner.stop()
    }

    suspend fun pollOnce() {
        val now = clock.instant()
        val ticks =
            store.claimDueTicks(
                now = now,
                ownerId = ownerId,
                claimDuration = claimDuration,
                limit = batchSize,
            )
        for (tick in ticks) {
            handleTick(tick = tick, now = now)
        }
        if (hasDelayRegistrations()) {
            val wakeups =
                store.claimDueDelayWakeups(
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
        loopOwner.close()
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
            isolatedObserver.onSkippedTick(tick.workflowName, tick.scheduledFireAt, reason, context)
            store.markTickSkipped(tick.tickId, tick.claimToken, reason)
            return
        }
        // A tick is a NEW execution: same deployment identity, FRESH run id. The
        // deployment comes from the durable schedule binding (the in-memory registration
        // is the fallback for stores without that capability); the run id is created here,
        // exactly once, by the execution envelope.
        val governedRun = governedRunFor(tick, registration)
        val context = governedRun?.context ?: scheduledTickContext(tick)
        val observer = registration.observer
        if (Duration.between(tick.scheduledFireAt, now) > misfireThreshold) {
            val reason = "misfire_threshold_exceeded"
            isolatedObserver.onMissedTick(tick.workflowName, tick.scheduledFireAt, reason, context)
            store.markTickMisfired(tick.tickId, tick.claimToken, reason)
            return
        }
        val runId = context.workflowId
        isolatedObserver.onScheduledTick(tick.workflowName, tick.scheduledFireAt, context)
        store.markTickStarted(tick.tickId, tick.claimToken, runId)
        try {
            registration.run(tick, context, governedRun)
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
        val registration =
            synchronized(monitor) {
                delayRegistrations[wakeup.runId]
            }
        if (registration == null) {
            val unregisteredEvent =
                RuntimeEvent.of(RuntimeEvents.SCHEDULER_DELAY_WAKEUP_UNREGISTERED) {
                    set(RuntimeAttributes.WORKFLOW_ID_BARE, wakeup.runId)
                    set(RuntimeAttributes.STEP_ID, wakeup.stepId)
                    set(RuntimeAttributes.RESUME_AT_EPOCH_MILLIS, wakeup.resumeAt.toEpochMilli())
                }
            isolatedObserver.onWorkflowEvent(
                workflowName = "unknown",
                name = unregisteredEvent.name,
                attributes = unregisteredEvent.attributes(),
                context = WorkflowContext(workflowId = wakeup.runId),
            )
            store.releaseDelayWakeupClaim(wakeup.runId, wakeup.stepId, wakeup.claimToken)
            return
        }
        val context =
            WorkflowContext(
                workflowId = wakeup.runId,
                attributes =
                    mapOf(
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

    private fun hasDelayRegistrations(): Boolean =
        synchronized(monitor) {
            delayRegistrations.isNotEmpty()
        }

    private fun registrationFor(scheduleId: String): ScheduledWorkflowRegistration<*, *>? =
        synchronized(monitor) {
            registrations[scheduleId]
        }

    /**
     * Resolves the governed execution envelope for a tick, or null for a schedule that is
     * not governed. The deployment identity is shared across the schedule's runs; the run
     * id is fresh for every tick.
     */
    private suspend fun governedRunFor(
        tick: ClaimedScheduledTick,
        registration: ScheduledWorkflowRegistration<*, *>,
    ): GovernedRun? {
        val durable = (store as? GovernedScheduleBindingStore)?.getGovernedScheduleBinding(tick.scheduleId)
        val deployment = durable?.deploymentIdentity ?: registration.deploymentIdentity ?: return null
        return GovernedRun.start(deployment = deployment, attributes = tickAttributes(tick))
    }

    private fun tickAttributes(tick: ClaimedScheduledTick): Map<String, Any?> =
        mapOf(
            RuntimeAttributes.SCHEDULE_TICK_ID.name to tick.tickId,
            RuntimeAttributes.SCHEDULE_SCHEDULE_ID.name to tick.scheduleId,
            RuntimeAttributes.SCHEDULE_SCHEDULED_FIRE_AT.name to tick.scheduledFireAt.toEpochMilli(),
        )

    private fun scheduledTickContext(tick: ClaimedScheduledTick): WorkflowContext =
        WorkflowContext(
            attributes = tickAttributes(tick),
        )

    private data class ScheduledWorkflowRegistration<S, R>(
        val workflow: Workflow<S, R>,
        val initialState: suspend (ClaimedScheduledTick) -> S,
        val observer: WorkflowObserver,
        val persistence: WorkflowPersistence<S>?,
        val deploymentIdentity: WorkloadDeploymentIdentity? = null,
    ) {
        suspend fun run(
            tick: ClaimedScheduledTick,
            context: WorkflowContext,
            governedRun: GovernedRun?,
        ) {
            val state = initialState(tick)
            if (governedRun == null) {
                workflow.run(
                    initialState = state,
                    context = context,
                    observer = observer,
                    persistence = persistence,
                )
            } else {
                workflow.run(
                    initialState = state,
                    run = governedRun,
                    observer = observer,
                    persistence = persistence,
                )
            }
        }

        suspend fun resume(context: WorkflowContext) {
            val persistence = persistence ?: return
            // Continuation RECOVERS identity from the run's own durable witness and never
            // rebuilds it: a schedule binding or configuration change between suspension
            // and wake-up must not rewrite the identity of an existing run. Attribution
            // that is partial or malformed fails closed here instead of degrading into a
            // legacy un-attributed wake-up.
            val governedRun = persistence.recoverGovernedRun(workflow.name, context.workflowId)
            if (governedRun == null) {
                workflow.resume(
                    context = context,
                    observer = observer,
                    persistence = persistence,
                )
            } else {
                workflow.resume(
                    run = governedRun,
                    observer = observer,
                    persistence = persistence,
                )
            }
        }
    }
}

private fun scheduleId(workflowName: String): String = "workflow:$workflowName"
