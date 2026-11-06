package dev.tramai.build.quality

/** Identity shortening, taken from the ratchet so both report the same id form. */
private fun short(id: String): String = MutationRatchetVerifier.short(id)

/**
 * The classification enrollment ceremony (0.7.1g1F): M22-M29 plus the unchanged
 * M08/M09 backstop for classifications a transition adds.
 *
 * Split out of [MutationRatchetVerifier] because a verifier class that keeps
 * every rule inline exceeds the maintainability function-count limit; the
 * ratchet's semantics are unchanged — the verifier delegates here exactly where
 * it used to dispatch added classifications.
 *
 * The trust model is temporal and base-derived, never mode-derived:
 *
 *   - Authorization is read from [MutationRatchetAuthority.enrollments] (the
 *     PR's BASE) only. A candidate's own ledger can propose an authorization but
 *     can never authorize the classification it introduces (M23).
 *   - An authorization binds the complete [MutationClassification] payload, not
 *     the identity, so a later transition cannot re-author meaning (M24), and a
 *     retained authorization cannot be rewritten before it is consumed (M28).
 *   - An authorization may only ratify an unresolved NON_KILLED survivor that
 *     the base authority already carries (M25) — never a new, dead or already
 *     classified identity.
 *   - Authorizations are single-use: consumed by the transition that enrolls
 *     them (M26), never silently cancelled (M27), and obsoleted only by the
 *     mutant itself dying or disappearing.
 *   - Every active classification — new or retained — must describe a candidate
 *     mutant that is present and NON_KILLED (M29), so a transition that passes
 *     can always be used as the next transition's base.
 *
 * The authorization lifecycle, in one place:
 *
 *   PENDING (authorization in base)
 *     ├─ target still a live survivor
 *     │    ├─ retained byte-identically .................... pass (awaiting consumption)
 *     │    ├─ consumed with the exact classification ....... pass (M26)
 *     │    ├─ rewritten while retained ..................... fail (M28)
 *     │    ├─ retained after enrollment .................... fail (M27, single-use)
 *     │    └─ removed without enrollment ................... fail (M27, no silent cancellation)
 *     └─ target KILLED or disappeared
 *          ├─ removed ...................................... pass (cleanup; M04/M21 govern the state change)
 *          └─ retained or enrolled ......................... fail (M27 stale / M29 invalid)
 */
object MutationEnrollmentCeremony {
    /**
     * All ceremony diagnostics for one transition. M22 (a successful authorized
     * enrollment) is a pass and emits nothing.
     */
    fun checks(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        diagnostics += addedClassifications(base, candidate)
        diagnostics += duplicateEnrollments(base, candidate)
        diagnostics += invalidTargets(base, candidate)
        diagnostics += selfAuthorizations(base, candidate)
        diagnostics += authorizationLifecycle(base, candidate)
        diagnostics += candidatePopulationIntegrity(base, candidate)
        return diagnostics
    }

    /**
     * M22/M24, and the unchanged M08/M09 backstop. A candidate may never approve
     * its own new survivors; the single exception is an enrollment whose exact
     * payload the BASE already authorized.
     */
    private fun addedClassifications(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseClassById = base.classifications.byIdentity()
        val candidateClassById = candidate.classifications.byIdentity()
        val baseById = base.population.mutants.associateBy { it.identity }
        val candidateById = candidate.population.mutants.associateBy { it.identity }
        val baseEnrollmentById = base.enrollments.byIdentity()

        (candidateClassById.keys - baseClassById.keys).forEach { id ->
            val authorized = baseEnrollmentById[id]
            val added = candidateClassById.getValue(id)
            if (authorized == null) {
                diagnostics +=
                    unauthorizedAddition(
                        id = id,
                        candidateMutant = candidateById[id],
                        baseMutant = baseById[id],
                        baseSha = base.baseSha,
                    )
            } else if (authorized != added) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_MISMATCH,
                        "M24: classification ${short(id)} does not match its base enrollment authorization. " +
                            "An enrollment authorizes one exact record — classification, reason, issue and " +
                            "targetPhase — never an identity alone, so a later transition cannot re-author " +
                            "the approved meaning.",
                        findingId = id,
                        modulePath = candidateById[id]?.module,
                    )
            }
            // M22: exact base-authorized payload for a legitimate target -> authorized enrollment.
        }
        return diagnostics
    }

    /**
     * M08/M09, byte-identical to the ratchet's pre-ceremony behaviour: nothing
     * about the ceremony may soften an unauthorized candidate classification.
     */
    private fun unauthorizedAddition(
        id: String,
        candidateMutant: MutationOutcome?,
        baseMutant: MutationOutcome?,
        baseSha: String,
    ): VerificationDiagnostic {
        val reason =
            when {
                candidateMutant == null -> {
                    "M09: classification ${short(id)} is fabricated — it references no mutant measured " +
                        "in the candidate population and none certified in the base."
                }

                baseMutant == null -> {
                    "M08: candidate self-approval — classification ${short(id)} was added for a mutant that " +
                        "does not exist in the base authority. A PR cannot approve its own new survivors."
                }

                else -> {
                    "M09: classification ${short(id)} was added for a mutant that was never classified in the " +
                        "base authority (base outcome ${baseMutant.outcome}). New classifications are adjudicated " +
                        "on master, never by the PR under review."
                }
            }
        return VerificationDiagnostic.failure(
            DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID,
            "$reason (base $baseSha)",
            findingId = id,
            modulePath = candidateMutant?.module,
        )
    }

    /** M25 duplicate authorization detection for both ledgers. */
    private fun duplicateEnrollments(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        listOf(
            "base" to base.enrollments.enrollments,
            "candidate" to candidate.enrollments.enrollments,
        ).forEach { (label, ledger) ->
            ledger
                .groupingBy { it.classification.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .sorted()
                .forEach { id ->
                    diagnostics +=
                        VerificationDiagnostic.failure(
                            DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_INVALID,
                            "M25: $label enrollment ledger contains duplicate authorizations for ${short(id)}. " +
                                "An identity may be authorized once.",
                            findingId = id,
                        )
                }
        }
        return diagnostics
    }

    /**
     * M25: an authorization is only meaningful for a survivor the base authority
     * already carries — preauthorizing a new, unmeasured, dead or already
     * classified identity would let the ledger *create* authority instead of
     * ratifying it. A proposed authorization must also target a live survivor in
     * the candidate population: a proposal for a mutant this very transition has
     * killed would land as a stale authorization in the next base.
     */
    private fun invalidTargets(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseMutantById = base.population.mutants.associateBy { it.identity }
        val candidateMutantById = candidate.population.mutants.associateBy { it.identity }
        val baseClassById = base.classifications.byIdentity()
        val baseEnrollmentIds = base.enrollments.byIdentity().keys
        val proposedIds = candidate.enrollments.byIdentity().keys - baseEnrollmentIds

        (baseEnrollmentIds + proposedIds).sorted().forEach { id ->
            val baseMutant = baseMutantById[id]
            val reason =
                when {
                    baseMutant == null -> {
                        "the identity is absent from the base authority population. An authorization ratifies " +
                            "an existing base survivor; it cannot preauthorize a new or unmeasured identity."
                    }

                    baseMutant.outcome == MutationRatchetVerifier.KILLED -> {
                        "the identity is KILLED in the base authority. A killed mutant needs no approval record."
                    }

                    baseClassById.containsKey(id) -> {
                        "the identity is already actively classified in the base authority."
                    }

                    id in proposedIds -> {
                        proposedTargetReason(candidateMutantById[id])
                    }

                    else -> {
                        null
                    }
                }
            if (reason != null) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_INVALID,
                        "M25: enrollment authorization for ${short(id)} is invalid — $reason",
                        findingId = id,
                        modulePath = baseMutant?.module,
                    )
            }
        }
        return diagnostics
    }

    /** Candidate-side half of M25 for an authorization this transition proposes. */
    private fun proposedTargetReason(candidateMutant: MutationOutcome?): String? =
        when {
            candidateMutant == null -> {
                "the transition proposes an authorization for an identity it does not measure. A proposal must " +
                    "target a live survivor in the candidate population too."
            }

            candidateMutant.outcome != MutationRatchetVerifier.NON_KILLED -> {
                "the transition proposes an authorization for a mutant it reports as " +
                    "${candidateMutant.outcome}. The authorization would be stale the moment it became base."
            }

            else -> {
                null
            }
        }

    /**
     * M23: a candidate may propose an authorization for a LATER transition, but
     * never consume it itself — otherwise it would create the authority it uses.
     */
    private fun selfAuthorizations(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseEnrollmentById = base.enrollments.byIdentity()
        val candidateEnrollmentById = candidate.enrollments.byIdentity()
        val candidateClassById = candidate.classifications.byIdentity()

        (candidateEnrollmentById.keys - baseEnrollmentById.keys).sorted().forEach { id ->
            if (candidateClassById.containsKey(id)) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_UNAUTHORIZED,
                        "M23: this transition adds an enrollment authorization for ${short(id)} AND enrolls its " +
                            "classification. A candidate cannot authorize its own enrollment — the authorization " +
                            "must already exist in the base, so adjudication happens at least one merge earlier.",
                        findingId = id,
                    )
            }
        }
        return diagnostics
    }

    /**
     * The authorization lifecycle for every base authorization: M26/M27/M28 and
     * the terminal states where the mutant itself stops being a survivor.
     */
    private fun authorizationLifecycle(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseEnrollmentById = base.enrollments.byIdentity()
        val candidateEnrollmentById = candidate.enrollments.byIdentity()
        val candidateClassById = candidate.classifications.byIdentity()
        val candidateMutantById = candidate.population.mutants.associateBy { it.identity }

        baseEnrollmentById.toSortedMap().forEach { (id, authorized) ->
            val retained = candidateEnrollmentById[id]
            val consumedHere = candidateClassById[id] == authorized
            val targetAlive = candidateMutantById[id]?.outcome == MutationRatchetVerifier.NON_KILLED
            // M28 is a payload mismatch (the authorization disagrees with the one that authorized it);
            // M26/M27 are lifecycle problems. Same family, distinct codes.
            val failure: Pair<DiagnosticCode, String>? =
                when {
                    retained != null && retained != authorized -> {
                        DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_MISMATCH to
                            "M28: the base authorization was rewritten while it is still retained. An authorization " +
                            "binds one exact record — classification, reason, issue and targetPhase — so a " +
                            "transition cannot re-author an approval it did not consume. Consume it exactly, or " +
                            "leave it byte-identical."
                    }

                    retained != null && consumedHere -> {
                        DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_ORPHANED to
                            "M27: the authorization was retained after its authorized classification was enrolled. " +
                            "An authorization is single-use; retaining it leaves two enduring copies of the " +
                            "same authority."
                    }

                    retained != null && !targetAlive -> {
                        DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_ORPHANED to
                            "M27: the authorization was retained for a target that is no longer a present " +
                            "NON_KILLED survivor. A dead or unmeasured target needs no approval record — remove " +
                            "the authorization instead of carrying it into the next base."
                    }

                    retained != null -> {
                        null // pending: still awaiting consumption
                    }

                    consumedHere -> {
                        null // M26: removed and consumed by the exact authorized classification
                    }

                    !targetAlive -> {
                        null // cleanup: the mutant died or disappeared; M04 and M21 govern that change
                    }

                    else -> {
                        DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_ORPHANED to
                            "M27: the base authorization was removed without enrolling the exact authorized " +
                            "classification. Removing an authorization is consumption, not cancellation; " +
                            "there is no silent cancellation path."
                    }
                }
            if (failure != null) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        failure.first,
                        failure.second,
                        findingId = id,
                        modulePath = candidateMutantById[id]?.module,
                    )
            }
        }
        return diagnostics
    }

    /**
     * M29: every active candidate classification — new or retained — must describe
     * a candidate mutant that is present and NON_KILLED. Without this, a transition
     * could enroll a classification for a mutant it just killed, or keep one whose
     * mutant vanished, and produce a candidate authority that its own next
     * verification rejects (base classification integrity requires NON_KILLED).
     *
     * The invariant this protects: every transition that passes must be usable as
     * the next transition's base.
     */
    private fun candidatePopulationIntegrity(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseMutantById = base.population.mutants.associateBy { it.identity }
        val candidateMutantById = candidate.population.mutants.associateBy { it.identity }

        candidate.classifications.byIdentity().toSortedMap().forEach { (id, _) ->
            // Absence from both populations is M09's territory (fabrication), reported elsewhere.
            if (!baseMutantById.containsKey(id)) return@forEach
            val target = candidateMutantById[id]
            val reason =
                when {
                    target == null -> {
                        "the classified mutant is absent from the candidate population"
                    }

                    target.outcome != MutationRatchetVerifier.NON_KILLED -> {
                        "the classified mutant is ${target.outcome} in the candidate population, not a survivor"
                    }

                    else -> {
                        null
                    }
                }
            if (reason != null) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID,
                        "M29: classification for ${short(id)} is invalid — $reason. A transition may not pass " +
                            "while producing an authority its own next verification rejects.",
                        findingId = id,
                        modulePath = target?.module,
                    )
            }
        }
        return diagnostics
    }
}
