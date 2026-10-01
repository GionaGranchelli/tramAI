package dev.tramai.build.quality

/**
 * The digest-migration certificate's authority lifecycle (0.7.1g1P2, M45-M47).
 *
 * The certificate is itself base authority, so its whole lifecycle is fail-closed, not only its
 * consumption. These three rules are the lifecycle half; M40-M44 are consumption rules and arrive
 * with the certificate-aware consumption path.
 *
 * ```
 * mint against the exact base        (M45)  <- this file
 *   -> retain byte-identically       (M46)  <- this file
 *   -> consume only from base        (M43 + M42 + M41 + M40)
 *   -> remove in the valid consuming transition  (M44 + M47)  <- M47's consuming half is here
 * ```
 *
 * Written standalone over the two ledgers plus the authority [baseSha], because a lifecycle rule is
 * a transition property: it cannot be expressed by parsing one file, and it needs no coupling to the
 * ratchet's own types.
 */
object MutationAuthorityDigestCertificateCeremony {
    /**
     * M45, M46 and M47 over the base/candidate certificate ledgers.
     *
     * @param base the certificate ledger as it exists in the PR's authority base
     * @param candidate the certificate ledger as the PR proposes it
     * @param baseSha the exact authority base SHA this transition is proposed against
     */
    fun checks(
        base: MutationAuthorityDigestCertificates,
        candidate: MutationAuthorityDigestCertificates,
        baseSha: String,
    ): List<VerificationDiagnostic> =
        mintChecks(base, candidate, baseSha) +
            retentionChecks(base, candidate) +
            removalChecks(base, candidate)

    /**
     * M45: a newly introduced certificate binds the exact authority base it is proposed against.
     * Anti-replay at mint time: a certificate payload cannot be replayed onto a different authority
     * base. Deliberately not re-checked later - a certificate legitimately survives intermediate
     * merges, so requiring it to track the immediate base would make delayed consumption impossible.
     */
    private fun mintChecks(
        base: MutationAuthorityDigestCertificates,
        candidate: MutationAuthorityDigestCertificates,
        baseSha: String,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseByFromDigest = base.byFromDigest()
        for ((fromDigest, certificate) in candidate.byFromDigest()) {
            if (fromDigest in baseByFromDigest) continue
            if (certificate.fromBaseSha != baseSha) {
                diagnostics +=
                    certificateFailure(
                        fromDigest,
                        "M45: the digest-migration certificate for source digest ${short(fromDigest)} " +
                            "records fromBaseSha '${certificate.fromBaseSha}', not the authority base " +
                            "'$baseSha' this transition is proposed against. fromBaseSha is the base of " +
                            "the minting transition.",
                    )
            }
        }
        return diagnostics
    }

    /**
     * M46: a certificate present in the base is immutable from the moment it is introduced. A
     * retained copy differing in any field of its enforced payload is a rewrite, whether or not the
     * migration it authorizes is being consumed in this transition. Correcting a certificate means
     * minting a new one in a later transition, never editing a retained one.
     *
     * Judged over [MutationAuthorityDigestCertificate.enforcedPayload], so touching audit metadata
     * alone never fails - and never manufactures authority either.
     */
    private fun retentionChecks(
        base: MutationAuthorityDigestCertificates,
        candidate: MutationAuthorityDigestCertificates,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val candidateByFromDigest = candidate.byFromDigest()
        for ((fromDigest, baseCertificate) in base.byFromDigest()) {
            val candidateCertificate = candidateByFromDigest[fromDigest] ?: continue
            if (baseCertificate.enforcedPayload() != candidateCertificate.enforcedPayload()) {
                diagnostics +=
                    certificateFailure(
                        fromDigest,
                        "M46: the retained digest-migration certificate for source digest " +
                            "${short(fromDigest)} does not match the base certificate. A certificate, " +
                            "once introduced, is immutable.",
                    )
            }
        }
        return diagnostics
    }

    /**
     * M47: removal custody. A certificate may only disappear by being consumed; it may not be
     * cancelled silently.
     *
     * Until the certificate-aware consumption path exists there is no way to *prove* a valid
     * consumption, so this rule takes its strictly fail-closed half: **any** base certificate absent
     * from the candidate fails. That is deliberate, not an unfinished branch. A disappearance is
     * never its own evidence - when M40-M44 land, this becomes "unless validly consumed by the same
     * transition", judged against an independently established consumption, and never against the
     * mere absence of the certificate.
     */
    private fun removalChecks(
        base: MutationAuthorityDigestCertificates,
        candidate: MutationAuthorityDigestCertificates,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val candidateByFromDigest = candidate.byFromDigest()
        for ((fromDigest, _) in base.byFromDigest()) {
            if (fromDigest !in candidateByFromDigest) {
                diagnostics +=
                    certificateFailure(
                        fromDigest,
                        "M47: the digest-migration certificate for source digest ${short(fromDigest)} " +
                            "was removed without being consumed. A certificate may not be cancelled " +
                            "silently.",
                    )
            }
        }
        return diagnostics
    }

    private fun certificateFailure(
        fromDigest: String,
        message: String,
    ): VerificationDiagnostic =
        VerificationDiagnostic.failure(
            DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
            message,
            findingId = fromDigest,
        )

    private fun short(digest: String): String = digest.take(SHORT_HASH_LENGTH)

    /** Prefix length used to identify a digest in diagnostics; the full value stays in findingId. */
    private const val SHORT_HASH_LENGTH = 8
}
