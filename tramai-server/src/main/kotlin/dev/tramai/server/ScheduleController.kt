package dev.tramai.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import dev.tramai.scheduler.ScheduleStatusView
import dev.tramai.scheduler.WorkflowSchedulerStore
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant

data class ScheduleSummary(
    val scheduleId: String,
    val workflowName: String,
    val cronExpression: String,
    val enabled: Boolean,
    val nextTick: String?, // ISO timestamp
    val lastTick: String?, // ISO timestamp
    val lastRunStatus: String?,
    val lastRunId: String?,
    val misfireCount: Int,
)

@RestController
@ConditionalOnProperty(prefix = "tramai.dashboard", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ScheduleController(
    @Autowired(required = false)
    private val schedulerStore: WorkflowSchedulerStore?,
    private val objectMapper: ObjectMapper,
) {
    private val monitor = Any()
    private val sseEmitters = mutableListOf<SseEmitter>()

    @GetMapping("/schedules")
    fun listSchedules(): List<ScheduleSummary> = runBlocking {
        schedulerStore?.listScheduleStatus()?.map(ScheduleStatusView::toSummary).orEmpty()
    }

    @GetMapping("/schedules/events")
    fun scheduleEvents(): SseEmitter {
        val emitter = SseEmitter(300_000L)
        synchronized(monitor) {
            emitter.onCompletion { synchronized(monitor) { sseEmitters.remove(emitter) } }
            emitter.onError { synchronized(monitor) { sseEmitters.remove(emitter) } }
            emitter.onTimeout { synchronized(monitor) { sseEmitters.remove(emitter) } }
            sseEmitters.add(emitter)
        }
        return emitter
    }

    fun onScheduledTick(
        workflowName: String,
        scheduledFireAt: Instant,
    ) {
        pushScheduleEvent("scheduleTick", ScheduleSummary(
            scheduleId = workflowName,
            workflowName = workflowName,
            cronExpression = "",
            enabled = true,
            nextTick = null,
            lastTick = scheduledFireAt.toString(),
            lastRunStatus = null,
            lastRunId = null,
            misfireCount = 0,
        ))
    }

    fun onMissedTick(
        workflowName: String,
        scheduledFireAt: Instant,
    ) {
        pushScheduleEvent("scheduleMisfire", ScheduleSummary(
            scheduleId = workflowName,
            workflowName = workflowName,
            cronExpression = "",
            enabled = true,
            nextTick = null,
            lastTick = scheduledFireAt.toString(),
            lastRunStatus = null,
            lastRunId = null,
            misfireCount = 0,
        ))
    }

    fun pushScheduleTick(schedule: ScheduleSummary) {
        pushScheduleEvent("scheduleTick", schedule)
    }

    private fun pushScheduleEvent(
        eventName: String,
        schedule: ScheduleSummary,
    ) {
        val emitters = synchronized(monitor) { sseEmitters.toList() }
        if (emitters.isEmpty()) {
            return
        }
        val json = objectMapper.writeValueAsString(schedule)
        val deadEmitters = mutableListOf<SseEmitter>()
        emitters.forEach { emitter ->
            try {
                emitter.send(
                    SseEmitter.event()
                        .name(eventName)
                        .data(json),
                )
            } catch (_: Exception) {
                deadEmitters += emitter
            }
        }
        if (deadEmitters.isNotEmpty()) {
            synchronized(monitor) { sseEmitters.removeAll(deadEmitters.toSet()) }
        }
    }
}

private fun ScheduleStatusView.toSummary(): ScheduleSummary = ScheduleSummary(
    scheduleId = scheduleId,
    workflowName = workflowName,
    cronExpression = cronExpression,
    enabled = true,
    nextTick = nextTick?.toString(),
    lastTick = lastTick?.toString(),
    lastRunStatus = lastRunStatus,
    lastRunId = lastRunId,
    misfireCount = misfireCount,
)

class ScheduleEventObserver(
    private val scheduleController: ObjectProvider<ScheduleController>,
) : WorkflowObserver {
    override fun onScheduledTick(
        workflowName: String,
        scheduledFireAt: Instant,
        context: WorkflowContext,
    ) {
        scheduleController.ifAvailable?.onScheduledTick(workflowName, scheduledFireAt)
    }

    override fun onMissedTick(
        workflowName: String,
        scheduledFireAt: Instant,
        reason: String,
        context: WorkflowContext,
    ) {
        scheduleController.ifAvailable?.onMissedTick(workflowName, scheduledFireAt)
    }
}
