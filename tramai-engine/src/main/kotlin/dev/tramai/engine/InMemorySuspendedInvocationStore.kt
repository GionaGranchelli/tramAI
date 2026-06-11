package dev.tramai.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [SuspendedInvocationStore].
 *
 * Stores both safe metadata and sensitive context in a single [ConcurrentHashMap] keyed by [approvalId].
 * Does NOT persist beyond the JVM lifecycle.
 */
internal class InMemorySuspendedInvocationStore : SuspendedInvocationStore {

    private data class StoredSuspendedInvocation(
        val metadata: SuspendedInvocationMetadata,
        val sensitiveContext: SensitiveResumeContext,
    )

    private val invocations = ConcurrentHashMap<String, StoredSuspendedInvocation>()

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        sensitiveContext: SensitiveResumeContext,
    ) {
        val existing = invocations.putIfAbsent(
            metadata.approvalId,
            StoredSuspendedInvocation(metadata = metadata, sensitiveContext = sensitiveContext),
        )
        require(existing == null) {
            "Suspended invocation with approvalId '${metadata.approvalId}' already exists"
        }
    }

    override suspend fun get(approvalId: String): SuspendedInvocationMetadata? =
        invocations[approvalId]?.metadata

    override suspend fun revealSensitiveContext(approvalId: String): SensitiveResumeContext? =
        invocations[approvalId]?.sensitiveContext

    override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? =
        invocations.remove(approvalId)?.metadata
}

fun inMemorySuspendedInvocationStore(): SuspendedInvocationStore =
    InMemorySuspendedInvocationStore()
