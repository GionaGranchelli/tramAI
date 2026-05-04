package dev.tramai.server

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@ConditionalOnProperty(prefix = "tramai.dashboard", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class WorkerController(
    private val workerRegistry: InMemoryWorkerRegistry,
) {
    @GetMapping("/workers")
    fun listWorkers(): List<WorkerInfo> = workerRegistry.listWorkers()

    @GetMapping("/workers/events")
    fun workerEvents(): SseEmitter {
        val emitter = SseEmitter(300_000L)
        workerRegistry.registerSseEmitter(emitter)
        return emitter
    }
}
