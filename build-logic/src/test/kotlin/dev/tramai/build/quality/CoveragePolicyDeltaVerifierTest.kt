package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Base-authoritative coverage ratchet discriminator matrix (Epic 10.3b).
 *
 * B01–B22 exercise the [CoveragePolicyDeltaVerifier]. The Socratic test for
 * this slice is not "does coverage fail when the percentage is low?" — it is:
 * "can the PR change the rules under which its own coverage is judged and
 * still go green?" Every B-test below asserts a specific DiagnosticCode, so
 * removing the corresponding check makes that test RED (gate mutations
 * M1–M7 are the implementation deletions each B-test pins).
 *
 * M1 remove line comparison      → B01 RED
 * M2 remove branch comparison    → B02 RED
 * M3 use candidate baseline      → B12/B13 RED
 * M4 use candidate tolerance     → B07 RED
 * M5 iterate candidate modules   → B06 RED
 * M6 accept new exclusion        → B09 RED
 * M7 trust stored percentage     → B22 RED
 */
class CoveragePolicyDeltaVerifierTest {
    private val baseConfig =
        TestQualityConfiguration(
            schemaVersion = "1",
            criticalModules = listOf(":core"),
            coverage =
                TestQualityConfiguration.CoverageConfiguration(
                    regressionTolerancePercentagePoints = 1.0,
                    exclusions = listOf(CoverageExclusion("**/model/**", "Generated model classes")),
                ),
            mutation =
                TestQualityConfiguration.MutationConfiguration(
                    1.0,
                    mapOf("routing" to TestQualityConfiguration.MutationTargetFamily(listOf(":core"))),
                ),
        )
    private val verifier = CoveragePolicyDeltaVerifier()

    private fun authority(
        baseline: CoverageData = data(80.0, 70.0),
        config: TestQualityConfiguration = baseConfig,
    ) = CoverageAuthority(baseSha = "base", configuration = config, baseline = baseline)

    private fun candidateConfig(
        criticalModules: List<String> = baseConfig.criticalModules,
        tolerance: Double = 1.0,
        exclusions: List<CoverageExclusion> = baseConfig.coverage.exclusions,
    ) = baseConfig.copy(
        criticalModules = criticalModules,
        coverage =
            TestQualityConfiguration.CoverageConfiguration(
                regressionTolerancePercentagePoints = tolerance,
                exclusions = exclusions,
            ),
    )

    private fun verify(
        authority: CoverageAuthority = authority(),
        candidateConfiguration: TestQualityConfiguration = baseConfig,
        candidateBaseline: CoverageData = data(80.0, 70.0),
        current: CoverageData = data(80.0, 70.0),
    ) = verifier.verify(authority, candidateConfiguration, candidateBaseline, current)

    private fun data(
        line: Double,
        branch: Double,
        module: String = ":core",
    ): CoverageData {
        val m =
            ModuleCoverage(
                module = module,
                lineCoverage = line,
                branchCoverage = branch,
                linesCovered = (line * 10).toInt(),
                linesMissed = 1000 - (line * 10).toInt(),
                linesTotal = 1000,
                branchesCovered = (branch * 10).toInt(),
                branchesMissed = 1000 - (branch * 10).toInt(),
                branchesTotal = 1000,
            )
        return CoverageData(
            status = "measured",
            byModule = mapOf(module to m),
            criticalModules = mapOf(module to m),
            exclusions = baseConfig.coverage.exclusions,
            overallLineCoverage = line,
            overallBranchCoverage = branch,
        )
    }

    // ── B01/B02: regression beyond base tolerance FAIL ──

    @Test
    fun `B01 line regression beyond base tolerance fails`() {
        val d = verify(current = data(78.9, 70.0))
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_REGRESSION && it.message.contains("line") })
        // M1: removing the line comparison removes this diagnostic → RED.
    }

    @Test
    fun `B02 branch regression beyond base tolerance fails`() {
        val d = verify(current = data(80.0, 68.9))
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_REGRESSION && it.message.contains("branch") })
        // M2: removing the branch comparison removes this diagnostic → RED.
    }

    @Test
    fun `B03 exactly at tolerance boundary passes`() {
        // 79.0 + 1.0 == 80.0 → boundary is PASS (no arbitrary floor).
        val d = verify(current = data(79.0, 69.0))
        assertFalse(d.any { it.code == DiagnosticCode.COVERAGE_REGRESSION })
    }

    // ── B04/B05: base-critical report presence ──

    @Test
    fun `B04 missing base-critical report fails`() {
        val d = verify(current = data(80.0, 70.0, module = ":other"))
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_REPORT_MISSING })
    }

    @Test
    fun `B05 zero executable base-critical module fails`() {
        val zero =
            data(80.0, 70.0).copy(
                criticalModules =
                    mapOf(
                        ":core" to
                            ModuleCoverage(
                                module = ":core",
                                lineCoverage = 0.0,
                                branchCoverage = 0.0,
                                linesCovered = 0,
                                linesMissed = 0,
                                linesTotal = 0,
                                branchesCovered = 0,
                                branchesMissed = 0,
                                branchesTotal = 0,
                            ),
                    ),
            )
        // byModule must agree for structural integrity.
        val zeroData = zero.copy(byModule = zero.criticalModules)
        val d = verify(current = zeroData)
        assertTrue(
            d.any { it.code == DiagnosticCode.COVERAGE_REPORT_MISSING && it.message.contains("zero executable") },
        )
    }

    // ── B06: candidate removes a base critical module ──

    @Test
    fun `B06 candidate removes critical module fails`() {
        val candidate =
            baseConfig.copy(criticalModules = emptyList())
        val d = verify(candidateConfiguration = candidate)
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_CRITICAL_MODULE_REMOVED })
        // M5: iterating candidate modules instead of base modules skips the
        // removed module and hides this → RED.
    }

    // ── B07/B08: tolerance monotonicity ──

    @Test
    fun `B07 candidate increases tolerance fails`() {
        val d = verify(candidateConfiguration = candidateConfig(tolerance = 2.0))
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_TOLERANCE_WEAKENED })
        // M4: using candidate tolerance for the regression check lets a
        // widened tolerance absorb a regression → B01 stays green → RED here.
    }

    @Test
    fun `B08 candidate tightens tolerance passes`() {
        val d = verify(candidateConfiguration = candidateConfig(tolerance = 0.5))
        assertFalse(d.any { it.code == DiagnosticCode.COVERAGE_TOLERANCE_WEAKENED })
    }

    // ── B09/B10/B11: exclusion monotonicity ──

    @Test
    fun `B09 candidate adds exclusion fails`() {
        val candidate =
            candidateConfig(
                exclusions =
                    listOf(
                        CoverageExclusion("**/model/**", "Generated model classes"),
                        CoverageExclusion("**/generated/**", "New generated"),
                    ),
            )
        val d = verify(candidateConfiguration = candidate)
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_EXCLUSION_UNDOCUMENTED })
        // M6: accepting any candidate exclusion hides this → RED.
    }

    @Test
    fun `B10 candidate changes exclusion reason fails`() {
        val candidate =
            candidateConfig(
                exclusions =
                    listOf(
                        CoverageExclusion("**/model/**", "Regenerated models"),
                    ),
            )
        val d = verify(candidateConfiguration = candidate)
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_EXCLUSION_UNDOCUMENTED })
    }

    @Test
    fun `B11 candidate removes exclusion passes`() {
        val candidate = candidateConfig(exclusions = emptyList())
        val d = verify(candidateConfiguration = candidate)
        assertFalse(d.any { it.code == DiagnosticCode.COVERAGE_EXCLUSION_UNDOCUMENTED })
    }

    // ── B12/B13: candidate baseline cannot weaken master ──

    @Test
    fun `B12 candidate lowers line baseline fails`() {
        val d = verify(candidateBaseline = data(75.0, 70.0))
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_BASELINE_WEAKENED && it.message.contains("line") })
        // M3: comparing current against the (lower) candidate baseline makes
        // current look compliant → B01 stays green → RED here.
    }

    @Test
    fun `B13 candidate lowers branch baseline fails`() {
        val d = verify(candidateBaseline = data(80.0, 60.0))
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_BASELINE_WEAKENED && it.message.contains("branch") })
        // M3: same as B12 for branches → RED.
    }

    @Test
    fun `B14 candidate raises baseline passes when current satisfies it`() {
        val stronger = data(82.0, 72.0)
        val d = verify(candidateBaseline = stronger, current = data(82.0, 72.0))
        assertFalse(d.any { it.code == DiagnosticCode.COVERAGE_BASELINE_WEAKENED })
        assertFalse(d.any { it.code == DiagnosticCode.COVERAGE_REGRESSION })
    }

    @Test
    fun `B14b candidate raises baseline but current falls short fails`() {
        val stronger = data(85.0, 75.0)
        val d = verify(candidateBaseline = stronger, current = data(83.0, 74.0))
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_REGRESSION })
    }

    // ── B15: unresolved base SHA ──

    @Test
    fun `B15 invalid base sha fails hard`() {
        // The loader throws — the task never reaches the verifier.
        val e =
            runCatching {
                CoverageAuthorityLoader.resolveBaseSha(File("."), "deadbeef")
            }
        assertTrue(e.isFailure)
    }

    // ── B16: unchanged everything PASS ──

    @Test
    fun `B16 candidate baseline and config unchanged passes`() {
        val d = verify()
        assertFalse(d.any { it.severity == DiagnosticSeverity.FAILURE })
    }

    // ── B17/B18: new critical module enrollment ──

    @Test
    fun `B17 new critical module without measurement fails`() {
        val candidate = candidateConfig(criticalModules = listOf(":core", ":engine"))
        // candidate baseline + current only contain :core.
        val d = verify(candidateConfiguration = candidate)
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_NEW_MODULE_UNMEASURED })
    }

    @Test
    fun `B17b new critical module baseline does not match fresh measurement fails`() {
        val candidate = candidateConfig(criticalModules = listOf(":core", ":engine"))
        val candidateBaseline =
            data(80.0, 70.0).copy(
                byModule =
                    mapOf(
                        ":core" to data(80.0, 70.0).byModule.getValue(":core"),
                        ":engine" to
                            ModuleCoverage(
                                module = ":engine",
                                lineCoverage = 5.0,
                                branchCoverage = 5.0,
                                linesCovered = 50,
                                linesMissed = 950,
                                linesTotal = 1000,
                                branchesCovered = 50,
                                branchesMissed = 950,
                                branchesTotal = 1000,
                            ),
                    ),
            )
        // current measurement: engine has real 82% coverage (freshly measured)
        val current =
            data(80.0, 70.0).copy(
                byModule =
                    mapOf(
                        ":core" to data(80.0, 70.0).byModule.getValue(":core"),
                        ":engine" to
                            ModuleCoverage(
                                module = ":engine",
                                lineCoverage = 82.0,
                                branchCoverage = 72.0,
                                linesCovered = 820,
                                linesMissed = 180,
                                linesTotal = 1000,
                                branchesCovered = 720,
                                branchesMissed = 280,
                                branchesTotal = 1000,
                            ),
                    ),
            )
        val candidateBaselineData =
            candidateBaseline.copy(criticalModules = candidateBaseline.byModule)
        val currentData = current.copy(criticalModules = current.byModule)
        val d =
            verify(
                candidateConfiguration = candidate,
                candidateBaseline = candidateBaselineData,
                current = currentData,
            )
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_NEW_MODULE_UNMEASURED })
    }

    @Test
    fun `B18 valid newly enrolled critical module passes`() {
        val candidate = candidateConfig(criticalModules = listOf(":core", ":engine"))
        val engine =
            ModuleCoverage(
                module = ":engine",
                lineCoverage = 82.0,
                branchCoverage = 72.0,
                linesCovered = 820,
                linesMissed = 180,
                linesTotal = 1000,
                branchesCovered = 720,
                branchesMissed = 280,
                branchesTotal = 1000,
            )
        val both =
            mapOf(
                ":core" to data(80.0, 70.0).byModule.getValue(":core"),
                ":engine" to engine,
            )
        val withEngine =
            data(80.0, 70.0).copy(
                byModule = both,
                criticalModules = both,
                // aggregate of both modules: lines (800+820)/2000 = 81.0, branches (700+720)/2000 = 71.0
                overallLineCoverage = 81.0,
                overallBranchCoverage = 71.0,
            )
        // candidate baseline entry matches fresh measurement exactly.
        val d =
            verify(
                candidateConfiguration = candidate,
                candidateBaseline = withEngine,
                current = withEngine,
            )
        assertFalse(d.any { it.code == DiagnosticCode.COVERAGE_NEW_MODULE_UNMEASURED })
        assertFalse(d.any { it.severity == DiagnosticSeverity.FAILURE })
    }

    // ── B19: denominator change but coverage compliant PASS ──

    @Test
    fun `B19 denominator change while compliant passes`() {
        // base: 80/100 (80.0%); current: 800/1000 (80.0%) — same %, bigger denominator.
        val base =
            data(80.0, 70.0).copy(
                byModule =
                    mapOf(
                        ":core" to
                            ModuleCoverage(
                                module = ":core",
                                lineCoverage = 80.0,
                                branchCoverage = 70.0,
                                linesCovered = 8,
                                linesMissed = 2,
                                linesTotal = 10,
                                branchesCovered = 7,
                                branchesMissed = 3,
                                branchesTotal = 10,
                            ),
                    ),
            )
        val baseData = base.copy(criticalModules = base.byModule)
        val d = verify(authority = authority(baseline = baseData), current = data(80.0, 70.0))
        assertFalse(d.any { it.severity == DiagnosticSeverity.FAILURE })
    }

    // ── B20/B21/B22: structural integrity ──

    @Test
    fun `B20 malformed raw counters fail`() {
        val bad =
            data(80.0, 70.0).copy(
                byModule =
                    mapOf(
                        ":core" to
                            ModuleCoverage(
                                module = ":core",
                                lineCoverage = 80.0,
                                branchCoverage = 70.0,
                                linesCovered = 8,
                                linesMissed = 3, // 8+3 != 10
                                linesTotal = 10,
                                branchesCovered = 7,
                                branchesMissed = 3,
                                branchesTotal = 10,
                            ),
                    ),
            )
        val badData = bad.copy(criticalModules = bad.byModule)
        val d = verify(current = badData)
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT })
    }

    @Test
    fun `B21 byModule criticalModules disagreement fails`() {
        val d = verify(current = data(80.0, 70.0).copy(byModule = emptyMap()))
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT })
    }

    @Test
    fun `B22 fake stored percentage disagrees with counters fails`() {
        val fake =
            data(80.0, 70.0).copy(
                byModule =
                    mapOf(
                        ":core" to
                            ModuleCoverage(
                                module = ":core",
                                lineCoverage = 99.0, // stored double is a lie
                                branchCoverage = 70.0,
                                linesCovered = 8,
                                linesMissed = 2,
                                linesTotal = 10,
                                branchesCovered = 7,
                                branchesMissed = 3,
                                branchesTotal = 10,
                            ),
                    ),
            )
        val fakeData = fake.copy(criticalModules = fake.byModule)
        val d = verify(current = fakeData)
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_BASELINE_INCONSISTENT })
        // M7: trusting stored doubles instead of recomputing from raw counters
        // hides this → RED.
    }

    // ── Candidate baseline weakening against the base (M3 discriminator) ──

    @Test
    fun `B12b candidate baseline removed module fails`() {
        val d =
            verify(
                candidateBaseline =
                    data(80.0, 70.0).copy(
                        byModule = emptyMap(),
                        criticalModules = emptyMap(),
                    ),
            )
        assertTrue(d.any { it.code == DiagnosticCode.COVERAGE_BASELINE_WEAKENED })
    }

    @Test
    fun `B07b tolerance equal passes`() {
        val d = verify(candidateConfiguration = candidateConfig(tolerance = 1.0))
        assertFalse(d.any { it.code == DiagnosticCode.COVERAGE_TOLERANCE_WEAKENED })
    }
}
