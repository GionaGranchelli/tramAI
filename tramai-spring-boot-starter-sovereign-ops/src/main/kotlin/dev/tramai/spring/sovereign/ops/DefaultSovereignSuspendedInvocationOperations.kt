package dev.tramai.spring.sovereign.ops

import dev.tramai.engine.SuspendedInvocationStore

/**
 * Default implementation of [SovereignSuspendedInvocationOperations].
 *
 * Delegates to a [SuspendedInvocationStore]. Only safe metadata is
 * returned — raw replay envelopes and sensitive payloads are never exposed.
 */
class DefaultSovereignSuspendedInvocationOperations(
    private val store: SuspendedInvocationStore?,
) : SovereignSuspendedInvocationOperations {

    private companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")
    }

    override suspend fun getSuspendedInvocation(
        approvalId: String,
    ): SovereignSuspendedInvocationSummary? {
        validateId(approvalId)
        if (store == null) {
            throw IllegalStateException("tramai-sovereign-ops-store-unavailable")
        }
        val metadata = store.get(approvalId) ?: return null
        return metadata.toSummary()
    }

    // ── Validation ──

    private fun validateId(id: String) {
        require(id.isNotBlank()) { ERROR_INVALID_SUSPENDED_INVOCATION_ID }
        require(id.length <= 128) { ERROR_INVALID_SUSPENDED_INVOCATION_ID }
        require(SAFE_ID.matches(id)) { ERROR_INVALID_SUSPENDED_INVOCATION_ID }
    }

    // ── Mapping ──

    private fun dev.tramai.engine.SuspendedInvocationMetadata.toSummary(): SovereignSuspendedInvocationSummary =
        SovereignSuspendedInvocationSummary(
            suspendedInvocationId = approvalId,
            workflowRunId = identity.workflowRunId,
            correlationId = identity.correlationId,
            serviceName = operationReference.serviceInterface,
            operationName = operationReference.methodName,
            status = "SUSPENDED",
            replayEnvelopeDigest = replayEnvelopeDigest.value,
        )
}

/** @see DefaultSovereignSuspendedInvocationOperations */
private const val ERROR_INVALID_SUSPENDED_INVOCATION_ID = "tramai-sovereign-ops-invalid-suspended-invocation-id"
