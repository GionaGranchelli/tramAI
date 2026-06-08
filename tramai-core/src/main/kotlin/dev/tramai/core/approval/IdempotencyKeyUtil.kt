package dev.tramai.core.approval

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Utility for deriving deterministic idempotency keys for approval-gated tool execution.
 *
 * The key is derived from the approval ID, tool call ID, and arguments digest —
 * all immutable binding fields established before tool execution.
 *
 * Rules:
 * - Normal non-approved tool calls do not use these keys.
 * - Resumed approval-gated tool calls receive a stable key.
 * - Retries of an idempotent tool reuse the same key.
 * - External tools must actively honor the key for deduplication.
 * - Raw arguments never appear in key derivation output, logs, or exceptions.
 */
object IdempotencyKeyUtil {

    /**
     * Derive a deterministic idempotency key for an approval-gated tool execution.
     *
     * @param approvalId The approval ID of the suspended execution.
     * @param toolCallId The tool call ID within the provider response.
     * @param argumentsDigest The SHA-256 digest of the tool arguments.
     * @return A hex-encoded SHA-256 hash of the concatenated inputs.
     */
    fun deriveApprovalKey(
        approvalId: String,
        toolCallId: String,
        argumentsDigest: Sha256Digest,
    ): String {
        val raw = "$approvalId:$toolCallId:${argumentsDigest.value}"
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns a safe redacted description for logging purposes.
     * Contains only the approval ID — no raw values, tokens, or digests.
     */
    fun describeKeySource(approvalId: String): String = "approval-key:$approvalId"
}
