package dev.tramai.testing.persistence.approval

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.Sha256Digest
import java.time.Instant

/**
 * Fixture factory for the ApprovalStore TCK. The TCK owns approval IDs,
 * timestamps, token digests, actors, and expected state transitions — the
 * runner owns storage technology (temp dirs, keys, datasources).
 */
object ApprovalStoreFixtures {

    /** Fixed digest: sha256:<64 x 'a'> — valid but NOT the stored token digest. */
    fun digest(hex: String = "a".repeat(64)): Sha256Digest = Sha256Digest.of("sha256:$hex")

    /** The digest the store will verify against: sha256:<64 x 'f'>. */
    fun validTokenDigest(): Sha256Digest = digest("f".repeat(64))

    /** A digest guaranteed to mismatch [validTokenDigest]. */
    fun wrongTokenDigest(): Sha256Digest = digest("e".repeat(64))

    fun binding(
        workflowRunId: String = "wf-run-1",
        toolName: String = "execute",
        argumentsDigest: Sha256Digest = digest("b".repeat(64)),
        policyVersion: String = "v1",
        workflowDigest: Sha256Digest = digest("c".repeat(64)),
        approvalTokenDigest: Sha256Digest = validTokenDigest(),
    ): ApprovalBinding = ApprovalBinding(
        workflowRunId = workflowRunId,
        toolName = toolName,
        argumentsDigest = argumentsDigest,
        policyVersion = policyVersion,
        workflowDigest = workflowDigest,
        approvalTokenDigest = approvalTokenDigest,
    )

    fun pending(
        approvalId: String,
        requestedAt: Instant,
        expiresAt: Instant,
        requestedBy: String = "requester-1",
        binding: ApprovalBinding = binding(),
    ): ApprovalRequest = ApprovalRequest(
        approvalId = approvalId,
        binding = binding,
        status = ApprovalStatus.PENDING,
        requestedBy = requestedBy,
        requestedAt = requestedAt,
        expiresAt = expiresAt,
        decidedBy = null,
        decidedAt = null,
        decisionComment = null,
        consumedBy = null,
        consumedAt = null,
        version = 0L,
    )
}
