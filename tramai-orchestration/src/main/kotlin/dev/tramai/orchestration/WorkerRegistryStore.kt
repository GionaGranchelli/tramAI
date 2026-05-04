package dev.tramai.orchestration

data class WorkerRegistryRecord(
    val workerId: String,
    val poolName: String,
    val version: String,
    val capabilityLabels: Set<String>,
    val host: String,
    val registeredAtEpochMillis: Long,
    val lastHeartbeatEpochMillis: Long,
)

interface WorkerRegistryStore {
    suspend fun registerWorker(
        workerId: String,
        poolName: String,
        version: String,
        capabilityLabels: Set<String>,
        host: String,
    )

    suspend fun updateHeartbeat(workerId: String)

    suspend fun unregisterWorker(workerId: String)

    suspend fun listActiveWorkers(): List<WorkerRegistryRecord>

    suspend fun listStaleWorkers(staleThresholdMillis: Long): List<WorkerRegistryRecord>
}
