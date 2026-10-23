package dev.tramai.build.quality

import java.security.MessageDigest

/**
 * Base-side digest-migration certificate (0.7.1g1P2, M40-M47).
 *
 * This is the artifact that carries a *semantic* upgrade of the whole-population authority digest
 * without rewriting historical authority. The P1 authorizations are bound to `raw-v1` raw-status
 * digests and must stay byte-identical; the certificate stands beside them and states, in base
 * authority, that one exact `fromDigest` is equivalent to one exact `toDigest` for one exact,
 * bounded set of authorizations. M36's payload comparison never sees it, because it lives in its
 * own ledger - no exception branch, no permitted-field list, is added to the admissions rules.
 *
 * ```
 * mint against the exact base        (M45)
 *   -> retain byte-identically       (M46)
 *   -> consume only from base        (M43 + M42 + M41 + M40)
 *   -> remove in the valid consuming transition  (M44 + M47)
 * ```
 *
 * ## Field semantics
 *
 * - [fromAlgorithm] / [fromDigest] - the digest semantics the covered authorizations were minted
 *   under, and that exact digest. M41 requires this to match the cited admission's
 *   `populationDigest`; a certificate can never be applied to a different authorization than the
 *   one whose digest it names.
 * - [toAlgorithm] / [toDigest] - the digest semantics the covered authorizations are to be consumed
 *   under, and that exact digest. M40 requires [toDigest] to equal the verifier's own fresh
 *   authority projection, never anything the candidate supplies.
 * - [admissionSetDigest] - SHA-256 over the exact set of covered identities, sorted and joined with
 *   `"\n"` with a trailing newline (the same recipe this repository uses for manifest digests). M42
 *   requires it to equal the exact set of base authorizations, which is what makes the certificate
 *   *bounded*: it cannot be widened to cover a different or later set.
 * - [fromBaseSha] - the authority base the certificate is minted against. **Enforced at
 *   introduction only (M45); thereafter immutable provenance.** Same semantics as
 *   [MutationPopulationAdmission.fromBaseSha]: a certificate legitimately survives intermediate
 *   merges, so requiring it to track the immediate base would make delayed consumption impossible;
 *   its enforcement value is anti-replay at mint time.
 * - [reason] - the recorded provenance of the supersession, part of the enforced payload.
 * - [authorizedBy], [authorizedAt] - **audit only, no enforcement value.** Recording them is fine;
 *   letting identity or actor carry trust would make them a bypass.
 *
 * ## Rule ownership
 *
 * M45 (mint binding), M46 (retained immutability) and M47 (removal custody) are lifecycle rules and
 * live in [MutationAuthorityDigestCertificateCeremony]. M40-M44 are *consumption* rules and arrive
 * with the certificate-aware consumption path; until then a base certificate may not disappear at
 * all, which is fail-closed and is the stricter half of M47.
 */
data class MutationAuthorityDigestCertificate(
    val fromAlgorithm: String,
    val fromDigest: String,
    val toAlgorithm: String,
    val toDigest: String,
    val admissionSetDigest: String,
    val fromBaseSha: String,
    val reason: String,
    val authorizedBy: String? = null,
    val authorizedAt: String? = null,
) {
    /**
     * The enforceable payload: every bound field except the audit-only ones. Base/candidate
     * byte-identity is judged over this (M46), so rewriting any bound field fails while touching
     * audit metadata alone can never manufacture authority.
     */
    fun enforcedPayload(): List<Any?> =
        listOf(
            fromAlgorithm,
            fromDigest,
            toAlgorithm,
            toDigest,
            admissionSetDigest,
            fromBaseSha,
            reason,
        )
}

data class MutationAuthorityDigestCertificates(
    val schemaVersion: String,
    val certificates: List<MutationAuthorityDigestCertificate>,
) {
    /**
     * Keyed by [MutationAuthorityDigestCertificate.fromDigest]: the source of a migration is
     * unique. Two certificates claiming the same source digest would contradict each other, and the
     * loader rejects that rather than leaving the winner to map iteration order.
     */
    fun byFromDigest(): Map<String, MutationAuthorityDigestCertificate> = certificates.associateBy { it.fromDigest }

    companion object {
        /** The digest semantics a certificate may migrate from and to. */
        const val ALGORITHM_RAW_V1 = "raw-v1"
        const val ALGORITHM_AUTHORITY_V2 = "authority-v2"

        /** Byte mask for the hex rendering of a digest. */
        private const val HEX_BYTE_MASK = 0xFF

        /** No certificates: strictly the most restrictive state, so no migration is possible. */
        val NONE = MutationAuthorityDigestCertificates(schemaVersion = "1", certificates = emptyList())

        /**
         * The bounded admission-set digest (M42): SHA-256 over the covered identities, sorted and
         * joined with `"\n"` with a trailing newline.
         *
         * The recipe is part of the contract, not an implementation detail: whoever mints a
         * certificate must be able to reproduce this value exactly from the authorization set, and a
         * test pins it against the real ledger.
         */
        fun admissionSetDigest(identities: Collection<String>): String {
            val payload = identities.sorted().joinToString("\n", postfix = if (identities.isEmpty()) "" else "\n")
            return MessageDigest
                .getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and HEX_BYTE_MASK) }
        }
    }
}
