package dev.tramai.testing.persistence.approval.continuation

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import java.security.MessageDigest
import java.time.Instant

/**
 * Fixture factory for the ApprovalContinuationStore TCK. The TCK owns IDs,
 * timestamps, arguments, digests, and actors — the runner owns storage
 * technology (temp dirs, keys, datasources).
 */
object ApprovalContinuationFixtures {

    val DEFAULT_ARGUMENTS: String = "{\"tool\":\"execute\",\"params\":{\"query\":\"select 1\"}}"

    fun digest(hex: String = "a".repeat(64)): Sha256Digest = Sha256Digest.of("sha256:$hex")

    /** SHA-256 of [raw] — the digest the stores verify against stored arguments. */
    fun argumentsDigest(raw: String): Sha256Digest {
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return Sha256Digest.of("sha256:" + bytes.joinToString("") { "%02x".format(it) })
    }

    fun arguments(raw: String = DEFAULT_ARGUMENTS): SensitiveToolArguments = SensitiveToolArguments.of(raw)

    fun continuation(
        approvalId: String,
        createdAt: Instant,
        expiresAt: Instant,
        rawArguments: String = DEFAULT_ARGUMENTS,
        workflowRunId: String = "wf-run-1",
        correlationId: String = "corr-1",
        toolCallId: String = "tc-1",
        toolName: String = "execute",
        policyVersion: String = "v1",
    ): ApprovalContinuation = ApprovalContinuation(
        approvalId = approvalId,
        workflowRunId = workflowRunId,
        correlationId = correlationId,
        toolCallId = toolCallId,
        toolName = toolName,
        argumentsDigest = argumentsDigest(rawArguments),
        policyVersion = policyVersion,
        workflowDigest = digest("b".repeat(64)),
        status = ApprovalContinuationStatus.PENDING,
        createdAt = createdAt,
        approvalExpiresAt = expiresAt,
        claimedBy = null,
        claimedAt = null,
        completedAt = null,
        recoveryResolvedBy = null,
        recoveryResolvedAt = null,
        recoveryReasonCode = null,
        version = 0L,
    )
}
