package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverageBaselineVerifierTest {
    private val configuration = TestQualityConfiguration(
        schemaVersion = "1",
        criticalModules = listOf(":core"),
        coverage = TestQualityConfiguration.CoverageConfiguration(
            1.0,
            listOf(CoverageExclusion("**/model/**", "generated"))
        ),
        mutation = TestQualityConfiguration.MutationConfiguration(
            1.0,
            mapOf("routing" to TestQualityConfiguration.MutationTargetFamily(listOf(":core")))
        )
    )
    private val verifier = CoverageBaselineVerifier(configuration)

    @Test
    fun `coverage within tolerance passes`() {
        val diagnostics = verifier.verify(data(80.0, 70.0), data(79.0, 69.0))
        assertFalse(diagnostics.any { it.severity == DiagnosticSeverity.FAILURE })
    }

    @Test
    fun `line or branch regression beyond tolerance fails`() {
        val diagnostics = verifier.verify(data(80.0, 70.0), data(78.9, 68.9))
        assertTrue(diagnostics.count { it.code == DiagnosticCode.COVERAGE_REGRESSION } == 2)
    }

    @Test
    fun `pending status fails`() {
        val diagnostics = verifier.verify(data(80.0, 70.0).copy(status = "pending"), data(80.0, 70.0))
        assertTrue(diagnostics.any { it.code == DiagnosticCode.TEST_QUALITY_STATUS_PENDING })
    }

    @Test
    fun `undocumented exclusion fails`() {
        val current = data(80.0, 70.0).copy(
            exclusions = listOf(CoverageExclusion("**/generated/**", "new"))
        )
        assertTrue(verifier.verify(data(80.0, 70.0), current).any {
            it.code == DiagnosticCode.COVERAGE_EXCLUSION_UNDOCUMENTED
        })
    }

    private fun data(line: Double, branch: Double): CoverageData {
        val module = ModuleCoverage(
            module = ":core",
            lineCoverage = line,
            branchCoverage = branch,
            linesCovered = 8,
            linesMissed = 2,
            linesTotal = 10,
            branchesCovered = 7,
            branchesMissed = 3,
            branchesTotal = 10
        )
        return CoverageData(
            status = "measured",
            byModule = mapOf(":core" to module),
            criticalModules = mapOf(":core" to module),
            exclusions = configuration.coverage.exclusions
        )
    }
}
