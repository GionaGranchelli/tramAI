package dev.tramai.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant

data class ScheduleSummary(
    val workflowName: String,
    val cronExpression: String,
    val nextTick: String?, // ISO timestamp
    val lastTick: String?, // ISO timestamp
    val lastRunStatus: String?,
    val lastRunId: String?,
    val misfireCount: Int,
)

@RestController
class ScheduleController {
    private val logger = LoggerFactory.getLogger(ScheduleController::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val schedulerAvailable: Boolean = try {
        Class.forName("dev.tramai.scheduler.InMemoryWorkflowSchedulerStore")
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    private val sseEmitters = mutableListOf<SseEmitter>()
    private val monitor = Any()

    init {
        logger.info("ScheduleController initialized, scheduler available: $schedulerAvailable")
    }

    @GetMapping("/schedules")
    fun listSchedules(): List<ScheduleSummary> {
        // For now, return an empty list. Once the scheduler store exposes a list
        // method, this can be wired to InMemoryWorkflowSchedulerStore.
        return emptyList()
    }

    @GetMapping("/schedules/events")
    fun scheduleEvents(): SseEmitter {
        val emitter = SseEmitter(-1L)
        synchronized(monitor) {
            emitter.onCompletion { synchronized(monitor) { sseEmitters.remove(emitter) } }
            emitter.onError { synchronized(monitor) { sseEmitters.remove(emitter) } }
            sseEmitters.add(emitter)
        }
        return emitter
    }

    /**
     * Call this when a schedule tick fires to push events to SSE subscribers.
     * This is a public hook that can be called by scheduler integration code.
     */
    fun pushScheduleTick(schedule: ScheduleSummary) {
        val json = objectMapper.writeValueAsString(schedule)
        val deadEmitters = mutableListOf<SseEmitter>()
        synchronized(monitor) {
            sseEmitters.forEach { emitter ->
                try {
                    emitter.send(
                        SseEmitter.event()
                            .name("scheduleTick")
                            .data(json),
                    )
                } catch (_: Exception) {
                    deadEmitters += emitter
                }
            }
        }
        if (deadEmitters.isNotEmpty()) {
            synchronized(monitor) { sseEmitters.removeAll(deadEmitters.toSet()) }
        }
    }
}
