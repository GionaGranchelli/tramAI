package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Epic 10.1b: `config/detekt/baseline.xml` is a canonical baseline alongside
 * `config/quality/0.6.0-baseline.json`. Change-policy semantics:
 *  - a runtime PR that touches the Detekt baseline violates
 *    production-baseline-separation;
 *  - a baseline-migration PR may migrate the Detekt baseline WITHOUT also
 *    modifying the maintainability baseline;
 *  - a baseline-migration PR must still change at least one canonical baseline.
 */
class ChangePolicyDetektBaselineTest {

    private fun input(
        changeClass: String?,
        files: List<String>,
    ) = ChangePolicyInput(
        changeClass = changeClass,
        changedFiles = files,
        baseDeviationsYaml = null,
        currentDeviationsYaml = null,
    )

    @Test
    fun `detekt baseline in a runtime PR triggers production-baseline separation`() {
        val result =
            ChangePolicyEvaluator.evaluate(
                input(
                    changeClass = null,
                    files = listOf(
                        "tramai-engine/src/main/kotlin/Engine.kt",
                        "config/detekt/baseline.xml",
                    ),
                )
            )
        assertFalse(result.passed)
        assertTrue(
            result.violations.any { it.rule == "production-baseline-separation" },
            "runtime + detekt baseline change must violate production-baseline-separation",
        )
    }

    @Test
    fun `baseline-migration via detekt baseline is valid without maintainability baseline`() {
        val result =
            ChangePolicyEvaluator.evaluate(
                input(
                    changeClass = "baseline-migration",
                    files = listOf(
                        "build-logic/src/main/kotlin/dev/tramai/build/quality/VerifyStaticAnalysisTask.kt",
                        "config/detekt/baseline.xml",
                    ),
                )
            )
        assertTrue(
            result.passed,
            "a detekt baseline migration must not be required to also modify 0.6.0-baseline.json",
        )
    }

    @Test
    fun `baseline-migration with neither canonical baseline fails`() {
        val result =
            ChangePolicyEvaluator.evaluate(
                input(
                    changeClass = "baseline-migration",
                    files = listOf(
                        "build-logic/src/main/kotlin/dev/tramai/build/quality/VerifyStaticAnalysisTask.kt",
                        "config/quality/maintainability-deviations.yml",
                    ),
                )
            )
        assertFalse(result.passed)
        assertTrue(
            result.violations.any { it.message.contains("canonical baseline") },
            "baseline-migration without any canonical baseline change must fail",
        )
    }
}
