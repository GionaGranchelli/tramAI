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
 * - M20 malformed / missing / self-inconsistent authority    = fail closed
 * - M21 base identity absent from the candidate population   = fail by default. A recorded evolution
 *   invocation may downgrade individually recorded removals to warnings only when the candidate has
 *   a fresh measured population; all other removals fail closed.
 * - M22 base-authorized enrollment: candidate adds a classification whose EXACT record (classification,
 *   reason, issue, targetPhase) is already authorized by the base enrollment ledger, for a target that is
 *   an unresolved NON_KILLED survivor in the base population                      = pass (the ceremony)
 * - M23 candidate adds an enrollment authorization AND enrolls it in the same transition = fail
 * - M24 enrolled classification payload differs from its base authorization      = fail
 * - M25 enrollment authorization is duplicated, or targets an identity that is absent from the base
 *   population, KILLED, or already classified                                    = fail closed
 * - M26 base authorization removed while its exact authorized classification is enrolled = pass (consumption)
 * - M27 authorization removed without enrollment, or retained after its classification was enrolled = fail
 *
 * Classification authority semantics: a PR may only REMOVE classifications
 * (when the underlying mutant dies), never add or re-author one. New
 * classifications are adjudicated on master during an enrollment ceremony and
 * become part of the base; anything a candidate adds is either self-approval
 * of its own survivor (M08) or fabrication (M09). The ceremony itself is the
 * base-side enrollment ledger (M22-M27): an authorization merged into the base
 * may be consumed by a LATER transition, so a candidate can never create the
 * authority it uses. byFamily metrics are
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
        evolution: MutationPopulationEvolution = MutationPopulationEvolution.FORBID,
        evolutionEvidence: MutationEvolutionEvidence = MutationEvolutionEvidence(),
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        diagnostics += schemaAndStatusChecks(base.population, candidate.population)
        diagnostics += semanticsChecks(base.population.analyzer, candidate.population.analyzer, executable)
        diagnostics += validatePopulationRows("base authority", base.population)
        diagnostics += validatePopulationRows("candidate", candidate.population)
        diagnostics += validateClassificationList("base authority", base.classifications)
        diagnostics += validateClassificationList("candidate", candidate.classifications)
        diagnostics += baseClassificationIntegrity(base)
        diagnostics +=
            outcomeRatchet(
                base.population,
                candidate.population,
                MutationEvolutionContext(
                    evolution,
                    evolutionEvidence,
                    base.baseSha,
                    AUTHORITY_EXCLUDED_IDENTITIES,
                ),
            )
        diagnostics += classificationRatchet(base, candidate)
        diagnostics += MutationEnrollmentCeremony.checks(base, candidate)
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
        basePopulation: MutationPopulationBaseline,
        candidatePopulation: MutationPopulationBaseline,
        evolution: MutationEvolutionContext,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val baseMutants = basePopulation.mutants
        val candidateMutants = candidatePopulation.mutants
        val baseById = baseMutants.associateBy { it.identity }
        val candidateById = candidateMutants.associateBy { it.identity }
        val baseIds = baseById.keys
        val candidateIds = candidateById.keys

        for (id in baseIds intersect candidateIds) {
            val base = baseById.getValue(id)
            val candidate = candidateById.getValue(id)
            if (base.outcome == KILLED && candidate.outcome == NON_KILLED && id !in AUTHORITY_EXCLUDED_IDENTITIES) {
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

        // M21: a base identity that simply stopped being measured. Absence is not evidence of
        // improvement — a narrowed target, a module that stopped reporting, a truncated report or an
        // aborted campaign all look exactly like this. Measured authority may only shrink through the
        // repository's explicit baseline evolution ceremony, never as a side effect of a measurement.
        diagnostics +=
            populationEvolutionDiagnostics(
                basePopulation,
                candidatePopulation,
                evolution,
            )
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

    internal companion object {
        const val KILLED = "KILLED"
        const val NON_KILLED = "NON_KILLED"
        const val IDENTITY_SCHEMA_VERSION = "2"
        const val ID_SHORT_LENGTH = 8

        // Exact PIT identities for compiler-generated PolicyEnforcementHelper
        // coroutine scaffolding. Source-semantic siblings remain ratcheted.
        val AUTHORITY_EXCLUDED_IDENTITIES =
            setOf(
                "43862d692f6aba3778da8e096701a05c93fd15e326f22c0edec0f5479def0d51",
                "555fd30f2589cc428ad5bf92f0f407688efce434cc020a7c58b767bee0324013",
            )
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

private const val M21_ID_SHORT_LENGTH = 8
private const val HEX_BYTE_MASK = 0xff

data class MutationEvolutionEvidence(
    val records: MutationEvolutionRecords = MutationEvolutionRecords("1", emptyList()),
    val proof: MutationPopulationEvolutionProof? = null,
)

private data class MutationEvolutionContext(
    val mode: MutationPopulationEvolution,
    val evidence: MutationEvolutionEvidence,
    val baseSha: String,
    val excludedIdentities: Set<String>,
)

// No population hash is stored in mutation-evolution.yml: exact measurement
// equality already binds the candidate, while this proof binds the verifier call.
// The proof hashes the FULL canonical comparison projection — identity, raw status,
// canonical outcome, family, module, family/module topology and analyzer semantics —
// not just the identity set, so it cannot be reused for a population that shares the
// identities but differs in outcomes.
class MutationPopulationEvolutionProof private constructor(
    val projectionHash: String,
) {
    fun matches(population: MutationPopulationBaseline): Boolean = projectionHash == population.projectionHash()

    companion object {
        fun exactComparison(
            fresh: MutationPopulationBaseline,
            candidate: MutationPopulationBaseline,
        ): MutationPopulationExactComparison {
            val diagnostics = mutableListOf<VerificationDiagnostic>()
            val freshById = fresh.mutants.associateBy { it.identity }
            val candidateById = candidate.mutants.associateBy { it.identity }
            diagnostics += rowDifferences(freshById, candidateById)
            val freshTopology = fresh.byFamily.mapValues { it.value.modules.toSet() }
            val candidateTopology = candidate.byFamily.mapValues { it.value.modules.toSet() }
            if (freshTopology != candidateTopology) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT,
                        "M21: fresh and committed mutation population family/module topology differs: " +
                            "fresh=$freshTopology, candidate=$candidateTopology.",
                    )
            }
            if (fresh.analyzer != candidate.analyzer) {
                diagnostics +=
                    exactDifference(
                        "analyzer",
                        "analyzer semantics fresh=${fresh.analyzer} != candidate=${candidate.analyzer}",
                    )
            }
            (fresh.byFamily + candidate.byFamily).forEach { (family, population) ->
                if (population.totalMutants == 0) {
                    diagnostics += exactDifference(family, "family is empty")
                }
            }
            val proof =
                if (diagnostics.isEmpty()) {
                    MutationPopulationEvolutionProof(
                        projectionHash = fresh.projectionHash(),
                    )
                } else {
                    null
                }
            return MutationPopulationExactComparison(proof, diagnostics)
        }

        private fun exactDifference(
            id: String,
            difference: String,
        ): VerificationDiagnostic =
            VerificationDiagnostic.failure(
                DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT,
                "M21: fresh measurement differs from committed candidate for identity " +
                    "${id.take(M21_ID_SHORT_LENGTH)}: $difference.",
                findingId = id,
            )

        private fun rowDifferences(
            fresh: Map<String, MutationOutcome>,
            candidate: Map<String, MutationOutcome>,
        ): List<VerificationDiagnostic> {
            val diagnostics = mutableListOf<VerificationDiagnostic>()
            for (id in (fresh.keys union candidate.keys).sorted()) {
                val measured = fresh[id]
                val committed = candidate[id]
                val difference =
                    when {
                        measured == null -> {
                            "identity is absent from fresh measurement"
                        }

                        committed == null -> {
                            "identity is absent from committed candidate"
                        }

                        measured.status != committed.status -> {
                            "raw status ${measured.status} != ${committed.status}"
                        }

                        measured.outcome != committed.outcome -> {
                            "canonical status ${measured.outcome} != ${committed.outcome}"
                        }

                        measured.family != committed.family -> {
                            "family ${measured.family} != ${committed.family}"
                        }

                        measured.module != committed.module -> {
                            "module ${measured.module} != ${committed.module}"
                        }

                        else -> {
                            null
                        }
                    }
                if (difference != null) diagnostics += exactDifference(id, difference)
            }
            return diagnostics
        }

        private fun String.sha256(): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and HEX_BYTE_MASK) }

        private fun MutationPopulationBaseline.projectionHash(): String = canonicalProjection().sha256()

        /**
         * The canonical exact-comparison projection: the complete, order-independent evidence a
         * fresh measurement must reproduce. Hashed by [projectionHash] so the proof binds
         * everything the comparison actually checked, not merely the identity set.
         */
        private fun MutationPopulationBaseline.canonicalProjection(): String =
            buildString {
                mutants
                    .map { "${it.identity}|${it.status}|${it.outcome}|${it.family}|${it.module}" }
                    .sorted()
                    .forEach { appendLine(it) }
                appendLine(
                    "topology=" +
                        byFamily.toSortedMap().entries.joinToString(",") {
                            "${it.key}:${it.value.modules.sorted().joinToString("+")}"
                        },
                )
                appendLine(
                    "analyzer=" +
                        analyzer.pluginVersion + "|" + analyzer.engineVersion + "|" +
                        analyzer.mutators
                            .sorted()
                            .joinToString("+") + "|" +
                        analyzer.timeoutConst + "|" + analyzer.timeoutFactor,
                )
            }
    }
}

data class MutationPopulationExactComparison(
    val proof: MutationPopulationEvolutionProof?,
    val diagnostics: List<VerificationDiagnostic>,
)

private fun populationEvolutionDiagnostics(
    basePopulation: MutationPopulationBaseline,
    candidatePopulation: MutationPopulationBaseline,
    evolution: MutationEvolutionContext,
): List<VerificationDiagnostic> {
    val diagnostics = mutableListOf<VerificationDiagnostic>()
    val baseById = basePopulation.mutants.associateBy { it.identity }
    val candidateIds = candidatePopulation.mutants.map { it.identity }.toSet()
    diagnostics += evolutionEvidenceDiagnostics(baseById, candidateIds, candidatePopulation, evolution)
    val recordsById = evolution.evidence.records.byIdentity()
    for (id in baseById.keys - candidateIds) {
        val base = baseById.getValue(id)
        if (id in evolution.excludedIdentities) continue
        val record = recordsById[id]
        val description =
            "${base.module} ${base.className}#${base.method}${base.methodDescription} " +
                "[${base.mutator}] family '${base.family}'"
        val message =
            "M21: $description (${id.take(M21_ID_SHORT_LENGTH)}) exists in the base authority but is " +
                "absent from the candidate population. "
        when {
            evolution.mode == MutationPopulationEvolution.FORBID -> {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT,
                        message +
                            "Absence alone cannot distinguish a legitimate code evolution from an " +
                            "incomplete or narrowed measurement, so normal ratchet verification fails " +
                            "closed. Population removal requires explicit baseline-evolution authority.",
                        findingId = id,
                        modulePath = base.module,
                    )
            }

            record == null -> {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT,
                        message + "Authorized evolution does not name this identity.",
                        findingId = id,
                        modulePath = base.module,
                    )
            }

            else -> {
                diagnostics +=
                    VerificationDiagnostic.warning(
                        DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT,
                        message + "Authorized population evolution: ${record.reason}; " +
                            "issue=${record.issue ?: "none"}, targetPhase=${record.targetPhase ?: "none"}.",
                    )
            }
        }
    }
    return diagnostics
}

private fun evolutionEvidenceDiagnostics(
    baseById: Map<String, MutationOutcome>,
    candidateIds: Set<String>,
    candidatePopulation: MutationPopulationBaseline,
    evolution: MutationEvolutionContext,
): List<VerificationDiagnostic> {
    if (evolution.mode != MutationPopulationEvolution.RECORDED_EVOLUTION) return emptyList()
    val diagnostics = mutableListOf<VerificationDiagnostic>()
    val proof = evolution.evidence.proof
    if (proof == null) {
        diagnostics +=
            VerificationDiagnostic.failure(
                DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT,
                "M21: recorded evolution requires a proof from an exact fresh canonical measurement; " +
                    "authority without measurement evidence is not accepted.",
            )
    } else if (!proof.matches(candidatePopulation)) {
        diagnostics +=
            VerificationDiagnostic.failure(
                DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT,
                "M21: recorded evolution proof does not match the committed candidate population.",
            )
    }
    val removedIds = baseById.keys - candidateIds - evolution.excludedIdentities
    val records = evolution.evidence.records
    for (id in records.byIdentity().keys - removedIds) {
        diagnostics +=
            VerificationDiagnostic.failure(
                DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT,
                "M21: evolution record ${id.take(M21_ID_SHORT_LENGTH)} does not name a base identity " +
                    "disappearing in this transition.",
                findingId = id,
            )
    }
    for (record in records.records) {
        if (record.fromBaseSha != evolution.baseSha) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT,
                    "M21: evolution record ${record.id.take(M21_ID_SHORT_LENGTH)} is bound to base " +
                        "${record.fromBaseSha}, not this transition base ${evolution.baseSha}.",
                    findingId = record.id,
                )
        }
    }
    return diagnostics
}
