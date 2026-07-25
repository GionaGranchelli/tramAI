package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class TestPerformanceVerifierTest {
    private val configuration = TestQualityConfiguration(
        "1",
        listOf(":core"),
        TestQualityConfiguration.CoverageConfiguration(1.0, emptyList()),
        TestQualityConfiguration.MutationConfiguration(
            1.0,
            mapOf("core" to TestQualityConfiguration.MutationTargetFamily(listOf(":core")))
        )
    )
    private val verifier = TestPerformanceVerifier(configuration)

    @Test
    fun `module regression over 25 percent warns`() {
        assertTrue(verifier.verify(data(100, 10), data(126, 10)).any {
            it.code == DiagnosticCode.TEST_PERFORMANCE_REGRESSION &&
                it.severity == DiagnosticSeverity.WARNING
        })
    }

    @Test
    fun `critical test regression over 50 percent warns`() {
        assertTrue(verifier.verify(data(100, 10), data(100, 16)).any {
            it.code == DiagnosticCode.CRITICAL_TEST_REGRESSION
        })
    }

    @Test
    fun `newly skipped critical test fails`() {
        assertTrue(verifier.verify(data(100, 10), data(100, 10, skipped = true)).any {
            it.code == DiagnosticCode.CRITICAL_TEST_NEWLY_SKIPPED &&
                it.severity == DiagnosticSeverity.FAILURE
        })
    }

    @Test
    fun `missing expected module fails`() {
        assertTrue(verifier.verify(data(100, 10), TestPerformanceData(status = "measured")).any {
            it.code == DiagnosticCode.TEST_REPORT_MISSING
        })
    }

    private fun data(moduleDuration: Long, testDuration: Long, skipped: Boolean = false) =
        TestPerformanceData(
            status = "measured",
            byModule = mapOf(
                ":core" to ModuleTestPerformance(":core", moduleDuration, moduleDuration, 1, 0, 0)
            ),
            slowestTests = listOf(
                TestTiming(":core", "CoreTest", "works", testDuration, skipped = skipped)
            ),
            allTests = listOf(
                TestTiming(":core", "CoreTest", "works", testDuration, skipped = skipped)
            ),
            byIdentity = mapOf(
                "85ca6e7f35613de78bab37e3a6fc735ca372a508cb41ec9f0a31d0deffd886e4" to
                    TestTiming(":core", "CoreTest", "works", testDuration, skipped = skipped)
            )
        )
}
