package dev.tramai.orchestration

data class WorkerConfig(
    val workerId: String,
    val poolName: String,
    val capabilityLabels: Set<String> = emptySet(),
    val pollIntervalMillis: Long = 5_000,
    val leaseDurationMillis: Long = 30_000,
    val drainTimeoutMillis: Long = 60_000,
    val partitionEnabled: Boolean = false,
    val workerCount: Int = 1,
) {
    init {
        require(workerId.isNotBlank()) { "WorkerConfig.workerId must not be blank" }
        require(poolName.isNotBlank()) { "WorkerConfig.poolName must not be blank" }
        require(pollIntervalMillis > 0) { "WorkerConfig.pollIntervalMillis must be greater than zero" }
        require(leaseDurationMillis > 0) { "WorkerConfig.leaseDurationMillis must be greater than zero" }
        require(drainTimeoutMillis > 0) { "WorkerConfig.drainTimeoutMillis must be greater than zero" }
        require(workerCount > 0) { "WorkerConfig.workerCount must be greater than zero" }
    }
}
