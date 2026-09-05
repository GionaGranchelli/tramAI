package dev.tramai.build.quality

import dev.tramai.build.quality.TestQualityConfiguration.MutationTargetFamily

/**
 * 10.3c3: base-authoritative mutation ratchet (Epic 10.3c3).
 *
 * Pure, in-memory, exact-set verifier. It judges a CANDIDATE mutation
 * population + classification set + mutation target configuration (all three
 * committed on the PR branch) against the BASE authority (the same three at
 * the PR base / master commit). It never runs PITest and never re-measures —
 * it only compares exact identities and canonical outcomes.
 *
 * Certified authority facts it preserves (identity schema v2):
 * - canonical outcomes are exactly KILLED | NON_KILLED; raw PIT statuses are
 *   diagnostic evidence and never participate in the ratchet (C7), but every
 *   persisted row MUST be self-consistent: its identity must equal the
 *   SHA-256 recomputed over its own fields, and its stored outcome must equal
 *   [MutationOutcome.canonical] of its raw status. Hand-edited rows cannot
 *   forge a kill (a raw SURVIVED cannot be stored as outcome=KILLED) and
 *   cannot launder a survivor into the authority under a fake identity.
 * - identity = SHA-256 over (module, class, method, descriptor, mutator,
 *   description, block, index) — see [MutationIdentity].
 *
 * Discriminator matrix (each rule maps to at least one focused test):
 * - M01 base KILLED -> candidate NON_KILLED                    = regression
 * - M02 base KILLED -> candidate KILLED                        = pass
 * - M03 approved (base-classified) survivor stays NON_KILLED   = pass; a
 *   retained classification record must stay byte-identical — rewriting
 *   classification/reason/issue/targetPhase of an existing approval fails
 * - M04 approved survivor -> KILLED + classification removed   = pass
 * - M05 approved survivor -> KILLED + classification retained  = stale
 * - M06 new NON_KILLED identity                                = new survivor
 * - M07 new KILLED identity                                    = pass (only if
 *   the row is self-consistent — forged kills fail closed)
 * - M08 candidate self-classification (new survivor)           = fail
 * - M09 fabricated classification (never certified)            = fail
 * - M10 classification for disappeared mutant retained         = orphaned
 * - M11 base classification removed while survivor remains     = fail
 * - M12 duplicate identities                                   = fail
 * - M13 unknown/non-canonical outcome OR raw status, and stored
 *   outcome contradicting canonical(raw status)                = fail closed
 * - M14 family narrowing (family/module set shrinks)           = fail
 * - M15 target-class / target-test narrowing                   = fail
 * - M16/M17/M18 analyzer semantics/mutator/timeout drift, base vs candidate
 *   AND candidate vs the executable PIT renderer               = fail
 * - M19 identity-schema drift                                  = fail
 * - M20 malformed / missing / self-inconsistent authority      = fail closed
 *
 * Classification authority semantics: a PR may only REMOVE classifications
 * (when the underlying mutant dies), never add or re-author one. New
 * classifications are adjudicated on master during an enrollment ceremony and
 * become part of the base; anything a candidate adds is either self-approval
 * of its own survivor (M08) or fabrication (M09). byFamily metrics are
 * derived truth: they are recomputed from the persisted rows and must equal
 * the persisted entry exactly; every configured family must stay non-vacuous.
 *
 * No wildcards, no family allowances, no budgets, no score thresholds and no
 * mutation-score floor are consulted anywhere in this class.
 */
class MutationRatchetVerifier {
    fun verify(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
        executable: MutationAnalyzerSemantics,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        diagnostics += schemaAndStatusChecks(base.population, candidate.population)
        diagnostics += semanticsChecks(base.population.analyzer, candidate.population.analyzer, executable)
        diagnostics += validatePopulationRows("base authority", base.population)
        diagnostics += validatePopulationRows("candidate", candidate.population)
        diagnostics += validateClassificationList("base authority", base.classifications)
        diagnostics += validateClassificationList("candidate", candidate.classifications)
        diagnostics += baseClassificationIntegrity(base)
        diagnostics += outcomeRatchet(base.population.mutants, candidate.population.mutants)
        diagnostics += classificationRatchet(base, candidate)
        diagnostics +=
            familyAndTargetChecks(
                base.population,
                base.targetFamilies,
                candidate.population,
                candidate.targetFamilies,
            )
        return diagnostics
    }

    private fun schemaAndStatusChecks(
        basePopulation: MutationPopulationBaseline,
        candidatePopulation: MutationPopulationBaseline,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        if (basePopulation.status != "measured" || candidatePopulation.status != "measured") {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
                    "base and candidate mutation populations must both be status=measured " +
                        "(base='${basePopulation.status}', candidate='${candidatePopulation.status}'). " +
                        "A pending/empty measurement is not an approval authority.",
                )
        }
        if (basePopulation.mutants.isEmpty() || candidatePopulation.mutants.isEmpty()) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
                    "base and candidate mutation populations must both be non-empty; " +
                        "an empty population would silently vacate every certified mutant.",
                )
        }
        if (basePopulation.identitySchemaVersion != IDENTITY_SCHEMA_VERSION ||
            candidatePopulation.identitySchemaVersion != IDENTITY_SCHEMA_VERSION
        ) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_RATCHET_SCHEMA_DRIFT,
                    "M19: identity schema drift — base='${basePopulation.identitySchemaVersion}', " +
                        "candidate='${candidatePopulation.identitySchemaVersion}'; only '$IDENTITY_SCHEMA_VERSION' " +
                        "is pinned authority.",
                )
        }
        if (basePopulation.identitySchemaVersion != candidatePopulation.identitySchemaVersion) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_RATCHET_SCHEMA_DRIFT,
                    "M19: candidate identity schema '${candidatePopulation.identitySchemaVersion}' differs from base " +
                        "'${basePopulation.identitySchemaVersion}'. Schema changes require a " +
                        "baseline-migration ceremony.",
                )
        }
        if (basePopulation.schemaVersion != "1" || candidatePopulation.schemaVersion != "1") {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_RATCHET_SCHEMA_DRIFT,
                    "M19: population schemaVersion must be '1' (base='${basePopulation.schemaVersion}', " +
                        "candidate='${candidatePopulation.schemaVersion}').",
                )
        }
        return diagnostics
    }

    /**
     * Three-way semantics pin (M16/M17/M18): the committed base metadata, the
     * committed candidate metadata and the EXECUTABLE PIT renderer semantics
     * ([MutationPopulationAggregator.canonicalSemantics], derived from
     * [MutationProbeInitScript]) must all agree. Comparing base vs candidate
     * alone only proves the JSON description did not drift; comparing both
     * against the renderer proves the actual PIT configuration did not drift
     * either — a future PR that bumps PIT in build-logic while leaving the
     * committed analyzer block untouched now fails.
     */
    private fun semanticsChecks(
        base: MutationAnalyzerSemantics,
        candidate: MutationAnalyzerSemantics,
        executable: MutationAnalyzerSemantics,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseVsCandidate = semanticsDiffs(base, candidate)
        if (baseVsCandidate.isNotEmpty()) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_RATCHET_SEMANTICS_DRIFT,
                    "M16/M17/M18: PIT semantics drift, base authority -> candidate: " +
                        baseVsCandidate.joinToString("; ") + ". Killed/survived meaning changed.",
                )
        }
        val candidateVsExecutable = semanticsDiffs(candidate, executable)
        if (candidateVsExecutable.isNotEmpty()) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_RATCHET_SEMANTICS_DRIFT,
                    "M16/M17/M18: candidate analyzer metadata differs from the executable PIT renderer " +
                        "(MutationProbeInitScript): " + candidateVsExecutable.joinToString("; ") +
                        ". Changing PIT configuration without re-measuring the population and updating its " +
                        "committed metadata is semantics drift.",
                )
        }
        val baseVsExecutable = semanticsDiffs(base, executable)
        if (baseVsExecutable.isNotEmpty()) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_RATCHET_SEMANTICS_DRIFT,
                    "M16/M17/M18: base authority analyzer metadata differs from the executable PIT renderer: " +
                        baseVsExecutable.joinToString("; ") +
                        ". The authority predates the current renderer or the renderer drifted — " +
                        "a re-enrollment ceremony is required.",
                )
        }
        return diagnostics
    }

    private fun validatePopulationRows(
        label: String,
        population: MutationPopulationBaseline,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val seen = mutableSetOf<String>()
        population.mutants.forEach { mutant ->
            if (mutant.identity.isBlank()) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
                        "$label: mutant row has a blank identity — malformed authority input fails closed",
                    )
            } else {
                if (!seen.add(mutant.identity)) {
                    diagnostics +=
                        VerificationDiagnostic.failure(
                            DiagnosticCode.MUTATION_RATCHET_DUPLICATE_IDENTITY,
                            "M12: $label contains duplicate identity ${short(mutant.identity)} " +
                                "(${mutant.className}#${mutant.method} ${mutant.mutator}). " +
                                "Duplicate identities cannot be ratcheted.",
                            findingId = mutant.identity,
                            modulePath = mutant.module,
                        )
                }
                diagnostics += rowSelfChecks(label, mutant)
            }
        }
        return diagnostics
    }

    private fun baseClassificationIntegrity(base: MutationRatchetAuthority): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseById = base.population.mutants.associateBy { it.identity }
        base.classifications.classifications.forEach { classification ->
            val mutant = baseById[classification.id]
            if (mutant == null) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
                        "base ${short(base.baseSha)}: classification ${classification.id} references a mutant " +
                            "absent from the base population",
                        findingId = classification.id,
                    )
            } else if (mutant.outcome != NON_KILLED) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
                        "base ${short(base.baseSha)}: classification ${classification.id} references a " +
                            "${mutant.outcome} mutant; classifications may only describe approved NON_KILLED survivors",
                        findingId = classification.id,
                    )
            }
        }
        return diagnostics
    }

    private fun outcomeRatchet(
        baseMutants: List<MutationOutcome>,
        candidateMutants: List<MutationOutcome>,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseById = baseMutants.associateBy { it.identity }
        val candidateById = candidateMutants.associateBy { it.identity }
        val baseIds = baseById.keys
        val candidateIds = candidateById.keys

        for (id in baseIds intersect candidateIds) {
            val base = baseById.getValue(id)
            val candidate = candidateById.getValue(id)
            if (base.outcome == KILLED && candidate.outcome == NON_KILLED) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_REGRESSION,
                        "M01: ${describe(base)} (${short(id)}) was KILLED in the base authority but is " +
                            "NON_KILLED in the candidate. A PR may not turn a killed mutant into a survivor.",
                        findingId = id,
                        modulePath = candidate.module,
                    )
            }
            if (base.family != candidate.family) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_FAMILY_NARROWING,
                        "M14: mutant ${short(id)} moved from base family '${base.family}' to candidate family " +
                            "'${candidate.family}'. Identities may not be re-homed between families.",
                        findingId = id,
                        modulePath = candidate.module,
                    )
            }
        }
        for (id in candidateIds - baseIds) {
            val candidate = candidateById.getValue(id)
            if (candidate.outcome == NON_KILLED) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_NEW_SURVIVOR,
                        "M06: ${describe(candidate)} (${short(id)}) is a NEW NON_KILLED identity absent from the " +
                            "base authority. New mutants must be killed; a PR cannot certify its own survivors.",
                        findingId = id,
                        modulePath = candidate.module,
                    )
            }
            // M07: new KILLED identities pass — improved protection is the ratchet's goal.
            // (Only self-consistent kills pass: rowSelfChecks already rejected forged ones.)
        }
        return diagnostics
    }

    private fun classificationRatchet(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseClassById = base.classifications.byIdentity()
        val candidateClassById = candidate.classifications.byIdentity()
        val baseById = base.population.mutants.associateBy { it.identity }
        val candidateById = candidate.population.mutants.associateBy { it.identity }

        // Retained classifications (M03/M05/M10). Retained means byte-identical:
        // the id alone is never authority for an approval record.
        (candidateClassById.keys intersect baseClassById.keys).forEach { id ->
            val baseClassification = baseClassById.getValue(id)
            val candidateClassification = candidateClassById.getValue(id)
            val mutant = candidateById[id]
            if (mutant == null) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID,
                        "M10: classification ${short(id)} is retained but its mutant disappeared from the candidate " +
                            "population. Population evolution requires the classification to be removed with it.",
                        findingId = id,
                    )
            } else if (mutant.outcome == KILLED) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID,
                        "M05: approved survivor ${short(id)} is now KILLED but its stale classification is retained. " +
                            "Removing the mutant requires removing the classification (M04).",
                        findingId = id,
                        modulePath = mutant.module,
                    )
            } else if (baseClassification != candidateClassification) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID,
                        "M03: retained classification ${short(id)} was rewritten — classification/reason/issue/" +
                            "targetPhase of an approved survivor must stay byte-identical while it remains " +
                            "NON_KILLED. A PR may remove a classification when the mutant dies (M04); it may not " +
                            "re-author an existing approval.",
                        findingId = id,
                        modulePath = mutant.module,
                    )
            }
            // M03: approved survivor stays NON_KILLED with an identical classification -> pass.
        }

        // Added classifications (M08/M09): a candidate may never add one.
        (candidateClassById.keys - baseClassById.keys).forEach { id ->
            diagnostics += addedClassificationDiagnostic(id, candidateById[id], baseById[id], base.baseSha)
        }

        // Removed classifications (M04/M11).
        (baseClassById.keys - candidateClassById.keys).forEach { id ->
            val mutant = candidateById[id]
            if (mutant != null && mutant.outcome == NON_KILLED) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_REMOVED,
                        "M11: base classification ${short(id)} was removed while its mutant is still NON_KILLED. " +
                            "A classification may only be removed when the mutant dies (M04) or legitimately " +
                            "disappears.",
                        findingId = id,
                        modulePath = mutant.module,
                    )
            }
            // M04: removed + mutant KILLED -> pass. Removed + mutant disappeared -> pass.
        }
        return diagnostics
    }

    private fun addedClassificationDiagnostic(
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

    private fun familyAndTargetChecks(
        basePopulation: MutationPopulationBaseline,
        baseTargetFamilies: Map<String, MutationTargetFamily>,
        candidatePopulation: MutationPopulationBaseline,
        candidateTargetFamilies: Map<String, MutationTargetFamily>,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        diagnostics += familyScopeConsistency("base authority", basePopulation, baseTargetFamilies)
        diagnostics += familyScopeConsistency("candidate", candidatePopulation, candidateTargetFamilies)

        // M14: base families/modules must survive in the candidate (broadening allowed, narrowing fails).
        baseTargetFamilies.forEach { (family, baseTarget) ->
            val candidateTarget = candidateTargetFamilies[family]
            if (candidateTarget == null) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_FAMILY_NARROWING,
                        "M14: candidate drops base mutation family '$family'. Narrowing measured authority is not " +
                            "population evolution.",
                    )
                return@forEach
            }
            val droppedModules = baseTarget.modules - candidateTarget.modules.toSet()
            if (droppedModules.isNotEmpty()) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_FAMILY_NARROWING,
                        "M14: candidate narrows family '$family' by removing module(s) " +
                            "${droppedModules.sorted().joinToString()} from the measured scope.",
                    )
            }
            val droppedClasses = baseTarget.targetClasses - candidateTarget.targetClasses.toSet()
            if (droppedClasses.isNotEmpty()) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT,
                        "M15: candidate narrows family '$family' targetClasses by removing " +
                            droppedClasses.sorted().joinToString(),
                    )
            }
            val droppedTests = baseTarget.targetTests - candidateTarget.targetTests.toSet()
            if (droppedTests.isNotEmpty()) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT,
                        "M15: candidate narrows family '$family' targetTests by removing " +
                            droppedTests.sorted().joinToString(),
                    )
            }
        }
        return diagnostics
    }

    /**
     * byFamily is derived truth: family keys, modules, totals and every raw
     * bucket are recomputed from the persisted mutant rows + the governing
     * target configuration and must equal the persisted entry exactly. Every
     * configured family must also stay non-vacuous.
     */
    private fun familyScopeConsistency(
        label: String,
        population: MutationPopulationBaseline,
        targetFamilies: Map<String, MutationTargetFamily>,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        if (population.byFamily.keys != targetFamilies.keys) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
                    "$label byFamily keys ${population.byFamily.keys.sorted()} disagree with governing " +
                        "targetFamilies ${targetFamilies.keys.sorted()}. Every configured family must appear in the " +
                        "population and vice versa.",
                )
        } else {
            val rowFamilies = population.mutants.map { it.family }.toSet()
            if (rowFamilies != targetFamilies.keys) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
                        "$label mutant rows carry families ${rowFamilies.sorted()} that disagree with the governing " +
                            "targetFamilies ${targetFamilies.keys.sorted()}. A row in an unconfigured family is not " +
                            "a measured mutant and cannot be ratcheted.",
                    )
            } else {
                diagnostics += familyEntryChecks(label, population, targetFamilies)
            }
        }
        return diagnostics
    }

    private companion object {
        const val KILLED = "KILLED"
        const val NON_KILLED = "NON_KILLED"
        const val IDENTITY_SCHEMA_VERSION = "2"
        const val ID_SHORT_LENGTH = 8
        val KNOWN_STATUSES = setOf("KILLED", "SURVIVED", "NO_COVERAGE", "TIMED_OUT")

        fun describe(mutant: MutationOutcome): String =
            "${mutant.module} ${mutant.className}#${mutant.method}${mutant.methodDescription} " +
                "[${mutant.mutator}] family '${mutant.family}'"

        fun short(id: String): String = id.take(ID_SHORT_LENGTH)

        fun validateClassificationList(
            label: String,
            classifications: MutationClassifications,
        ): List<VerificationDiagnostic> {
            val diagnostics = mutableListOf<VerificationDiagnostic>()
            val ids = classifications.classifications.map { it.id }
            if (ids.size != ids.distinct().size) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_DUPLICATE_IDENTITY,
                        "M12: $label contains duplicate classification ids; duplicate identities fail closed",
                    )
            }
            return diagnostics
        }

        /**
         * Per-row cryptographic and canonical self-validation (M13/M20): the
         * stored identity must equal the SHA-256 over the row's own identity
         * fields.
         */
        fun rowSelfChecks(
            label: String,
            mutant: MutationOutcome,
        ): List<VerificationDiagnostic> {
            val diagnostics = mutableListOf<VerificationDiagnostic>()
            if (mutant.outcome != KILLED && mutant.outcome != NON_KILLED) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_UNKNOWN_OUTCOME,
                        "M13: $label mutant ${short(mutant.identity)} has non-canonical outcome '${mutant.outcome}'. " +
                            "Canonical outcomes are exactly KILLED | NON_KILLED; unknown outcomes fail closed.",
                        findingId = mutant.identity,
                        modulePath = mutant.module,
                    )
            }
            val recomputed =
                MutationIdentity(
                    mutant.module,
                    mutant.className,
                    mutant.method,
                    mutant.methodDescription,
                    mutant.mutator,
                    mutant.description,
                    mutant.block,
                    mutant.index,
                ).stableKey()
            if (recomputed != mutant.identity) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
                        "M20: $label mutant ${short(mutant.identity)} is self-inconsistent — its stored identity " +
                            "does not equal the SHA-256 over its own module/class/method/descriptor/mutator/" +
                            "description/block/index fields (recomputed ${short(recomputed)}). " +
                            "Hand-forged rows fail closed.",
                        findingId = mutant.identity,
                        modulePath = mutant.module,
                    )
            }
            diagnostics += canonicalOutcomeChecks(label, mutant)
            return diagnostics
        }

        fun canonicalOutcomeChecks(
            label: String,
            mutant: MutationOutcome,
        ): List<VerificationDiagnostic> {
            val diagnostics = mutableListOf<VerificationDiagnostic>()
            val canonicalAttempt = runCatching { MutationOutcome.canonical(mutant.status) }
            val canonicalOutcome = canonicalAttempt.getOrNull()
            if (canonicalOutcome == null) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_UNKNOWN_OUTCOME,
                        "M13: $label mutant ${short(mutant.identity)} has raw status '${mutant.status}' which " +
                            "cannot be canonicalized (${canonicalAttempt.exceptionOrNull()?.message}); " +
                            "tool-failure statuses fail closed and never become NON_KILLED or KILLED.",
                        findingId = mutant.identity,
                        modulePath = mutant.module,
                    )
            } else if (canonicalOutcome != mutant.outcome) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_UNKNOWN_OUTCOME,
                        "M13: $label mutant ${short(mutant.identity)} stores outcome '${mutant.outcome}' which " +
                            "contradicts the canonical mapping of its raw status '${mutant.status}' " +
                            "(canonical '$canonicalOutcome'). A hand-edited outcome cannot turn a raw survivor " +
                            "into a kill.",
                        findingId = mutant.identity,
                        modulePath = mutant.module,
                    )
            }
            return diagnostics
        }

        /** Per-family row-scope and derived-metric validation (M14/M20). */
        fun familyEntryChecks(
            label: String,
            population: MutationPopulationBaseline,
            targetFamilies: Map<String, MutationTargetFamily>,
        ): List<VerificationDiagnostic> {
            val diagnostics = mutableListOf<VerificationDiagnostic>()
            population.byFamily.forEach { (family, familyPopulation) ->
                val config = targetFamilies.getValue(family)
                val familyRows = population.mutants.filter { it.family == family }
                val unexpectedModules = familyRows.map { it.module }.toSet() - config.modules.toSet()
                if (unexpectedModules.isNotEmpty()) {
                    diagnostics +=
                        VerificationDiagnostic.failure(
                            DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
                            "$label family '$family' rows reference module(s) " +
                                "${unexpectedModules.sorted().joinToString()} outside the configured modules " +
                                "[${config.modules.joinToString()}] for that family. Rows may only sit inside their " +
                                "family's measured scope.",
                        )
                    return@forEach
                }
                val recomputed = recomputeFamilyPopulation(family, familyRows, config.modules)
                if (recomputed != familyPopulation) {
                    diagnostics +=
                        VerificationDiagnostic.failure(
                            DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
                            "$label family '$family' byFamily entry ${summarizeFamily(familyPopulation)} disagrees " +
                                "with its rows (recomputed ${summarizeFamily(recomputed)}). byFamily is derived " +
                                "truth; hand-edited metrics fail closed.",
                        )
                }
            }
            return diagnostics
        }

        fun semanticsDiffs(
            a: MutationAnalyzerSemantics,
            b: MutationAnalyzerSemantics,
        ): List<String> {
            val diffs = mutableListOf<String>()
            if (a.pluginVersion != b.pluginVersion) {
                diffs += "plugin ${a.pluginVersion}->${b.pluginVersion}"
            }
            if (a.engineVersion != b.engineVersion) {
                diffs += "engine ${a.engineVersion}->${b.engineVersion}"
            }
            if (a.mutators != b.mutators) {
                diffs += "mutators [${a.mutators.joinToString()}]->[${b.mutators.joinToString()}]"
            }
            if (a.timeoutConst != b.timeoutConst) {
                diffs += "timeoutConst ${a.timeoutConst}->${b.timeoutConst}"
            }
            if (a.timeoutFactor != b.timeoutFactor) {
                diffs += "timeoutFactor ${a.timeoutFactor}->${b.timeoutFactor}"
            }
            return diffs
        }

        /** Mirrors MutationPopulationAggregator.familyPopulation over persisted rows. */
        fun recomputeFamilyPopulation(
            family: String,
            familyRows: List<MutationOutcome>,
            configuredModules: List<String>,
        ): MutationFamilyPopulation {
            val killed = familyRows.count { it.status == "KILLED" }
            val survived = familyRows.count { it.status == "SURVIVED" }
            val noCoverage = familyRows.count { it.status == "NO_COVERAGE" }
            val timedOut = familyRows.count { it.status == "TIMED_OUT" }
            val errors = familyRows.count { it.status !in KNOWN_STATUSES }
            return MutationFamilyPopulation(
                family = family,
                modules = configuredModules.sorted(),
                totalMutants = familyRows.size,
                killedMutants = killed,
                survivedMutants = survived,
                noCoverageMutants = noCoverage,
                timedOutMutants = timedOut,
                errorMutants = errors,
                mutationScore = if (familyRows.isEmpty()) 0.0 else 100.0 * killed / familyRows.size,
            )
        }

        fun summarizeFamily(population: MutationFamilyPopulation): String =
            "modules=${population.modules} total=${population.totalMutants} killed=${population.killedMutants} " +
                "survived=${population.survivedMutants} noCoverage=${population.noCoverageMutants} " +
                "timedOut=${population.timedOutMutants} errors=${population.errorMutants} " +
                "score=${population.mutationScore}"
    }
}
