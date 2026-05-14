package dev.tramai.orchestration

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * A [PartitionAssignmentStrategy] that uses the high 64 bits of SHA-256 modulo
 * the active worker count to deterministically assign workflow partitions to workers.
 *
 * Workers are sorted lexicographically by their worker ID, ensuring a consistent
 * assignment order across the pool. Each workflow is always routed to the same
 * worker as long as the active worker set remains unchanged.
 *
 * This is the default strategy used by [TramaiWorker] when no custom strategy is provided.
 */
class ModHashPartitionStrategy : PartitionAssignmentStrategy {

    override suspend fun ownsPartition(
        workflowId: String,
        workerId: String,
        activeWorkers: List<String>,
    ): Boolean {
        if (activeWorkers.isEmpty()) return false
        val hash = stableHash(workflowId)
        val index = (hash % activeWorkers.size).toInt()
        return workerId == activeWorkers[index]
    }

    /**
     * Uses the high 64 bits of SHA-256 to keep partition routing deterministic
     * without carrying the full digest.
     *
     * This truncation still has the usual birthday-bound collision risk, so operators
     * with very large worker counts should expect extremely rare but possible hash collisions.
     */
    private fun stableHash(workflowId: String): Long {
        val bytes = MessageDigest.getInstance("SHA-256").digest(workflowId.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(bytes.copyOfRange(0, Long.SIZE_BYTES)).long and Long.MAX_VALUE
    }
}
