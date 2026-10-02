package dev.tramai.build.quality

/**
 * Certificate consumption: M40-M43 (0.7.1g1P2).
 *
 * These rules produce a **positive, attributed fact** — [CertificateConsumption.Valid] — and the only
 * way to obtain one is through [verifyCertificateConsumption], which derives every predicate from real
 * authority inputs: the base certificate ledger, the cited admission, the exact base authorization
 * set and the verifier's own fresh measurement.
 *
 * That matters because lifecycle rules must not be able to act on a claim:
 *
 * - M44 and M47 take `Set<CertificateConsumption.Valid>`, never raw digests, so a caller cannot
 *   state "trust me, this digest was consumed". The attribution is carried by the value.
 * - M43 provenance is derived here by looking the certificate up in the base ledger. There is no
 *   `fromBase` flag for a caller to assert, so the Boolean cannot drift from the truth.
 * - The Valid constructor is `private` (with `@ConsistentCopyVisibility` on the class), so no code
 *   outside the verification itself can construct one - not even other files in this module, which
 *   is where the Gradle call sites live. The proof and the fact share a scope by construction: there
 *   is no path to a Valid that skips M43-M42. This is deliberately not a capability system: the goal
 *   is that no ordinary call site can accidentally manufacture consumption authority.
 *
 * Consumption is bounded, not a licence. A certificate names one exact source digest, one exact
 * target digest and one exact admission set, and it may only be consumed by a transition whose fresh
 * measurement actually projects to that target:
 *
 * ```
 * M43  the certificate is present in the base with the payload it was minted with (the consuming
 *      candidate cannot invent the semantic upgrade it consumes - the M31 analogue)
 * M40  toDigest == the verifier's fresh authority projection   (never candidate-supplied: T7)
 * M41  fromDigest == the cited admission's own populationDigest (the historical authority)
 * M42  admissionSetDigest == the exact base authorization set  (bounded: no different, later set)
 * ```
 *
 * Every predicate is fail-closed: a missing or mismatched value yields [CertificateConsumption.Invalid],
 * never a pass by omission. Each predicate is its own function so the refusal order is readable, and
 * the whole consumption is one chain: the first predicate that refuses decides, and only a
 * certificate satisfying all of them yields `Valid`.
 */
sealed interface CertificateConsumption {
    /**
     * The proven fact: this certificate validly authorises this migration in this transition.
     *
     * The constructor is **private**, so no code outside this class - including other files in the
     * same module, which is where the Gradle call sites live - can construct one. The only production
     * path is [Valid.verify], which is the verification itself: it derives M43 provenance from the
     * base ledger and runs M40-M42 before the fact exists. `@ConsistentCopyVisibility` keeps the
     * generated `copy()` private too, so it is not an escape hatch either.
     */
    @ConsistentCopyVisibility
    data class Valid private constructor(
        val fromDigest: String,
        val toDigest: String,
        val admissionSetDigest: String,
    ) : CertificateConsumption {
        companion object {
            /**
             * M43 + M40 + M41 + M42, derived from base authority. The only way a [Valid] comes into
             * existence.
             *
             * @param certificate the certificate being cited
             * @param baseCertificates the certificate ledger as it exists in the authority base. M43
             *   is derived from it, not asserted: a certificate that is absent, or whose enforced
             *   payload differs from the base copy, cannot be consumed.
             * @param citedAdmissionPopulationDigest the `populationDigest` of the admission being
             *   consumed (M41).
             * @param baseAdmissionIdentities the exact identities authorised in the base (M42).
             * @param freshAuthorityProjectionHash the authority-v2 projection the verifier computed
             *   from its own fresh measurement (M40). Callers must pass measured data, never
             *   candidate data.
             */
            internal fun verify(
                certificate: MutationAuthorityDigestCertificate,
                baseCertificates: MutationAuthorityDigestCertificates,
                citedAdmissionPopulationDigest: String,
                baseAdmissionIdentities: List<String>,
                freshAuthorityProjectionHash: String,
            ): CertificateConsumption {
                val inBase = baseCertificates.byFromDigest()[certificate.fromDigest]
                val refusal =
                    provenance(certificate, inBase)
                        ?: targetDigest(certificate, freshAuthorityProjectionHash)
                        ?: algorithmPair(certificate)
                        ?: sourceDigest(certificate, citedAdmissionPopulationDigest)
                        ?: admissionSet(certificate, baseAdmissionIdentities)
                return refusal?.let { CertificateConsumption.Invalid(it) }
                    ?: Valid(
                        fromDigest = certificate.fromDigest,
                        toDigest = certificate.toDigest,
                        admissionSetDigest = certificate.admissionSetDigest,
                    )
            }
        }
    }

    /** The refusal, carrying the named rule that rejected it. */
    data class Invalid(
        val diagnostic: VerificationDiagnostic,
    ) : CertificateConsumption
}

/**
 * The consumption entry point for call sites: delegates to [CertificateConsumption.Valid.verify], so
 * there is exactly one implementation of the proof and no second route to a fact.
 */
fun verifyCertificateConsumption(
    certificate: MutationAuthorityDigestCertificate,
    baseCertificates: MutationAuthorityDigestCertificates,
    citedAdmissionPopulationDigest: String,
    baseAdmissionIdentities: List<String>,
    freshAuthorityProjectionHash: String,
): CertificateConsumption =
    CertificateConsumption.Valid.verify(
        certificate = certificate,
        baseCertificates = baseCertificates,
        citedAdmissionPopulationDigest = citedAdmissionPopulationDigest,
        baseAdmissionIdentities = baseAdmissionIdentities,
        freshAuthorityProjectionHash = freshAuthorityProjectionHash,
    )

/**
 * M43, derived from the base ledger rather than asserted by the caller. Two distinct failures: the
 * certificate is not in the base at all (the transition is minting the authority it consumes), or it
 * is present but its enforced payload differs (the consumption cites something other than the
 * certificate that was minted).
 */
private fun provenance(
    certificate: MutationAuthorityDigestCertificate,
    inBase: MutationAuthorityDigestCertificate?,
): VerificationDiagnostic? =
    when {
        inBase == null -> {
            failure(
                certificate,
                "M43: the digest-migration certificate for source digest " +
                    "${short(certificate.fromDigest)} is not present in the authority base, so this " +
                    "transition would consume migration authority it introduces itself. Migration " +
                    "authority must already exist in the base.",
            )
        }

        inBase.enforcedPayload() != certificate.enforcedPayload() -> {
            failure(
                certificate,
                "M43: the digest-migration certificate for source digest " +
                    "${short(certificate.fromDigest)} does not match the certificate present in the " +
                    "authority base. A consumption must cite the base certificate as it was minted.",
            )
        }

        else -> {
            null
        }
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
                "${short(certificate.toDigest)}, but this transition's fresh measurement projects to " +
                "${short(freshAuthorityProjectionHash)}. A certificate authorises one exact " +
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
                "'${MutationAuthorityDigestCertificates.ALGORITHM_AUTHORITY_V2}' authority upgrade " +
                "this consumption requires.",
        )
    }

private fun sourceDigest(
    certificate: MutationAuthorityDigestCertificate,
    citedAdmissionPopulationDigest: String,
): VerificationDiagnostic? =
    if (certificate.fromDigest == citedAdmissionPopulationDigest) {
        null
    } else {
        failure(
            certificate,
            "M41: the digest-migration certificate for source digest " +
                "${short(certificate.fromDigest)} does not match the cited admission's historical " +
                "population digest ${short(citedAdmissionPopulationDigest)}. A certificate " +
                "translates the authority the admission was actually minted under, not a different " +
                "one.",
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
            "${baseAdmissionIdentities.size} base authorizations (${short(bound)}). The certificate " +
            "is bounded: it cannot cover a different or later admission set.",
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
