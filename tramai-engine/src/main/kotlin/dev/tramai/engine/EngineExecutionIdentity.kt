package dev.tramai.engine

import dev.tramai.core.approval.Sha256Digest

/**
 * Identity context for one concrete engine invocation.
 *
 * Created at the beginning of one engine invocation and threaded through
 * the provider loop, tool loop, suspension path, and resume path.
 * Do not regenerate values during resume.
 *
 * @property workflowRunId Identifies one concrete engine invocation (not regenerated on resume).
 * @property correlationId Correlation ID linking related operations.
 * @property workflowDigest Deterministic SHA-256 over the canonical operation definition.
 * @property policyVersion Policy version active at the time of invocation.
 * @property actorId Identity of the caller or service account.
 */
data class EngineExecutionIdentity(
    val workflowRunId: String,
    val correlationId: String,
    val workflowDigest: Sha256Digest,
    val policyVersion: String,
    val actorId: String,
)
