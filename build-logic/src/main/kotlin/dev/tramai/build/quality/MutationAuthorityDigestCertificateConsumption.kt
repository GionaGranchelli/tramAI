package dev.tramai.build.quality

/**
 * Certificate consumption: M40-M43 (0.7.1g1P2).
 *
 * These rules establish a **positive** fact — "this certificate validly authorises this migration in
 * this transition" — before anything acts on it. M44 and M47 consume that established fact; neither
 * of them re-derives it. That separation is deliberate: if removal custody re-ran the semantic
 * checks itself, the two implementations could drift, and a disappearance could quietly become its
 * own evidence. Here the only way to reach the fact is to pass every predicate below.
 *
 * Consumption is bounded, not a licence. A certificate names one exact source digest, one exact
 * target digest and one exact admission set, and it may only be consumed by a transition whose fresh
 * measurement actually projects to that target:
 *
 * ```
 * M43  the certificate already existed in the base   (the consuming candidate cannot invent the
 *      semantic upgrade it consumes - the M31 analogue)
 * M40  toDigest == the verifier's fresh authority projection   (never candidate-supplied: T7)
 * M41  fromDigest == the cited admission's own populationDigest (the historical authority)
 * M42  admissionSetDigest == the exact base authorization set  (bounded: no different, later set)
 * ```
 *
 * Every predicate is fail-closed: a missing or mismatched value yields a diagnostic, never a pass by
 * omission. Each predicate is its own function so the refusal order is readable, and so the whole
 * consumption is one chain: the first predicate that refuses is the diagnostic, and only a
 * certificate that satisfies all of them yields `null` (the valid consumption).
 */
object MutationAuthorityDigestCertificateConsumption {
    /**
     * M43 + M40 + M41 + M42 for one candidate consumption. Returns `null` when the consumption is
     * valid, otherwise the diagnostic that refuses it.
     *
     * @param certificate the certificate being cited
     * @param certificateFromBase whether that certificate is present in the base ledger. This is
     *   M43, checked explicitly rather than assumed from how the caller found the certificate, so
     *   provenance stays a named rule in diagnostics and tests.
     * @param freshAuthorityProjectionHash the authority-v2 projection the verifier computed from
     *   its own fresh measurement (M40). Callers must pass measured data, never candidate data.
     * @param citedPopulationDigest the `populationDigest` of the admission being consumed (M41).
     * @param baseAdmissionIdentities the exact identities authorised in the base (M42).
     */
    fun verify(
        certificate: MutationAuthorityDigestCertificate,
        certificateFromBase: Boolean,
        freshAuthorityProjectionHash: String,
        citedPopulationDigest: String,
        baseAdmissionIdentities: List<String>,
    ): VerificationDiagnostic? =
        provenance(certificate, certificateFromBase)
            ?: targetDigest(certificate, freshAuthorityProjectionHash)
            ?: algorithmPair(certificate)
            ?: sourceDigest(certificate, citedPopulationDigest)
            ?: admissionSet(certificate, baseAdmissionIdentities)

    private fun provenance(
        certificate: MutationAuthorityDigestCertificate,
        certificateFromBase: Boolean,
    ): VerificationDiagnostic? =
        if (certificateFromBase) {
            null
        } else {
            failure(
                certificate,
                "M43: the digest-migration certificate for source digest " +
                    "${short(certificate.fromDigest)} is cited for consumption by the same transition " +
                    "that introduces it. Migration authority must already exist in the base: a " +
                    "candidate cannot mint the semantic upgrade it consumes.",
            )
        }

    private fun targetDigest(
        certificate: MutationAuthorityDigestCertificate,
        freshAuthorityProjectionHash: String,
    ): VerificationDiagnostic? =
        if (certificate.toDigest == freshAuthorityProjectionHash) {
            null
        } else {
            failure(
                certificate,
                "M40: the digest-migration certificate for source digest " +
                    "${short(certificate.fromDigest)} certifies target digest " +
                    "${short(certificate.toDigest)}, but this transition's fresh measurement projects " +
                    "to ${short(freshAuthorityProjectionHash)}. A certificate authorises one exact " +
                    "migration, not any migration.",
            )
        }

    private fun algorithmPair(certificate: MutationAuthorityDigestCertificate): VerificationDiagnostic? =
        if (certificate.fromAlgorithm == MutationAuthorityDigestCertificates.ALGORITHM_RAW_V1 &&
            certificate.toAlgorithm == MutationAuthorityDigestCertificates.ALGORITHM_AUTHORITY_V2
        ) {
            null
        } else {
            failure(
                certificate,
                "M41: the digest-migration certificate for source digest " +
                    "${short(certificate.fromDigest)} declares the migration " +
                    "'${certificate.fromAlgorithm}' -> '${certificate.toAlgorithm}', which is not the " +
                    "'${MutationAuthorityDigestCertificates.ALGORITHM_RAW_V1}' -> " +
                    "'${MutationAuthorityDigestCertificates.ALGORITHM_AUTHORITY_V2}' authority " +
                    "upgrade this consumption requires.",
            )
        }

    private fun sourceDigest(
        certificate: MutationAuthorityDigestCertificate,
        citedPopulationDigest: String,
    ): VerificationDiagnostic? =
        if (certificate.fromDigest == citedPopulationDigest) {
            null
        } else {
            failure(
                certificate,
                "M41: the digest-migration certificate for source digest " +
                    "${short(certificate.fromDigest)} does not match the cited admission's historical " +
                    "population digest ${short(citedPopulationDigest)}. A certificate translates the " +
                    "authority the admission was actually minted under, not a different one.",
            )
        }

    private fun admissionSet(
        certificate: MutationAuthorityDigestCertificate,
        baseAdmissionIdentities: List<String>,
    ): VerificationDiagnostic? {
        val bound = MutationAuthorityDigestCertificates.admissionSetDigest(baseAdmissionIdentities)
        if (certificate.admissionSetDigest == bound) return null
        return failure(
            certificate,
            "M42: the digest-migration certificate for source digest " +
                "${short(certificate.fromDigest)} binds admission set " +
                "${short(certificate.admissionSetDigest)}, which is not the exact set of " +
                "${baseAdmissionIdentities.size} base authorizations (${short(bound)}). The " +
                "certificate is bounded: it cannot cover a different or later admission set.",
        )
    }

    private fun failure(
        certificate: MutationAuthorityDigestCertificate,
        message: String,
    ): VerificationDiagnostic =
        VerificationDiagnostic.failure(
            DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
            message,
            findingId = certificate.fromDigest,
        )

    private fun short(digest: String): String = digest.take(SHORT_HASH_LENGTH)

    /** Prefix length used to identify a digest in diagnostics; the full value stays in findingId. */
    private const val SHORT_HASH_LENGTH = 8
}
