package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

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
        val survivor = survivor().copy(classification = "unclassified", behaviourFamily = "routing")
        assertTrue(verifier.verify(data(80.0), data(80.0).copy(survivingMutants = listOf(survivor))).any {
            it.code == DiagnosticCode.MUTATION_SURVIVOR_UNCLASSIFIED
        })
    }

    @Test
    fun `missing test survivor requires issue or target phase`() {
        assertTrue(verifier.verify(
            data(80.0),
            data(80.0).copy(survivingMutants = listOf(survivor().copy(status = "NO_COVERAGE", classification = "unclassified")))
        ).any { it.code == DiagnosticCode.MUTATION_MISSING_TEST_UNTRACKED })
    }

    @Test
    fun `classified survivor with valid classification passes`(@TempDir tempDir: File) {
        // Write a mutation-classifications.yml with a valid entry
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText("""
            schemaVersion: "1"
            classifications:
              - id: "known-id"
                classification: "equivalent-mutant"
                reason: "Test equivalent mutant"
        """.trimIndent())

        val verifierWithRoot = MutationBaselineVerifier(configuration, tempDir)
        val survivor = survivor().copy(identity = "known-id", behaviourFamily = "routing")
        val diagnostics = verifierWithRoot.verify(data(80.0), data(80.0).copy(survivingMutants = listOf(survivor)))
        assertFalse(diagnostics.any { it.severity == DiagnosticSeverity.FAILURE })
    }

    @Test
    fun `classified survivor missing-test without issue or target phase fails`(@TempDir tempDir: File) {
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText("""
            schemaVersion: "1"
            classifications:
              - id: "bad-id"
                classification: "missing-test"
                reason: "Test missing test"
        """.trimIndent())

        val verifierWithRoot = MutationBaselineVerifier(configuration, tempDir)
        val survivor = survivor().copy(identity = "bad-id", behaviourFamily = "routing")
        val diagnostics = verifierWithRoot.verify(data(80.0), data(80.0).copy(survivingMutants = listOf(survivor)))
        assertTrue(diagnostics.any { it.code == DiagnosticCode.MUTATION_MISSING_TEST_UNTRACKED })
    }

    @Test
    fun `classified survivor missing-test with issue passes`(@TempDir tempDir: File) {
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText("""
            schemaVersion: "1"
            classifications:
              - id: "issue-id"
                classification: "missing-test"
                reason: "Test missing test with issue"
                issue: "https://github.com/example/repo/issues/1"
        """.trimIndent())

        val verifierWithRoot = MutationBaselineVerifier(configuration, tempDir)
        val survivor = survivor().copy(identity = "issue-id", behaviourFamily = "routing")
        val diagnostics = verifierWithRoot.verify(data(80.0), data(80.0).copy(survivingMutants = listOf(survivor)))
        assertFalse(diagnostics.any { it.severity == DiagnosticSeverity.FAILURE })
    }

    @Test
    fun `classified survivor missing-test with target phase passes`(@TempDir tempDir: File) {
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText("""
            schemaVersion: "1"
            classifications:
              - id: "phase-id"
                classification: "missing-test"
                reason: "Test missing test with target phase"
                targetPhase: "v0.7.0"
        """.trimIndent())

        val verifierWithRoot = MutationBaselineVerifier(configuration, tempDir)
        val survivor = survivor().copy(identity = "phase-id", behaviourFamily = "routing")
        val diagnostics = verifierWithRoot.verify(data(80.0), data(80.0).copy(survivingMutants = listOf(survivor)))
        assertFalse(diagnostics.any { it.severity == DiagnosticSeverity.FAILURE })
    }

    @Test
    fun `survivor with behaviour family but no classification entry fails`(@TempDir tempDir: File) {
        // Empty classifications file
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText("""
            schemaVersion: "1"
            classifications: []
        """.trimIndent())

        val verifierWithRoot = MutationBaselineVerifier(configuration, tempDir)
        val survivor = survivor().copy(
            identity = "unknown-id",
            behaviourFamily = "routing",
            classification = "behaviour-family"
        )
        val diagnostics = verifierWithRoot.verify(data(80.0), data(80.0).copy(survivingMutants = listOf(survivor)))
        assertTrue(diagnostics.any { it.code == DiagnosticCode.MUTATION_SURVIVOR_UNCLASSIFIED })
    }

    @Test
    fun `survivor without behaviourFamily bypasses classification check`() {
        val survivor = survivor().copy(behaviourFamily = "", classification = "unclassified")
        val diagnostics = verifier.verify(data(80.0), data(80.0).copy(survivingMutants = listOf(survivor)))
        // Old survivor without family should NOT trigger the new unclassified check
        assertFalse(diagnostics.any { it.code == DiagnosticCode.MUTATION_SURVIVOR_UNCLASSIFIED })
    }

    @Test
    fun `mutation classification loader validates allowed values`(@TempDir tempDir: File) {
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText("""
            schemaVersion: "1"
            classifications:
              - id: "test-id"
                classification: "invalid-value"
                reason: "Test invalid"
        """.trimIndent())

        try {
            MutationClassificationLoader.load(tempDir)
            throw AssertionError("Should have thrown")
        } catch (e: org.gradle.api.GradleException) {
            assertTrue(e.message!!.contains("not allowed"))
        }
    }

    @Test
    fun `mutation classification loader accepts missing-test without issue or targetPhase`(@TempDir tempDir: File) {
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText("""
            schemaVersion: "1"
            classifications:
              - id: "test-id"
                classification: "missing-test"
                reason: "Test missing test"
        """.trimIndent())

        // Loader now accepts this; validation happens in MutationBaselineVerifier
        val result = MutationClassificationLoader.load(tempDir)
        kotlin.test.assertEquals(1, result.classifications.size)
        kotlin.test.assertEquals("missing-test", result.classifications[0].classification)
    }

    @Test
    fun `mutation classification loader rejects unknown classification`(@TempDir tempDir: File) {
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText("""
            schemaVersion: "1"
            classifications:
              - id: "bad"
                classification: "unknown-type"
                reason: "Test"
        """.trimIndent())

        try {
            MutationClassificationLoader.load(tempDir)
            throw AssertionError("Should have thrown")
        } catch (e: org.gradle.api.GradleException) {
            assertTrue(e.message!!.contains("not allowed"))
        }
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
