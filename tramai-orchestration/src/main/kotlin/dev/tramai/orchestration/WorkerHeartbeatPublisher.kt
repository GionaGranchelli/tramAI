package dev.tramai.orchestration

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.InetAddress

/**
 * Owns worker registration and the heartbeat loop.
 *
 * Registration runs once at worker start; the heartbeat then reports the
 * worker's uptime and claimed execution count on the poll-interval cadence
 * (half the poll interval, as before). Both use the optional
 * [WorkerRegistryStore] and the worker observer.
 */
internal class WorkerHeartbeatPublisher(
    private val config: WorkerConfig,
    private val workerRegistryStore: WorkerRegistryStore?,
    private val observability: TramaiWorkerObserver,
) {
    suspend fun registerWorker() {
        workerRegistryStore?.registerWorker(
            workerId = config.workerId,
            poolName = config.poolName,
            version = workerVersion(),
            capabilityLabels = config.capabilityLabels,
            host = workerHost(),
        )
    }

    suspend fun heartbeatLoop(
        startedAtMark: () -> MonotonicMark,
        claimedCount: () -> Int,
    ) {
        val interval = maxOf(1L, config.pollIntervalMillis / 2)
        while (currentCoroutineContext().isActive) {
            val uptime = startedAtMark().elapsedMillis()
            workerRegistryStore?.updateHeartbeat(config.workerId)
            observability.onWorkerHeartbeat(config.workerId, uptime, claimedCount())
            delay(interval)
        }
    }

    private fun workerHost(): String = runCatching {
        InetAddress.getLocalHost().hostName
    }.getOrDefault("unknown")

    private fun workerVersion(): String = TramaiWorker::class.java.`package`?.implementationVersion ?: "dev"
}
