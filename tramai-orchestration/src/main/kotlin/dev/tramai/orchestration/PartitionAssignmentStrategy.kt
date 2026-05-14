package dev.tramai.orchestration

/**
 * Strategy interface for determining whether a worker owns the partition for a given workflow.
 *
 * Implementations can use any partitioning scheme (mod-hash, consistent hashing, etc.)
 * to map workflow IDs to workers. The strategy receives the current set of active workers
 * and must return `true` if the given worker should handle the workflow.
 *
 * This is a functional interface so it can be implemented concisely as a lambda.
 */
fun interface PartitionAssignmentStrategy {
    /**
     * Determines whether [workerId] should handle [workflowId] given the current set of
     * [activeWorkers].
     *
     * @param workflowId the workflow identifier being routed
     * @param workerId the worker to check ownership for
     * @param activeWorkers the ordered list of currently active worker IDs for this pool
     * @return `true` if [workerId] owns the partition for [workflowId]
     */
    suspend fun ownsPartition(
        workflowId: String,
        workerId: String,
        activeWorkers: List<String>,
    ): Boolean
}
