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
     * M44, M45, M46 and M47 over the base/candidate certificate ledgers.
     *
     * @param base the certificate ledger as it exists in the PR's authority base
     * @param candidate the certificate ledger as the PR proposes it
     * @param baseSha the exact authority base SHA this transition is proposed against
     * @param validConsumptions the source digests of certificates whose consumption in this
     *   transition was **independently established** by M40-M43. This is the fact M44 and M47 act on:
     *   they never re-derive it, and they never treat the absence of a certificate as evidence that
     *   it was consumed. An empty set is the fail-closed state, so a caller that establishes nothing
     *   gets the strictest behaviour.
     */
    fun checks(
        base: MutationAuthorityDigestCertificates,
        candidate: MutationAuthorityDigestCertificates,
        baseSha: String,
        validConsumptions: Set<String> = emptySet(),
    ): List<VerificationDiagnostic> =
        mintChecks(base, candidate, baseSha) +
            retentionChecks(base, candidate) +
            singleUseChecks(base, candidate, validConsumptions) +
            removalChecks(base, candidate, validConsumptions)

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
     * M44: single use. A certificate that was consumed in this transition must not also be retained.
     * Leaving it in place would let the same migration authority be consumed again by a later
     * transition, so consumption and retention are mutually exclusive.
     *
     * Judged only over consumptions that were **independently established** (M40-M43). A source
     * digest that is not in the base is not this rule's business - M43 refuses that citation - so a
     * candidate cannot use this rule to fail a transition by citing certificates that do not exist.
     */
    private fun singleUseChecks(
        base: MutationAuthorityDigestCertificates,
        candidate: MutationAuthorityDigestCertificates,
        validConsumptions: Set<String>,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseByFromDigest = base.byFromDigest()
        val candidateByFromDigest = candidate.byFromDigest()
        for (fromDigest in validConsumptions.sorted()) {
            if (fromDigest !in baseByFromDigest) continue
            if (fromDigest in candidateByFromDigest) {
                diagnostics +=
                    certificateFailure(
                        fromDigest,
                        "M44: the digest-migration certificate for source digest " +
                            "${short(fromDigest)} was consumed by this transition but is retained in " +
                            "the candidate. A certificate authorises one migration once.",
                    )
            }
        }
        return diagnostics
    }

    /**
     * M47: removal custody. A certificate may only disappear by being consumed; it may not be
     * cancelled silently.
     *
     * The consuming half is [validConsumptions] - the fact established by M40-M43, taken here as an
     * input rather than rediscovered. That is structural, not conventional: this function has no
     * access to the semantic predicates, so a disappearance can never become its own evidence. If
     * nothing valid was established (empty set, including the fail-closed default), every base
     * certificate absent from the candidate fails, exactly as before the consumption path existed.
     */
    private fun removalChecks(
        base: MutationAuthorityDigestCertificates,
        candidate: MutationAuthorityDigestCertificates,
        validConsumptions: Set<String>,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val candidateByFromDigest = candidate.byFromDigest()
        for ((fromDigest, _) in base.byFromDigest()) {
            if (fromDigest !in candidateByFromDigest && fromDigest !in validConsumptions) {
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
