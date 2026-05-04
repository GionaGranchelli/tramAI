package dev.tramai.server

import org.slf4j.LoggerFactory
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant

data class WorkerInfo(
    val workerId: String,
    val status: String, // "online" or "offline"
    val poolName: String,
    val capabilityLabels: Set<String>,
    val version: String,
    val host: String,
    val lastHeartbeat: String, // ISO timestamp
    val activeRunCount: Int,
    val draining: Boolean,
)

class InMemoryWorkerRegistry {
    private val logger = LoggerFactory.getLogger(InMemoryWorkerRegistry::class.java)
    private val monitor = Any()
    private val workers = linkedMapOf<String, MutableWorkerRecord>()
    private val emitters = mutableListOf<SseEmitter>()

    fun register(
        workerId: String,
        poolName: String,
        capabilityLabels: Set<String>,
        version: String,
        host: String,
    ): WorkerInfo = synchronized(monitor) {
        val now = Instant.now()
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
        val info = record.toInfo()
        dispatchSse("workerOnline", info)
        info
    }

    fun heartbeat(workerId: String): WorkerInfo? = synchronized(monitor) {
        val record = workers[workerId] ?: return null
        val wasOffline = Instant.now().minusSeconds(30).isAfter(record.lastHeartbeat)
        record.lastHeartbeat = Instant.now()
        if (wasOffline) {
            dispatchSse("workerOnline", record.toInfo())
        }
        record.toInfo()
    }

    fun unregister(workerId: String): WorkerInfo? = synchronized(monitor) {
        val record = workers.remove(workerId) ?: return null
        val info = record.toInfo()
        dispatchSse("workerOffline", info)
        info
    }

    fun listWorkers(): List<WorkerInfo> = synchronized(monitor) {
        val now = Instant.now()
        val staleThreshold = now.minusSeconds(30)
        workers.values.map { record ->
            val isOnline = !record.lastHeartbeat.isBefore(staleThreshold)
            record.toInfo().copy(status = if (isOnline) "online" else "offline")
        }
    }

    fun registerSseEmitter(emitter: SseEmitter) {
        synchronized(monitor) {
            emitter.onCompletion { synchronized(monitor) { emitters.remove(emitter) } }
            emitter.onError { synchronized(monitor) { emitters.remove(emitter) } }
            emitters.add(emitter)
        }
        // Send current worker list as initial state
        try {
            val workerList = listWorkers()
            val json = workerListToJson(workerList)
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

    private fun dispatchSse(eventName: String, worker: WorkerInfo) {
        val json = workerToJson(worker)
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
            synchronized(monitor) { emitters.removeAll(deadEmitters.toSet()) }
        }
    }

    private fun workerToJson(worker: WorkerInfo): String {
        return """{"workerId":"${escapeJson(worker.workerId)}","status":"${escapeJson(worker.status)}","poolName":"${escapeJson(worker.poolName)}","capabilityLabels":${labelsToJson(worker.capabilityLabels)},"version":"${escapeJson(worker.version)}","host":"${escapeJson(worker.host)}","lastHeartbeat":"${worker.lastHeartbeat}","activeRunCount":${worker.activeRunCount},"draining":${worker.draining}}"""
    }

    private fun workerListToJson(workers: List<WorkerInfo>): String {
        return workers.joinToString(prefix = "[", postfix = "]", separator = ",") { workerToJson(it) }
    }

    private fun labelsToJson(labels: Set<String>): String {
        return labels.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"${escapeJson(it)}\"" }
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private data class MutableWorkerRecord(
        val workerId: String,
        val poolName: String,
        val capabilityLabels: Set<String>,
        val version: String,
        val host: String,
        var lastHeartbeat: Instant,
        var activeRunCount: Int,
        var draining: Boolean,
    ) {
        fun toInfo(): WorkerInfo = WorkerInfo(
            workerId = workerId,
            status = "online",
            poolName = poolName,
            capabilityLabels = capabilityLabels,
            version = version,
            host = host,
            lastHeartbeat = lastHeartbeat.toString(),
            activeRunCount = activeRunCount,
            draining = draining,
        )
    }
}
