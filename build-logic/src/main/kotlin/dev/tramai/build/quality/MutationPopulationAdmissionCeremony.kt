package dev.tramai.build.quality

/**
 * The population-admission ceremony (0.7.1g1G3): M30-M39.
 *
 * M06 forbids a new NON_KILLED identity from appearing in a candidate measurement: a PR cannot
 * certify its own survivor. Left at that, an adjudicated survivor that is *absent from the
 * committed population* could never be admitted, because admission would always look exactly like
 * the thing M06 exists to stop. This ceremony is the missing, narrowly scoped exception:
 *
 * ```
 * M06 (unchanged)   appearing NON_KILLED, no authorization anywhere  -> fail
 * M30               appearing NON_KILLED, exact base authorization   -> accepted, and the
 *                     authorization is consumed (removed) in the same transition
 * M31               authorization created by this same transition    -> fail
 * M32               authorized identity, different row               -> fail
 * M33               authorized row, different analyzer semantics     -> fail
 * M34               authorized row, different population digest      -> fail
 * M35               new authorization bound to another base SHA      -> fail at mint
 * M36               retained authorization rewritten                  -> fail
 * M37               authorization removed without a valid consumption-> fail
 * M38               authorization retained after being consumed      -> fail (single use)
 * M39               authorization obsolete (target already in the base population, or now KILLED)
 *                     -> warning: cleanable, never forced into an admission
 * ```
 *
 * ## Trust properties
 *
 * The authorization consulted for an admission is **always the base ledger**. An authorization the
 * candidate adds is authority for nothing: it can neither admit in its own transition (M31) nor
 * rewrite what the base already approved (M36). The single negative that separates this from a
 * general M06 bypass is therefore preserved by construction: authorized X plus unauthorized Y in
 * the same measurement fails for Y, through unchanged M06.
 *
 * The digest binds the *whole* transition, not just the identity's row: the authorization records
 * the projection hash of the complete canonical fresh measurement it was minted against, and
 * consumption compares it with the verifier's own fresh measurement
 * ([MutationEvolutionEvidence.proof]). "Binds the neighbours" is what makes this all-or-nothing: if
 * any other identity in the population changes between minting and consumption, the digest no
 * longer matches and the authorization must be re-minted against the new measurement. A candidate
 * can never construct that measurement, because the hash compared is never read from the ledger.
 *
 * ## Division of responsibility (one implementation, two call sites)
 *
 * `outcomeRatchet` (M06) decides whether an appearing NON_KILLED identity has an exact, consumable
 * base authorization. This ceremony owns the authorization lifecycle: mint authority (M35),
 * immutability (M36), payload/digest integrity (M32-M34) and consumption/single-use (M37-M39), plus
 * the M30 note. Both call the same [appearanceVerdict], and the lifecycle scan reuses that same
 * verdict to decide whether an authorization was actually consumed - so "is X exactly authorized?"
 * has exactly one implementation and the two call sites cannot drift apart.
 */
object MutationPopulationAdmissionCeremony {
    /** The outcome of judging one appearing candidate-only identity. */
    sealed interface AdmissionVerdict {
        /** The exact row was authorized for this exact transition by the base ledger (M30). */
        data object Authorized : AdmissionVerdict

        /** Not admissible, for the stated reason (the message already carries its rule id). */
        data class Rejected(
            val code: DiagnosticCode,
            val message: String,
        ) : AdmissionVerdict
    }

    fun checks(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
        freshProjectionHash: String?,
    ): List<VerificationDiagnostic> = lifecycleChecks(base, candidate, freshProjectionHash)

    /**
     * The verdict for one identity that is present in the candidate population and absent from the
     * committed base population. Shared by M06 and by the lifecycle scan, so an authorized identity
     * can never be both admitted and reported as a failure.
     *
     * The M06 message is reproduced verbatim for the unauthorized case: this ceremony narrows M06
     * for exactly authorized identities and otherwise leaves it untouched.
     */
    fun appearanceVerdict(
        baseAdmission: MutationPopulationAdmission?,
        candidateAdmission: MutationPopulationAdmission?,
        mutant: MutationOutcome,
        candidateAnalyzer: MutationAnalyzerSemantics,
        freshProjectionHash: String?,
    ): AdmissionVerdict {
        val short = short(mutant.identity)
        return when {
            baseAdmission == null -> {
                unauthorizedAppearanceVerdict(mutant, candidateAdmission)
            }

            candidateAdmission != null && isRetainedRewrite(baseAdmission, candidateAdmission) -> {
                reject(
                    DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID,
                    "M36: the retained population admission for $short does not match the base " +
                        "authorization. An authorization, once introduced, is immutable.",
                )
            }

            !baseAdmission.admitsRow(mutant) -> {
                reject(
                    DiagnosticCode.MUTATION_RATCHET_ADMISSION_MISMATCH,
                    "M32: ${describe(mutant)} ($short) does not match the row authorized in the base " +
                        "(status='${baseAdmission.status}', outcome='${baseAdmission.outcome}', " +
                        "family='${baseAdmission.family}', module='${baseAdmission.module}'). An " +
                        "authorization admits one exact persisted row, not an identity.",
                )
            }

            baseAdmission.analyzer != candidateAnalyzer -> {
                reject(
                    DiagnosticCode.MUTATION_RATCHET_ADMISSION_MISMATCH,
                    "M33: $short was authorized under different analyzer semantics (authorized=" +
                        "${baseAdmission.analyzer}, candidate=$candidateAnalyzer). M16-M19 forbid " +
                        "consuming an authorization under other settings.",
                )
            }

            freshProjectionHash == null -> {
                reject(
                    DiagnosticCode.MUTATION_RATCHET_ADMISSION_MISMATCH,
                    "M34: $short cannot be admitted: no proof of the canonical fresh measurement exists, " +
                        "so the authorized population digest cannot be checked. Admission fails closed.",
                )
            }

            baseAdmission.populationDigest != freshProjectionHash -> {
                reject(
                    DiagnosticCode.MUTATION_RATCHET_ADMISSION_MISMATCH,
                    "M34: $short was authorized against population digest " +
                        "${short(baseAdmission.populationDigest)}, but this transition's canonical " +
                        "fresh measurement hashes to ${short(freshProjectionHash)}. An authorization " +
                        "binds the complete measured population.",
                )
            }

            else -> {
                AdmissionVerdict.Authorized
            }
        }
    }

    /**
     * The verdict for an appearing identity with no base authorization. The M06 rendering here is
     * byte-identical to the ratchet's own unauthorized-survivor diagnostic ([MutationRatchetVerifier]),
     * because it is produced by the same formatters: this ceremony narrows M06 for authorized
     * identities and must not restate it. M31 differs only in naming the self-authorization.
     */
    private fun unauthorizedAppearanceVerdict(
        mutant: MutationOutcome,
        candidateAdmission: MutationPopulationAdmission?,
    ): AdmissionVerdict {
        val described = describe(mutant)
        val short = short(mutant.identity)
        return if (candidateAdmission != null) {
            reject(
                DiagnosticCode.MUTATION_RATCHET_ADMISSION_UNAUTHORIZED,
                "M31: $described ($short) is authorized only by this transition: the authorization is " +
                    "declared in the candidate ledger and absent from the base. A transition cannot create " +
                    "the authority it consumes.",
            )
        } else {
            reject(
                DiagnosticCode.MUTATION_RATCHET_NEW_SURVIVOR,
                "M06: $described ($short) is a NEW NON_KILLED identity absent from the base authority. " +
                    "New mutants must be killed; a PR cannot certify its own survivors.",
            )
        }
    }

    private fun reject(
        code: DiagnosticCode,
        message: String,
    ): AdmissionVerdict = AdmissionVerdict.Rejected(code, message)

    /**
     * Authorization lifecycle: minting (M35), retention (M36), removal (M37), single use (M38) and
     * obsolescence (M39). Independent of M06, which judges the population itself.
     */
    private fun lifecycleChecks(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
        freshProjectionHash: String?,
    ): List<VerificationDiagnostic> {
        val diagnostics = mintChecks(base, candidate)
        return diagnostics + consumptionChecks(base, candidate, freshProjectionHash)
    }

    /**
     * M35: a newly introduced authorization binds the exact authority base it is proposed against.
     * This is where the SHA has enforcement value - an old authorization payload cannot be replayed
     * onto a different authority base. It is deliberately NOT re-checked at consumption: a pending
     * authorization legitimately survives intermediate merges, and requiring it to track the
     * immediate base would make delayed consumption impossible.
     */
    private fun mintChecks(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseAdmissions = base.admissions.byIdentity()
        for ((id, admission) in candidate.admissions.byIdentity().filterKeys { it !in baseAdmissions }) {
            if (admission.fromBaseSha != base.baseSha) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID,
                        "M35: the population admission for ${short(id)} records fromBaseSha " +
                            "'${admission.fromBaseSha}', not the authority base '${base.baseSha}' this " +
                            "transition is proposed against. fromBaseSha is the base of the minting transition.",
                        findingId = id,
                        modulePath = admission.module,
                    )
            }
        }
        return diagnostics
    }

    /**
     * M36-M39: retention, removal, single use and obsolescence of authorizations the base already
     * holds. Independent of M06, which judges the population itself.
     */
    private fun consumptionChecks(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
        freshProjectionHash: String?,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseAdmissions = base.admissions.byIdentity()
        val candidateAdmissions = candidate.admissions.byIdentity()
        val basePopulationIds =
            base.population.mutants
                .map { it.identity }
                .toSet()
        val candidateById = candidate.population.mutants.associateBy { it.identity }

        for ((id, baseAdmission) in baseAdmissions) {
            val candidateAdmission = candidateAdmissions[id]
            val mutant = candidateById[id]
            val consumed =
                mutant != null &&
                    appearanceVerdict(
                        baseAdmission = baseAdmission,
                        candidateAdmission = candidateAdmission,
                        mutant = mutant,
                        candidateAnalyzer = candidate.population.analyzer,
                        freshProjectionHash = freshProjectionHash,
                    ) is AdmissionVerdict.Authorized
            if (candidateAdmission == null) {
                // M37: a retained authorization may only disappear by being consumed. Otherwise a
                // transition could silently cancel authority that a later transition is entitled to
                // rely on. An obsolete authorization (its target is already committed, or now killed)
                // is the exception: cleanup of dead authority is required, not forbidden.
                if (!consumed && !isObsolete(mutant, basePopulationIds.contains(id))) {
                    diagnostics +=
                        VerificationDiagnostic.failure(
                            DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID,
                            "M37: the population admission for ${short(id)} was removed without being " +
                                "consumed: the candidate population does not contain the authorized row as " +
                                "NON_KILLED. An authorization may not be cancelled silently.",
                            findingId = id,
                            modulePath = baseAdmission.module,
                        )
                }
            } else if (isRetainedRewrite(baseAdmission, candidateAdmission)) {
                // M36: an authorization is immutable from the moment it is introduced. A retained
                // copy differing in any enforced field - including fromBaseSha - is a rewrite,
                // whether or not the target is being consumed in this transition.
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID,
                        "M36: the retained population admission for ${short(id)} does not match the base " +
                            "authorization. An authorization, once introduced, is immutable.",
                        findingId = id,
                        modulePath = candidateAdmission.module,
                    )
            } else if (consumed) {
                // M38: single use. The authorization that admitted the row must be gone.
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_ADMISSION_RETAINED,
                        "M38: the population admission for ${short(id)} was consumed but is still present " +
                            "in the candidate ledger. An authorization is single-use; retaining it would " +
                            "re-authorize a future transition.",
                        findingId = id,
                        modulePath = candidateAdmission.module,
                    )
            } else if (isObsolete(mutant, basePopulationIds.contains(id))) {
                diagnostics += obsoleteWarning(id)
            }
        }
        return diagnostics
    }

    /**
     * True when an authorization can no longer admit anything, so its removal is cleanup rather
     * than silent cancellation: the target is already committed authority, it is now killed, or it
     * is not NON_KILLED at all.
     *
     * The analogue of M29 for admissions is deliberately weaker: a pending population admission is
     * for an identity that is *absent* from the committed population, so absence is the expected
     * state and is never invalid.
     */
    private fun isObsolete(
        mutant: MutationOutcome?,
        presentInBasePopulation: Boolean,
    ): Boolean = presentInBasePopulation || (mutant != null && mutant.outcome != MutationRatchetVerifier.NON_KILLED)

    /**
     * M36, in one place: a retained authorization that differs from the base copy in any enforced
     * field is a rewrite. [appearanceVerdict] and the lifecycle scan both call this, so immutability
     * is judged identically wherever it matters - and audit-only fields are excluded by
     * [MutationPopulationAdmission.enforcedPayload].
     */
    private fun isRetainedRewrite(
        base: MutationPopulationAdmission,
        candidate: MutationPopulationAdmission,
    ): Boolean = candidate.enforcedPayload() != base.enforcedPayload()

    /**
     * M39: advisory only. Obsolete authority is cleanable, whether it is removed (the M37 exception)
     * or retained; it never forces an admission.
     */
    private fun obsoleteWarning(id: String): VerificationDiagnostic =
        VerificationDiagnostic.warning(
            DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID,
            "M39: the population admission for ${short(id)} is obsolete (target already in the committed " +
                "base population, or no longer NON_KILLED). It authorizes nothing and may be removed as cleanup.",
        )
}

/**
 * The ratchet's canonical diagnostic rendering, delegated rather than reimplemented: that is what
 * makes this ceremony's M06 verdict the SAME string the ratchet itself produces. The aliases exist
 * only so the message templates stay readable and inside the line-length limit.
 */
private fun describe(mutant: MutationOutcome): String = MutationRatchetVerifier.describe(mutant)

private fun short(id: String): String = MutationRatchetVerifier.short(id)
