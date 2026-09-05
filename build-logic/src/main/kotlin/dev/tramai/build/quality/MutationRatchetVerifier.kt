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
 *   diagnostic evidence and never participate in the ratchet (C7).
 * - identity = SHA-256 over (module, class, method, descriptor, mutator,
 *   description, block, index) — see [MutationIdentity].
 *
 * Discriminator matrix (each rule maps to at least one focused test):
 * - M01 base KILLED -> candidate NON_KILLED                    = regression
 * - M02 base KILLED -> candidate KILLED                        = pass
 * - M03 approved (base-classified) survivor stays NON_KILLED   = pass
 * - M04 approved survivor -> KILLED + classification removed   = pass
 * - M05 approved survivor -> KILLED + classification retained  = stale
 * - M06 new NON_KILLED identity                                = new survivor
 * - M07 new KILLED identity                                    = pass
 * - M08 candidate self-classification (new survivor)           = fail
 * - M09 fabricated classification (never certified)            = fail
 * - M10 classification for disappeared mutant retained         = orphaned
 * - M11 base classification removed while survivor remains     = fail
 * - M12 duplicate identities                                   = fail
 * - M13 unknown/non-canonical outcome                          = fail closed
 * - M14 family narrowing (family/module set shrinks)           = fail
 * - M15 target-class / target-test narrowing                   = fail
 * - M16/M17/M18 analyzer semantics/mutator/timeout drift       = fail
 * - M19 identity-schema drift                                  = fail
 * - M20 malformed / missing / self-inconsistent authority      = fail closed
 *
 * Classification authority semantics: a PR may only REMOVE classifications
 * (when the underlying mutant dies), never add or re-author one. New
 * classifications are adjudicated on master during an enrollment ceremony and
 * become part of the base; anything a candidate adds is either self-approval
 * of its own survivor (M08) or fabrication (M09).
 *
 * No wildcards, no family allowances, no budgets, no score thresholds and no
 * mutation-score floor are consulted anywhere in this class.
 */
class MutationRatchetVerifier {
    fun verify(
        base: MutationRatchetAuthority,
        candidate: MutationRatchetCandidate,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        diagnostics += schemaAndStatusChecks(base.population, candidate.population)
        diagnostics += analyzerSemanticsChecks(base.population.analyzer, candidate.population.analyzer)
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

    private fun analyzerSemanticsChecks(
        base: MutationAnalyzerSemantics,
        candidate: MutationAnalyzerSemantics,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        if (base.pluginVersion != candidate.pluginVersion || base.engineVersion != candidate.engineVersion) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_RATCHET_SEMANTICS_DRIFT,
                    "M16: PIT semantics drift — plugin ${base.pluginVersion}->${candidate.pluginVersion}, " +
                        "engine ${base.engineVersion}->${candidate.engineVersion}. Killed/survived meaning changed.",
                )
        }
        if (base.mutators != candidate.mutators) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_RATCHET_SEMANTICS_DRIFT,
                    "M17: mutator drift — base [${base.mutators.joinToString()}] vs " +
                        "candidate [${candidate.mutators.joinToString()}]. The 11-mutator DEFAULT expansion is pinned.",
                )
        }
        if (base.timeoutConst != candidate.timeoutConst || base.timeoutFactor != candidate.timeoutFactor) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_RATCHET_SEMANTICS_DRIFT,
                    "M18: timeout drift — base ${base.timeoutConst}ms x${base.timeoutFactor} vs " +
                        "candidate ${candidate.timeoutConst}ms x${candidate.timeoutFactor}. " +
                        "Timeouts are mutation semantics.",
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
            } else if (!seen.add(mutant.identity)) {
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

        // Retained classifications (M03/M05/M10).
        (candidateClassById.keys intersect baseClassById.keys).forEach { id ->
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
            }
            // M03: approved survivor stays NON_KILLED with its classification → pass.
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
            // M04: removed + mutant KILLED → pass. Removed + mutant disappeared → pass.
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
     * The committed population's byFamily section must be the exact, consistent
     * mirror of the governing target configuration and of the mutant rows
     * themselves. A hand-trimmed byFamily that disagrees with config or with
     * row counts is evidence of tampering.
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
            return diagnostics
        }
        population.byFamily.forEach { (family, familyPopulation) ->
            val config = targetFamilies.getValue(family)
            if (familyPopulation.modules != config.modules.sorted()) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
                        "$label family '$family' byFamily modules ${familyPopulation.modules} disagree with " +
                            "config modules ${config.modules.sorted()}.",
                    )
            }
            val rowCount = population.mutants.count { it.family == family }
            if (familyPopulation.totalMutants != rowCount) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID,
                        "$label family '$family' byFamily.totalMutants=${familyPopulation.totalMutants} does not " +
                            "match the $rowCount mutant rows persisted for that family.",
                    )
            }
        }
        return diagnostics
    }

    private companion object {
        const val KILLED = "KILLED"
        const val NON_KILLED = "NON_KILLED"
        const val IDENTITY_SCHEMA_VERSION = "2"
        const val ID_SHORT_LENGTH = 8

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
    }
}
