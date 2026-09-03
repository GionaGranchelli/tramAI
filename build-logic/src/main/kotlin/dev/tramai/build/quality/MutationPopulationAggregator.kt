package dev.tramai.build.quality

import org.gradle.api.GradleException

/**
 * 10.3c1: aggregates parsed PIT reports into the exact mutation population.
 *
 * Distinct from MutationBaselineVerifier.aggregate (legacy, score-oriented):
 * this persists every mutant, fails on same-family identity collisions, and
 * validates non-vacuity per configured family.
 */
object MutationPopulationAggregator {
    fun aggregate(
        reports: List<ParsedMutationReport>,
        configuredFamilies: Map<String, TestQualityConfiguration.MutationTargetFamily>,
        measuredCommit: String,
        semantics: MutationAnalyzerSemantics,
    ): MutationPopulationBaseline {
        validateSemantics(semantics)
        val records = reports.flatMap { it.mutants }
        validateRecords(records)
        validateFamilies(records, configuredFamilies)
        val byFamily =
            configuredFamilies.keys.sorted().associateWith { family ->
                familyPopulation(family, records.filter { it.family == family }, configuredFamilies)
            }
        val outcomes =
            records
                .map { record ->
                    MutationOutcome(
                        identity = record.identity,
                        family = record.family,
                        module = record.module,
                        className = record.className,
                        method = record.method,
                        methodDescription = record.methodDescription,
                        mutator = record.mutator,
                        description = record.description,
                        block = record.block,
                        index = record.index,
                        sourceFile = record.sourceFile,
                        line = record.line,
                        status = record.status,
                        outcome = MutationOutcome.canonical(record.status),
                    )
                }.sortedWith(
                    compareBy<MutationOutcome> { it.family }
                        .thenBy { it.identity }
                        .thenBy { it.status },
                )

        return MutationPopulationBaseline(
            measuredCommit = measuredCommit,
            analyzer = semantics,
            byFamily = byFamily,
            mutants = outcomes,
        )
    }

    private fun validateSemantics(semantics: MutationAnalyzerSemantics) {
        // M19/C1: the exact-population artifact is approval authority, so the
        // metadata it persists must describe the SAME analyzer semantics that
        // MutationProbeInitScript actually renders. This prevents a future
        // edit from changing PIT configuration while leaving the recorded
        // analyzer block stale (or vice versa).
        validatePinnedSemantics(semantics)
        val canonical = canonicalSemantics()
        if (semantics != canonical) {
            throw GradleException(
                "MutationAnalyzerSemantics drift from canonical PIT renderer semantics (C1): " +
                    "expected=$canonical, actual=$semantics",
            )
        }
    }

    private fun validatePinnedSemantics(semantics: MutationAnalyzerSemantics) {
        if (semantics.pluginVersion.isBlank() || semantics.engineVersion.isBlank() || semantics.mutators.isEmpty()) {
            throw GradleException(
                "MutationAnalyzerSemantics must pin pluginVersion, engineVersion and mutators (M19).",
            )
        }
        if (semantics.timeoutConst <= 0) {
            throw GradleException(
                "MutationAnalyzerSemantics must pin a positive timeoutConst (C1); timeout is mutation semantics.",
            )
        }
    }

    private fun canonicalSemantics(): MutationAnalyzerSemantics =
        MutationAnalyzerSemantics(
            pluginVersion = MutationProbeInitScript.PIT_PLUGIN_VERSION,
            engineVersion = MutationProbeInitScript.PIT_ENGINE_VERSION,
            mutators = MutationProbeInitScript.PIT_MUTATORS,
            timeoutConst = MutationProbeInitScript.TIMEOUT_CONST_MILLIS.toInt(),
            timeoutFactor = MutationProbeInitScript.TIMEOUT_FACTOR,
        )

    private fun validateRecords(records: List<MutationRecord>) {
        // M18: every mutant must carry a stable identity.
        for (record in records) {
            if (record.identity.isBlank()) {
                throw GradleException(
                    "Mutation in family '${record.family}' module '${record.module}' has a blank identity (M18).",
                )
            }
        }
        // M06: two raw mutants with the same stable identity inside one
        // family/module is a hard failure. Never silently deduplicate.
        val seen = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        for (record in records) {
            val key = record.family to record.module
            val identities = seen.getOrPut(key) { mutableSetOf() }
            if (!identities.add(record.identity)) {
                throw GradleException(
                    "Mutation identity collision in family '${record.family}' module '${record.module}': " +
                        "two mutants share identity ${record.identity} (${record.className}#${record.method} " +
                        "${record.mutator} @block ${record.block} index ${record.index}). " +
                        "Identity schema v2 requires distinct mutants to be distinguishable.",
                )
            }
        }
    }

    private fun validateFamilies(
        records: List<MutationRecord>,
        configuredFamilies: Map<String, TestQualityConfiguration.MutationTargetFamily>,
    ) {
        // C6: every configured family must be present and non-empty.
        val presentFamilies = records.map { it.family }.toSet()
        for (family in configuredFamilies.keys) {
            if (family !in presentFamilies) {
                throw GradleException(
                    "Configured mutation family '$family' produced no report/population (M01/M02). " +
                        "A configured family must not be dropped.",
                )
            }
            if (records.none { it.family == family }) {
                throw GradleException("Configured mutation family '$family' produced zero mutants (M02).")
            }
        }
        // M17: no family beyond the configured ones.
        rejectUnconfiguredFamilies(presentFamilies, configuredFamilies)
    }

    private fun rejectUnconfiguredFamilies(
        presentFamilies: Set<String>,
        configuredFamilies: Map<String, TestQualityConfiguration.MutationTargetFamily>,
    ) {
        for (family in presentFamilies) {
            if (family !in configuredFamilies) {
                throw GradleException("Unconfigured mutation family '$family' appeared in reports.")
            }
        }
    }

    private fun familyPopulation(
        family: String,
        familyRecords: List<MutationRecord>,
        configuredFamilies: Map<String, TestQualityConfiguration.MutationTargetFamily>,
    ): MutationFamilyPopulation {
        val killed = familyRecords.count { it.status == "KILLED" }
        val survived = familyRecords.count { it.status == "SURVIVED" }
        val noCoverage = familyRecords.count { it.status == "NO_COVERAGE" }
        val timedOut = familyRecords.count { it.status == "TIMED_OUT" }
        val known = setOf("KILLED", "SURVIVED", "NO_COVERAGE", "TIMED_OUT")
        val errors = familyRecords.count { it.status !in known }
        return MutationFamilyPopulation(
            family = family,
            modules = configuredFamilies.getValue(family).modules.sorted(),
            totalMutants = familyRecords.size,
            killedMutants = killed,
            survivedMutants = survived,
            noCoverageMutants = noCoverage,
            timedOutMutants = timedOut,
            errorMutants = errors,
            mutationScore = if (familyRecords.isEmpty()) 0.0 else 100.0 * killed / familyRecords.size,
        )
    }
}
