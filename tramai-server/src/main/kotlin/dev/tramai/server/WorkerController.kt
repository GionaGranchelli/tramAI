package dev.tramai.server

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
class WorkerController(
    private val workerRegistry: InMemoryWorkerRegistry,
) {
    @GetMapping("/workers")
    fun listWorkers(): List<WorkerInfo> = workerRegistry.listWorkers()

    @GetMapping("/workers/events")
    fun workerEvents(): SseEmitter {
        val emitter = SseEmitter(-1L)
        workerRegistry.registerSseEmitter(emitter)
        return emitter
    }
}
