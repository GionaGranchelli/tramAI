package dev.tramai.spring.sovereign.ops

/**
 * Operations for inspecting suspended invocations.
 *
 * Returns only safe metadata. Raw replay envelopes, tool arguments,
 * and sensitive payloads are NEVER exposed.
 */
interface SovereignSuspendedInvocationOperations {

    /**
     * Retrieve a single suspended invocation by its approval ID.
     * @return A safe summary, or null if not found.
     */
    suspend fun getSuspendedInvocation(approvalId: String): SovereignSuspendedInvocationSummary?
}
