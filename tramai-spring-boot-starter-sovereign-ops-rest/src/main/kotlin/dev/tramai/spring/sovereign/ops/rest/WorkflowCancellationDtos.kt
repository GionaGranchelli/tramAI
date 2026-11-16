package dev.tramai.spring.sovereign.ops.rest

/**
 * Request body for POST workflow cancellation endpoint (0.7.7m).
 *
 * [expectedRevision] is nullable only so the controller can answer a missing or negative value
 * with a clean 400 instead of letting Jackson coercion decide.
 */
data class CancelWorkflowRunRequest(
    val expectedRevision: Long? = null,
    val reason: String? = null,
    val correlationId: String? = null,
)

/**
 * Safe typed REST response for workflow cancellation endpoint (0.7.7m).
 *
 * Never exposes internal stack traces, encryption nonces, or sensitive credentials.
 */
data class WorkflowCancellationResponse(
    val status: String,
    val workflowName: String,
    val runId: String,
    val revision: Long? = null,
    val expectedRevision: Long? = null,
    val currentRevision: Long? = null,
    val cancelledBy: String? = null,
    val cancelledAtEpochMillis: Long? = null,
    val alreadyCancelled: Boolean = false,
    val message: String? = null,
)
