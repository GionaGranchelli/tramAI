package dev.tramai.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [SuspendedInvocationStore].
 *
 * Stores suspended invocations in a [ConcurrentHashMap] keyed by [approvalId].
 * Does NOT persist beyond the JVM lifecycle.
 */
internal class InMemorySuspendedInvocationStore : SuspendedInvocationStore {

    private val store = ConcurrentHashMap<String, SuspendedInvocation>()

    override suspend fun create(invocation: SuspendedInvocation): SuspendedInvocation {
        val existing = store.putIfAbsent(invocation.approvalId, invocation)
        require(existing == null) {
            "Suspended invocation with approvalId '${invocation.approvalId}' already exists"
        }
        return invocation
    }

    override suspend fun get(approvalId: String): SuspendedInvocation? = store[approvalId]

    override suspend fun remove(approvalId: String): SuspendedInvocation? = store.remove(approvalId)
}
