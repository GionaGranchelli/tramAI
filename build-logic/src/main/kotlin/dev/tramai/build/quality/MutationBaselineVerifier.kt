package dev.tramai.build.quality

import java.io.File

class MutationBaselineVerifier(
    private val configuration: TestQualityConfiguration,
    private val repositoryRoot: File? = null
) {
    private val mutationClassifications: MutationClassifications by lazy {
        if (repositoryRoot != null) {
            MutationClassificationLoader.load(repositoryRoot)
        } else {
            MutationClassifications(schemaVersion = "1", classifications = emptyList())
        }
    }

    fun verify(committed: MutationData, current: MutationData): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        if (committed.status == "pending" || current.status == "pending") {
            diagnostics += VerificationDiagnostic.failure(
                DiagnosticCode.TEST_QUALITY_STATUS_PENDING,
                "Mutation status must never remain pending"
            )
        }
        if (committed.status != "measured" || current.status != "measured") {
            diagnostics += VerificationDiagnostic.failure(
                DiagnosticCode.MUTATION_REPORT_MISSING,
                "Mutation baseline and current mutation data must both be measured"
            )
            return diagnostics
        }

        val tolerance = configuration.mutation.regressionTolerancePercentagePoints
        configuration.mutation.targetFamilies.forEach { (family, target) ->
            val baseline = committed.byFamily[family]
            val measured = current.byFamily[family]
            if (baseline == null || measured == null) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_REPORT_MISSING,
                    "Missing mutation report for configured target family '$family'"
                )
                return@forEach
            }
            if (measured.totalMutants == 0 && target.modules.any { moduleHasProductionSources(it) }) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_TARGET_EMPTY,
                    "Configured mutation target family '$family' produced zero mutants"
                )
            }
            if (measured.mutationScore + tolerance < baseline.mutationScore) {
                val bscore = "%.2f".format(baseline.mutationScore)
                val mscore = "%.2f".format(measured.mutationScore)
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_REGRESSION,
                    "Mutation score for '$family' regressed from ${bscore}% to ${mscore}%"
                )
            }
        }

        val classificationsById = mutationClassifications.byIdentity()

        current.survivingMutants.forEach { survivor ->
            val classificationEntry = if (survivor.identity.isNotBlank()) {
                classificationsById[survivor.identity]
            } else null

            if (classificationEntry != null) {
                // Classified survivor from YAML — validate classification
                val classification = classificationEntry.classification
                if (classification == "missing-test" || classification == "known-design-ambiguity") {
                    if (classificationEntry.issue.isNullOrBlank() && classificationEntry.targetPhase.isNullOrBlank()) {
                        diagnostics += VerificationDiagnostic.failure(
                            DiagnosticCode.MUTATION_MISSING_TEST_UNTRACKED,
                            "Classified survivor ${survivor.identity.ifBlank { survivor.mutator }} " +
                                "is '$classification' but has no issue or targetPhase",
                            modulePath = survivor.module,
                            findingId = survivor.identity
                        )
                    }
                }
            } else if (survivor.behaviourFamily.isNotBlank()) {
                // Has a behaviour family but no classification → unclassified → failure
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_SURVIVOR_UNCLASSIFIED,
                    "Surviving mutant ${survivor.identity.ifBlank { survivor.mutator }} is unclassified",
                    modulePath = survivor.module,
                    findingId = survivor.identity
                )
            }
            // Old survivors (no behaviourFamily, no classification) are kept as-is

            // Legacy check: if still NO_COVERAGE and no issue/targetPhase, flag it
            if (classificationEntry == null && survivor.status == "NO_COVERAGE" &&
                survivor.issue.isNullOrBlank() && survivor.targetPhase.isNullOrBlank()
            ) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.MUTATION_MISSING_TEST_UNTRACKED,
                    "Missing-test survivor ${survivor.identity.ifBlank { survivor.mutator }} has no issue or target phase",
                    modulePath = survivor.module,
                    findingId = survivor.identity
                )
            }
        }
        return diagnostics
    }

    private fun moduleHasProductionSources(module: String): Boolean =
        configuration.criticalModules.contains(module)

    companion object {
        fun aggregate(
            reports: List<ParsedMutationReport>,
            analyzerVersion: String = "",
            measuredCommit: String = ""
        ): MutationData {
            val all = reports.flatMap { it.mutants }
            val byModule = all.groupBy { it.module }.toSortedMap().mapValues { (module, records) ->
                metrics(module, records)
            }
            val byFamily = reports.groupBy { it.family }.toSortedMap().mapValues { (family, familyReports) ->
                val records = familyReports.flatMap { it.mutants }
                val killed = records.count { it.status == "KILLED" }
                val survived = records.count { it.status == "SURVIVED" }
                val noCoverage = records.count { it.status == "NO_COVERAGE" }
                MutationFamilyMetrics(
                    family = family,
                    modules = familyReports.map { it.module }.distinct().sorted(),
                    totalMutants = records.size,
                    killedMutants = killed,
                    survivedMutants = survived,
                    noCoverageMutants = noCoverage,
                    mutationScore = score(killed, records.size)
                )
            }
            val survivors = all.filter { it.status in setOf("SURVIVED", "NO_COVERAGE") }.map {
                SurvivingMutant(
                    module = it.module,
                    file = it.sourceFile,
                    line = it.line,
                    mutator = it.mutator,
                    classification = if (it.family.isBlank()) "unclassified" else "behaviour-family",
                    description = it.description,
                    className = it.className,
                    method = it.method,
                    status = it.status,
                    identity = it.identity,
                    behaviourFamily = it.family
                )
            }.sortedWith(compareBy<SurvivingMutant> { it.module }.thenBy { it.identity })
            val killed = all.count { it.status == "KILLED" }
            val survived = all.count { it.status == "SURVIVED" }
            return MutationData(
                status = "measured",
                analyzerVersion = analyzerVersion,
                measuredCommit = measuredCommit,
                totalMutants = all.size,
                killedMutants = killed,
                survivedMutants = survived,
                mutationScore = score(killed, all.size),
                byModule = byModule,
                byFamily = byFamily,
                survivingMutants = survivors,
                unclassifiedMutants = survivors.filter { it.classification == "unclassified" }
            )
        }

        private fun metrics(module: String, records: List<MutationRecord>): ModuleMutationMetrics {
            val killed = records.count { it.status == "KILLED" }
            return ModuleMutationMetrics(
                module = module,
                generated = records.size,
                killed = killed,
                survived = records.count { it.status == "SURVIVED" },
                noCoverage = records.count { it.status == "NO_COVERAGE" },
                timedOut = records.count { it.status == "TIMED_OUT" },
                mutationScore = score(killed, records.size)
            )
        }

        private fun score(killed: Int, total: Int): Double =
            if (total == 0) 0.0 else killed * 100.0 / total
    }
}
