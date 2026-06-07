package dev.tramai.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [SuspendedInvocationStore].
 *
 * Stores both safe metadata and sensitive context in a [ConcurrentHashMap] keyed by [approvalId].
 * Does NOT persist beyond the JVM lifecycle.
 */
internal class InMemorySuspendedInvocationStore : SuspendedInvocationStore {

    private val metadatas = ConcurrentHashMap<String, SuspendedInvocationMetadata>()
    private val sensitiveContexts = ConcurrentHashMap<String, SensitiveResumeContext>()

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        sensitiveContext: SensitiveResumeContext,
    ) {
        val existing = metadatas.putIfAbsent(metadata.approvalId, metadata)
        require(existing == null) {
            "Suspended invocation with approvalId '${metadata.approvalId}' already exists"
        }
        sensitiveContexts[metadata.approvalId] = sensitiveContext
    }

    override suspend fun get(approvalId: String): SuspendedInvocationMetadata? = metadatas[approvalId]

    override suspend fun revealSensitiveContext(approvalId: String): SensitiveResumeContext? =
        sensitiveContexts[approvalId]

    override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
        sensitiveContexts.remove(approvalId)
        return metadatas.remove(approvalId)
    }
}
