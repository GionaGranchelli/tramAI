package dev.tramai.build.quality

import kotlin.math.abs

/**
 * Base-authoritative coverage ratchet (Epic 10.3b).
 *
 * Replaces the 10.3a-era verifier that trusted the candidate's own
 * configuration. This verifier judges the CURRENT measurement against the
 * BASE authority (policy + baseline from the PR base / master), and the
 * CANDIDATE policy against the base policy, so a PR cannot change the rules
 * under which its own coverage is judged.
 *
 * Three inputs are deliberately distinct:
 * - [authority]  — base test-quality.yml + base coverage-baseline.json (the law)
 * - [candidateConfiguration] / [candidateBaseline] — what the PR commits (the proposal)
 * - [current]    — freshly generated JaCoCo CoverageData (the evidence)
 */
class CoveragePolicyDeltaVerifier {
    fun verify(
        authority: CoverageAuthority,
        candidateConfiguration: TestQualityConfiguration,
        candidateBaseline: CoverageData,
        current: CoverageData,
    ): List<VerificationDiagnostic> {
        val base = authority.configuration
        val baseBaseline = authority.baseline

        // ── 10.3b-G: structural integrity — raw counters are the authority ──
        // Recompute every stored percentage from raw counters; a stored double
        // that disagrees is evidence of tampering, not a rounding artifact.
        // Keys must also agree with the governing config's criticalModules
        // (review P2): a phantom extra baseline module must not be insertable.
        val candidateCritical = candidateConfiguration.criticalModules.toSet()
        val diagnostics =
            mutableListOf<VerificationDiagnostic>().apply {
                addAll(structuralIntegrity("base baseline", baseBaseline, base.criticalModules.toSet()))
                addAll(structuralIntegrity("candidate baseline", candidateBaseline, candidateCritical))
                addAll(structuralIntegrity("current measurement", current, candidateCritical))
            }

        // Status must be measured on all three inputs.
        if (baseBaseline.status != "measured" || current.status != "measured" ||
            candidateBaseline.status != "measured"
        ) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.TEST_QUALITY_STATUS_PENDING,
                    "Coverage base baseline, candidate baseline, and current measurement must all be measured",
                )
            return diagnostics
        }

        val baseTolerance = base.coverage.regressionTolerancePercentagePoints
        val baseCriticalModules = base.criticalModules.toSet()
        diagnostics +=
            verifyBaseRegression(
                baseCriticalModules = baseCriticalModules,
                baseBaseline = baseBaseline,
                current = current,
                tolerance = baseTolerance,
            )
        diagnostics +=
            verifyPolicyMonotonicity(
                baseCriticalModules = baseCriticalModules,
                baseTolerance = baseTolerance,
                base = base,
                candidateConfiguration = candidateConfiguration,
            )
        diagnostics +=
            verifyCandidateBaseline(
                baseCriticalModules = baseCriticalModules,
                baseBaseline = baseBaseline,
                candidateBaseline = candidateBaseline,
                current = current,
                tolerance = baseTolerance,
            )
        diagnostics +=
            verifyNewModules(
                baseCriticalModules = baseCriticalModules,
                candidateConfiguration = candidateConfiguration,
                candidateBaseline = candidateBaseline,
                current = current,
            )
        return diagnostics
    }

    /**
     * 10.3b-C: current measurement regression uses BASE authority.
     * Iterate BASE critical modules with BASE tolerance against BASE
     * baseline. The candidate's tolerance/criticalModules are never used
     * to judge the current measurement.
     */
    private fun verifyBaseRegression(
        baseCriticalModules: Set<String>,
        baseBaseline: CoverageData,
        current: CoverageData,
        tolerance: Double,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        baseCriticalModules.forEach { module ->
            val baseline = baseBaseline.criticalModules[module]
            val measured = current.criticalModules[module]
            if (measured == null) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.COVERAGE_REPORT_MISSING,
                        "Missing critical coverage measurement for base-critical module $module",
                        modulePath = module,
                    )
                return@forEach
            }
            if (baseline == null) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT,
                        "Base baseline has no entry for base-critical module $module",
                        modulePath = module,
                    )
                return@forEach
            }
            if (measured.linesTotal == 0) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.COVERAGE_REPORT_MISSING,
                        "Base-critical module $module has zero executable lines",
                        modulePath = module,
                    )
                return@forEach
            }
            compare(module, "line", baseline.lineCoverage, measured.lineCoverage, tolerance)
                ?.let(diagnostics::add)
            compare(module, "branch", baseline.branchCoverage, measured.branchCoverage, tolerance)
                ?.let(diagnostics::add)
        }
        return diagnostics
    }

    /**
     * 10.3b-D: policy monotonicity — candidate cannot weaken the law.
     */
    private fun verifyPolicyMonotonicity(
        baseCriticalModules: Set<String>,
        baseTolerance: Double,
        base: TestQualityConfiguration,
        candidateConfiguration: TestQualityConfiguration,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val candidateCritical = candidateConfiguration.criticalModules.toSet()

        // B06: candidate removes a base critical module → FAIL.
        val removed = baseCriticalModules - candidateCritical
        if (removed.isNotEmpty()) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_CRITICAL_MODULE_REMOVED,
                    "Candidate removes base-critical module(s) from scrutiny: ${removed.sorted().joinToString()}",
                )
        }

        // B07/B08: tolerance may tighten, never loosen. NO runtime 1.0pp slack
        // here — repeated small weakenings would stair-step the ratchet down.
        val candidateTolerance = candidateConfiguration.coverage.regressionTolerancePercentagePoints
        if (candidateTolerance > baseTolerance + EPSILON) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_TOLERANCE_WEAKENED,
                    "Candidate widens coverage regression tolerance from $baseTolerance to $candidateTolerance",
                    baselineValue = baseTolerance.toString(),
                    currentValue = candidateTolerance.toString(),
                )
        }

        // B09/B10/B11: candidate exclusions must be a SUBSET of base
        // exclusions with exact pattern+reason identity. Adding, broadening,
        // or silently re-authorizing an exclusion → FAIL.
        val baseExclusions = base.coverage.exclusions.toSet()
        val candidateExclusions = candidateConfiguration.coverage.exclusions.toSet()
        val addedExclusions = candidateExclusions - baseExclusions
        if (addedExclusions.isNotEmpty()) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_EXCLUSION_UNDOCUMENTED,
                    "Candidate adds or alters coverage exclusions not authorized by base: " +
                        addedExclusions.sortedBy { it.pattern }.joinToString { "${it.pattern} (${it.reason})" },
                )
        }
        return diagnostics
    }

    /**
     * 10.3b-E: candidate baseline cannot weaken master's baseline.
     * No tolerance here: every module present in the base baseline must be
     * present in the candidate baseline with >= line and branch coverage.
     * The candidate may RAISE a baseline, but only if current satisfies it.
     */
    private fun verifyCandidateBaseline(
        baseCriticalModules: Set<String>,
        baseBaseline: CoverageData,
        candidateBaseline: CoverageData,
        current: CoverageData,
        tolerance: Double,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        baseBaseline.criticalModules.forEach { (module, baseModule) ->
            val candidateModule = candidateBaseline.criticalModules[module]
            if (candidateModule == null) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.COVERAGE_BASELINE_WEAKENED,
                        "Candidate baseline removes module $module from the committed baseline",
                        modulePath = module,
                    )
                return@forEach
            }
            if (candidateModule.lineCoverage < baseModule.lineCoverage - EPSILON) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.COVERAGE_BASELINE_WEAKENED,
                        "Candidate lowers $module line baseline from ${baseModule.lineCoverage} to " +
                            "${candidateModule.lineCoverage}",
                        modulePath = module,
                        baselineValue = baseModule.lineCoverage.toString(),
                        currentValue = candidateModule.lineCoverage.toString(),
                    )
            }
            if (candidateModule.branchCoverage < baseModule.branchCoverage - EPSILON) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.COVERAGE_BASELINE_WEAKENED,
                        "Candidate lowers $module branch baseline from ${baseModule.branchCoverage} to " +
                            "${candidateModule.branchCoverage}",
                        modulePath = module,
                        baselineValue = baseModule.branchCoverage.toString(),
                        currentValue = candidateModule.branchCoverage.toString(),
                    )
            }
        }

        // Candidate may RAISE baseline, but current must still satisfy it.
        candidateBaseline.criticalModules.forEach { (module, candidateModule) ->
            if (module !in baseCriticalModules) return@forEach // new modules handled separately
            val measured = current.criticalModules[module] ?: return@forEach
            compare(module, "line", candidateModule.lineCoverage, measured.lineCoverage, tolerance)
                ?.let(diagnostics::add)
            compare(module, "branch", candidateModule.branchCoverage, measured.branchCoverage, tolerance)
                ?.let(diagnostics::add)
        }
        return diagnostics
    }

    /**
     * 10.3b-F: new critical modules must be real, not vacuous declarations.
     */
    private fun verifyNewModules(
        baseCriticalModules: Set<String>,
        candidateConfiguration: TestQualityConfiguration,
        candidateBaseline: CoverageData,
        current: CoverageData,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val newModules = candidateConfiguration.criticalModules.toSet() - baseCriticalModules
        newModules.forEach { module ->
            val candidateModule = candidateBaseline.criticalModules[module]
            val measured = current.criticalModules[module]
            if (candidateModule == null || measured == null || measured.linesTotal == 0) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.COVERAGE_NEW_MODULE_UNMEASURED,
                        "New critical module $module lacks full enrollment: " +
                            "requires module catalog entry, JaCoCo report, non-zero population, " +
                            "candidate baseline entry, and current measurement",
                        modulePath = module,
                    )
                return@forEach
            }
            // First-enrollment contract: the newly committed baseline entry
            // must match the freshly measured population/coverage — this
            // prevents freezing a meaningless floor (declare critical at 82%,
            // commit baseline at 5%).
            val mismatch =
                candidateModule.linesTotal != measured.linesTotal ||
                    candidateModule.branchesTotal != measured.branchesTotal ||
                    abs(candidateModule.lineCoverage - measured.lineCoverage) > EPSILON ||
                    abs(candidateModule.branchCoverage - measured.branchCoverage) > EPSILON
            if (mismatch) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.COVERAGE_NEW_MODULE_UNMEASURED,
                        "New critical module $module baseline entry must match freshly measured " +
                            "population/coverage (lines ${measured.linesTotal}, branch ${measured.branchesTotal})",
                        modulePath = module,
                    )
            }
        }
        return diagnostics
    }

    private fun structuralIntegrity(
        label: String,
        data: CoverageData,
        expectedModules: Set<String>,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()

        // byModule/criticalModules must agree exactly, and the baseline's
        // module keys must match the governing config's criticalModules — no
        // phantom extra module, no missing one (review P2: byModule==
        // criticalModules alone can't catch a phantom).
        val keyMismatch =
            data.byModule.keys != data.criticalModules.keys ||
                data.criticalModules.keys != expectedModules
        if (keyMismatch) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT,
                    "$label byModule keys ${data.byModule.keys.sorted()} / " +
                        "criticalModules keys ${data.criticalModules.keys.sorted()} disagree with " +
                        "config criticalModules ${expectedModules.sorted()}",
                )
            return diagnostics
        }
        data.byModule.forEach { (module, m) ->
            diagnostics += moduleIntegrity(label, module, m, data.criticalModules[module])
        }
        diagnostics += overallIntegrity(label, data)
        return diagnostics
    }

    private fun moduleIntegrity(
        label: String,
        module: String,
        m: ModuleCoverage,
        critical: ModuleCoverage?,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        if (critical == null || critical != m) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT,
                    "$label module $module differs between byModule and criticalModules",
                    modulePath = module,
                )
            return diagnostics
        }
        diagnostics += rawEvidenceIntegrity(label, module, m)
        if (m.linesCovered + m.linesMissed != m.linesTotal) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT,
                    "$label module $module lines $m.linesCovered + $m.linesMissed != $m.linesTotal",
                    modulePath = module,
                )
        }
        if (m.branchesCovered + m.branchesMissed != m.branchesTotal) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT,
                    "$label module $module branches $m.branchesCovered + $m.branchesMissed != $m.branchesTotal",
                    modulePath = module,
                )
        }
        if (m.linesTotal > 0) {
            val recomputed = PERCENT * m.linesCovered / m.linesTotal
            if (abs(recomputed - m.lineCoverage) > EPSILON) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT,
                        "$label module $module stored lineCoverage ${m.lineCoverage} != recomputed $recomputed",
                        modulePath = module,
                        baselineValue = m.lineCoverage.toString(),
                        currentValue = recomputed.toString(),
                    )
            }
        }
        if (m.branchesTotal > 0) {
            val recomputed = PERCENT * m.branchesCovered / m.branchesTotal
            if (abs(recomputed - m.branchCoverage) > EPSILON) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT,
                        "$label module $module stored branchCoverage ${m.branchCoverage} != recomputed $recomputed",
                        modulePath = module,
                        baselineValue = m.branchCoverage.toString(),
                        currentValue = recomputed.toString(),
                    )
            }
        }
        return diagnostics
    }

    /**
     * Review P2: raw-evidence sanity for a single module — non-negative
     * counters, non-empty critical line population, finite in-range stored
     * percentages. A zero-denominator entry carrying a fabricated stored
     * percentage would otherwise bypass the recompute above.
     */
    private fun rawEvidenceIntegrity(
        label: String,
        module: String,
        m: ModuleCoverage,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val negative =
            m.linesCovered < 0 || m.linesMissed < 0 || m.linesTotal < 0 ||
                m.branchesCovered < 0 || m.branchesMissed < 0 || m.branchesTotal < 0
        if (negative) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT,
                    "$label module $module has negative raw counters " +
                        "(lines $m.linesCovered/$m.linesMissed/$m.linesTotal, " +
                        "branches $m.branchesCovered/$m.branchesMissed/$m.branchesTotal)",
                    modulePath = module,
                )
        }
        if (m.linesTotal == 0) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT,
                    "$label module $module is critical but has zero executable lines",
                    modulePath = module,
                )
        }
        if (!m.lineCoverage.isFinite() || m.lineCoverage < 0.0 || m.lineCoverage > PERCENT) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT,
                    "$label module $module stored lineCoverage ${m.lineCoverage} outside [0,$PERCENT]",
                    modulePath = module,
                )
        }
        if (!m.branchCoverage.isFinite() || m.branchCoverage < 0.0 || m.branchCoverage > PERCENT) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT,
                    "$label module $module stored branchCoverage ${m.branchCoverage} outside [0,$PERCENT]",
                    modulePath = module,
                )
        }
        return diagnostics
    }

    private fun overallIntegrity(
        label: String,
        data: CoverageData,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val totalLines = data.byModule.values.sumOf { it.linesTotal }
        val coveredLines = data.byModule.values.sumOf { it.linesCovered }
        val totalBranches = data.byModule.values.sumOf { it.branchesTotal }
        val coveredBranches = data.byModule.values.sumOf { it.branchesCovered }
        if (totalLines > 0) {
            val recomputed = PERCENT * coveredLines / totalLines
            if (abs(recomputed - data.overallLineCoverage) > EPSILON) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT,
                        "$label stored overallLineCoverage ${data.overallLineCoverage} != recomputed $recomputed",
                        baselineValue = data.overallLineCoverage.toString(),
                        currentValue = recomputed.toString(),
                    )
            }
        }
        if (totalBranches > 0) {
            val recomputed = PERCENT * coveredBranches / totalBranches
            if (abs(recomputed - data.overallBranchCoverage) > EPSILON) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT,
                        "$label stored overallBranchCoverage ${data.overallBranchCoverage} != recomputed $recomputed",
                        baselineValue = data.overallBranchCoverage.toString(),
                        currentValue = recomputed.toString(),
                    )
            }
        }
        return diagnostics
    }

    private fun compare(
        module: String,
        counter: String,
        baseline: Double,
        current: Double,
        tolerance: Double,
    ): VerificationDiagnostic? {
        if (current + tolerance < baseline - EPSILON) {
            return VerificationDiagnostic.failure(
                DiagnosticCode.COVERAGE_REGRESSION,
                "$module $counter coverage regressed from ${"%.2f".format(baseline)}% to ${"%.2f".format(current)}% " +
                    "(tolerance ${"%.2f".format(tolerance)} percentage points)",
                modulePath = module,
                baselineValue = baseline.toString(),
                currentValue = current.toString(),
            )
        }
        return null
    }

    private companion object {
        const val EPSILON = 0.0001
        const val PERCENT = 100.0
    }
}
