package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutationBaselineVerifierTest {
    private val configuration = TestQualityConfiguration(
        "1",
        listOf(":engine"),
        TestQualityConfiguration.CoverageConfiguration(1.0, emptyList()),
        TestQualityConfiguration.MutationConfiguration(
            1.0,
            mapOf("routing" to TestQualityConfiguration.MutationTargetFamily(listOf(":engine")))
        )
    )
    private val verifier = MutationBaselineVerifier(configuration)

    @Test
    fun `score within tolerance passes`() {
        assertFalse(verifier.verify(data(80.0), data(79.0)).any {
            it.severity == DiagnosticSeverity.FAILURE
        })
    }

    @Test
    fun `score regression fails`() {
        assertTrue(verifier.verify(data(80.0), data(78.9)).any {
            it.code == DiagnosticCode.MUTATION_REGRESSION
        })
    }

    @Test
    fun `unclassified survivor fails`() {
        val survivor = survivor().copy(classification = "unclassified", behaviourFamily = "")
        assertTrue(verifier.verify(data(80.0), data(80.0).copy(survivingMutants = listOf(survivor))).any {
            it.code == DiagnosticCode.MUTATION_SURVIVOR_UNCLASSIFIED
        })
    }

    @Test
    fun `missing test survivor requires issue or target phase`() {
        assertTrue(verifier.verify(
            data(80.0),
            data(80.0).copy(survivingMutants = listOf(survivor().copy(status = "NO_COVERAGE")))
        ).any { it.code == DiagnosticCode.MUTATION_MISSING_TEST_UNTRACKED })
    }

    private fun data(score: Double) = MutationData(
        status = "measured",
        totalMutants = 10,
        killedMutants = 8,
        survivedMutants = 2,
        mutationScore = score,
        byFamily = mapOf(
            "routing" to MutationFamilyMetrics(
                "routing", listOf(":engine"), 10, 8, 2, 0, score
            )
        )
    )

    private fun survivor() = SurvivingMutant(
        module = ":engine",
        file = "Router.kt",
        line = 1,
        mutator = "BooleanMutator",
        classification = "behaviour-family",
        identity = "id",
        behaviourFamily = "routing"
    )
}
