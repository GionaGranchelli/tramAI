package dev.tramai.core.operator

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Canonical durable digest of a verified operator identity -- `(issuer, subject)` -- for 0.7.6f.
 *
 * **Why this exists.** A subject alone is only unique *within* an issuer, so a durable record keyed on
 * the subject digest cannot distinguish two operators who share a subject string across two identity
 * providers. This digest binds both halves into one stored value
 * (`approvals.decision_actor_identity_digest`, `SovereignOpsAuditOutboxRecord.actorIdentityDigest`).
 *
 * **The encoding is fixed, not configurable.** The output is persisted and compared as text, so a
 * deployment-specific variant would silently make two systems' evidence incomparable. The input is
 * domain-separated and length-prefixed rather than delimited, so no issuer or subject value -- including
 * one containing a separator -- can be re-split or collide with a different `(issuer, subject)` pair:
 *
 * ```
 * SHA-256( "tramai/actor-identity" ‖ 0x00 ‖ u32be(1) ‖ u32be(len(issuer))  ‖ issuer  (UTF-8)
 *                                             ‖ u32be(len(subject)) ‖ subject (UTF-8) )
 * ```
 *
 * The version integer sits inside the digest input, so a future v2 encoding can never collide with v1.
 *
 * **Output form.** 64 lowercase hexadecimal characters, **no prefix**. This is deliberately different
 * from the `sha256:`-prefixed digests that predate 0.7.6f (`decision_actor_hash`, `aggregateIdDigest`,
 * `reasonDigest`), which are frozen in their existing textual form; a reader can therefore tell which
 * encoding it is holding.
 *
 * A [String] identity that is empty is rejected rather than hashed: an empty issuer would make the
 * digest claim a provenance the caller does not have.
 */
object ActorIdentityDigest {
    private const val DOMAIN: String = "tramai/actor-identity"
    private const val ENCODING_VERSION: Int = 1

    /**
     * Digest of the verified identity pair.
     *
     * @throws IllegalArgumentException if [issuer] or [subject] is empty.
     */
    fun of(
        issuer: String,
        subject: String,
    ): String {
        require(issuer.isNotEmpty()) { "tramai-actor-identity-issuer-blank" }
        require(subject.isNotEmpty()) { "tramai-actor-identity-subject-blank" }
        val bytes =
            ByteArrayOutputStream()
                .also { buffer ->
                    DataOutputStream(buffer).use { out ->
                        out.write(DOMAIN.toByteArray(StandardCharsets.UTF_8))
                        out.writeByte(0)
                        out.writeInt(ENCODING_VERSION)
                        out.writeLengthPrefixed(issuer)
                        out.writeLengthPrefixed(subject)
                    }
                }.toByteArray()
        return MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun DataOutputStream.writeLengthPrefixed(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }
}
