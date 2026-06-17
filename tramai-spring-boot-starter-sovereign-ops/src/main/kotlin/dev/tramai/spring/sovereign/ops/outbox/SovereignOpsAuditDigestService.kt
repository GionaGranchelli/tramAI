package dev.tramai.spring.sovereign.ops.outbox

import java.security.MessageDigest

/**
 * Digest service for sovereign ops audit data.
 *
 * Keeps digest computation behind an interface so the hashing algorithm
 * can be upgraded (e.g. from plain SHA-256 to HMAC-SHA256) without
 * changing the audit emitter, outbox, or mutation code.
 */
interface SovereignOpsAuditDigestService {

    /**
     * Produce a safe, bounded digest for an approval identifier.
     * The result is NOT the raw approvalId and must not be reversible.
     */
    fun approvalIdDigest(approvalId: String): String

    /**
     * Produce a safe, bounded digest of a reason string.
     * The result is NOT the raw reason and must not be reversible.
     */
    fun reasonDigest(reason: String): String
}

/**
 * Default digest service using plain SHA-256.
 *
 * This is acceptable as a starting point. For production deployments
 * where reason-digest correlation is a concern, replace with an
 * HMAC-SHA256 implementation keyed to a private audit secret.
 */
object DefaultSovereignOpsAuditDigestService : SovereignOpsAuditDigestService {

    private const val APPROVAL_ID_PREFIX = "sovereign-ops-approval:"
    private const val REASON_PREFIX = "sovereign-ops-reason:"

    override fun approvalIdDigest(approvalId: String): String =
        sha256Hex("$APPROVAL_ID_PREFIX$approvalId")

    override fun reasonDigest(reason: String): String =
        sha256Hex("$REASON_PREFIX$reason")

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
