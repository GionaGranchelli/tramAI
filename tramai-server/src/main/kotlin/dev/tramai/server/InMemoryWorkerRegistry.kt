package dev.tramai.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Clock
import java.time.Instant

data class WorkerInfo(
    val workerId: String,
    val status: String, // "online", "stale", or "offline"
    val poolName: String,
    val capabilityLabels: Set<String>,
    val version: String,
    val host: String,
    val lastHeartbeat: String, // ISO timestamp
    val activeRunCount: Int,
    val draining: Boolean,
)

class InMemoryWorkerRegistry(
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val monitor = Any()
    private val workers = linkedMapOf<String, MutableWorkerRecord>()
    private val emitters = mutableListOf<SseEmitter>()

    fun register(
        workerId: String,
        poolName: String,
        capabilityLabels: Set<String>,
        version: String,
        host: String,
    ): WorkerInfo {
        val info: WorkerInfo
        val emitterSnapshot: List<SseEmitter>
        synchronized(monitor) {
            val now = clock.instant()
            val record = MutableWorkerRecord(
                workerId = workerId,
                poolName = poolName,
                capabilityLabels = capabilityLabels.toSet(),
                version = version,
                host = host,
                lastHeartbeat = now,
                activeRunCount = 0,
                draining = false,
            )
            workers[workerId] = record
            info = record.toInfo(statusFor(record.lastHeartbeat, now))
            emitterSnapshot = emitters.toList()
        }
        dispatchSse("workerOnline", info, emitterSnapshot)
        return info
    }

    fun heartbeat(workerId: String): WorkerInfo? {
        var emitterSnapshot: List<SseEmitter>? = null
        val info = synchronized(monitor) {
            val record = workers[workerId] ?: return null
            val now = clock.instant()
            val wasOffline = statusFor(record.lastHeartbeat, now) == "offline"
            record.lastHeartbeat = now
            if (wasOffline) {
                emitterSnapshot = emitters.toList()
            }
            record.toInfo(statusFor(record.lastHeartbeat, now))
        }
        emitterSnapshot?.let { dispatchSse("workerOnline", info, it) }
        return info
    }

    fun unregister(workerId: String): WorkerInfo? {
        val info: WorkerInfo
        val emitterSnapshot: List<SseEmitter>
        synchronized(monitor) {
            val record = workers.remove(workerId) ?: return null
            info = record.toInfo("offline")
            emitterSnapshot = emitters.toList()
        }
        dispatchSse("workerOffline", info, emitterSnapshot)
        return info
    }

    fun listWorkers(): List<WorkerInfo> = synchronized(monitor) {
        val now = clock.instant()
        workers.values.map { record -> record.toInfo(statusFor(record.lastHeartbeat, now)) }
    }

    fun registerSseEmitter(emitter: SseEmitter) {
        synchronized(monitor) {
            emitter.onCompletion { synchronized(monitor) { emitters.remove(emitter) } }
            emitter.onError { synchronized(monitor) { emitters.remove(emitter) } }
            emitter.onTimeout { synchronized(monitor) { emitters.remove(emitter) } }
            emitters.add(emitter)
        }
        // Send current worker list as initial state
        try {
            val workerList = listWorkers()
            val json = objectMapper.writeValueAsString(workerList)
            emitter.send(
                SseEmitter.event()
                    .id("0")
                    .name("workerList")
                    .data(json),
            )
        } catch (_: Exception) {
            synchronized(monitor) { emitters.remove(emitter) }
            emitter.completeWithError(IllegalStateException("Failed to send initial worker list"))
        }
    }

    private fun dispatchSse(
        eventName: String,
        worker: WorkerInfo,
        emitterSnapshot: List<SseEmitter>,
    ) {
        val json = objectMapper.writeValueAsString(worker)
        val deadEmitters = mutableListOf<SseEmitter>()
        emitterSnapshot.forEach { emitter ->
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
            synchronized(monitor) { emitters.removeAll(deadEmitters.toSet()) }
        }
    }

    private fun statusFor(
        lastHeartbeat: Instant,
        now: Instant,
    ): String = when {
        lastHeartbeat.isBefore(now.minusSeconds(30)) -> "offline"
        lastHeartbeat.isBefore(now.minusSeconds(15)) -> "stale"
        else -> "online"
    }

    private class MutableWorkerRecord(
        var workerId: String,
        var poolName: String,
        var capabilityLabels: Set<String>,
        var version: String,
        var host: String,
        var lastHeartbeat: Instant,
        var activeRunCount: Int,
        var draining: Boolean,
    ) {
        fun toInfo(status: String): WorkerInfo = WorkerInfo(
            workerId = workerId,
            status = status,
            poolName = poolName,
            capabilityLabels = capabilityLabels,
            version = version,
            host = host,
            lastHeartbeat = lastHeartbeat.toString(),
            activeRunCount = activeRunCount,
            draining = draining,
        )

        override fun equals(other: Any?): Boolean =
            this === other || (other is MutableWorkerRecord && workerId == other.workerId)

        override fun hashCode(): Int = workerId.hashCode()
    }
}
