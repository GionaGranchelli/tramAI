package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutationBaselineVerifierTest {
    private val configuration =
        TestQualityConfiguration(
            "1",
            listOf(":engine"),
            TestQualityConfiguration.CoverageConfiguration(1.0, emptyList()),
            TestQualityConfiguration.MutationConfiguration(
                1.0,
                mapOf("routing" to TestQualityConfiguration.MutationTargetFamily(listOf(":engine"))),
            ),
        )
    private val verifier = MutationBaselineVerifier(configuration)

    @Test
    fun `score within tolerance passes`() {
        assertFalse(
            verifier.verify(data(80.0), data(79.0)).any {
                it.severity == DiagnosticSeverity.FAILURE
            },
        )
    }

    @Test
    fun `score regression fails`() {
        assertTrue(
            verifier.verify(data(80.0), data(78.9)).any {
                it.code == DiagnosticCode.MUTATION_REGRESSION
            },
        )
    }

    @Test
    fun `unclassified survivor fails`() {
        val survivor = survivor().copy(classification = "unclassified", behaviourFamily = "routing")
        assertTrue(
            verifier.verify(data(80.0), data(80.0).copy(survivingMutants = listOf(survivor))).any {
                it.code == DiagnosticCode.MUTATION_SURVIVOR_UNCLASSIFIED
            },
        )
    }

    @Test
    fun `missing test survivor requires issue or target phase`() {
        assertTrue(
            verifier
                .verify(
                    data(80.0),
                    data(80.0).copy(survivingMutants = listOf(survivor().copy(status = "NO_COVERAGE", classification = "unclassified"))),
                ).any { it.code == DiagnosticCode.MUTATION_MISSING_TEST_UNTRACKED },
        )
    }

    @Test
    fun `classified survivor with valid classification passes`(
        @TempDir tempDir: File,
    ) {
        // Write a mutation-classifications.yml with a valid entry
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText(
            """
            schemaVersion: "1"
            classifications:
              - id: "known-id"
                classification: "equivalent-mutant"
                reason: "Test equivalent mutant"
            """.trimIndent(),
        )

        val verifierWithRoot = MutationBaselineVerifier(configuration, tempDir)
        val survivor = survivor().copy(identity = "known-id", behaviourFamily = "routing")
        val diagnostics = verifierWithRoot.verify(data(80.0), data(80.0).copy(survivingMutants = listOf(survivor)))
        assertFalse(diagnostics.any { it.severity == DiagnosticSeverity.FAILURE })
    }

    @Test
    fun `classified survivor missing-test without issue or target phase fails`(
        @TempDir tempDir: File,
    ) {
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText(
            """
            schemaVersion: "1"
            classifications:
              - id: "bad-id"
                classification: "missing-test"
                reason: "Test missing test"
            """.trimIndent(),
        )

        val verifierWithRoot = MutationBaselineVerifier(configuration, tempDir)
        val survivor = survivor().copy(identity = "bad-id", behaviourFamily = "routing")
        val diagnostics = verifierWithRoot.verify(data(80.0), data(80.0).copy(survivingMutants = listOf(survivor)))
        assertTrue(diagnostics.any { it.code == DiagnosticCode.MUTATION_MISSING_TEST_UNTRACKED })
    }

    @Test
    fun `classified survivor missing-test with issue passes`(
        @TempDir tempDir: File,
    ) {
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText(
            """
            schemaVersion: "1"
            classifications:
              - id: "issue-id"
                classification: "missing-test"
                reason: "Test missing test with issue"
                issue: "https://github.com/example/repo/issues/1"
            """.trimIndent(),
        )

        val verifierWithRoot = MutationBaselineVerifier(configuration, tempDir)
        val survivor = survivor().copy(identity = "issue-id", behaviourFamily = "routing")
        val diagnostics = verifierWithRoot.verify(data(80.0), data(80.0).copy(survivingMutants = listOf(survivor)))
        assertFalse(diagnostics.any { it.severity == DiagnosticSeverity.FAILURE })
    }

    @Test
    fun `classified survivor missing-test with target phase passes`(
        @TempDir tempDir: File,
    ) {
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText(
            """
            schemaVersion: "1"
            classifications:
              - id: "phase-id"
                classification: "missing-test"
                reason: "Test missing test with target phase"
                targetPhase: "v0.7.0"
            """.trimIndent(),
        )

        val verifierWithRoot = MutationBaselineVerifier(configuration, tempDir)
        val survivor = survivor().copy(identity = "phase-id", behaviourFamily = "routing")
        val diagnostics = verifierWithRoot.verify(data(80.0), data(80.0).copy(survivingMutants = listOf(survivor)))
        assertFalse(diagnostics.any { it.severity == DiagnosticSeverity.FAILURE })
    }

    @Test
    fun `survivor with behaviour family but no classification entry fails`(
        @TempDir tempDir: File,
    ) {
        // Empty classifications file
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText(
            """
            schemaVersion: "1"
            classifications: []
            """.trimIndent(),
        )

        val verifierWithRoot = MutationBaselineVerifier(configuration, tempDir)
        val survivor =
            survivor().copy(
                identity = "unknown-id",
                behaviourFamily = "routing",
                classification = "behaviour-family",
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
    fun `mutation classification loader validates allowed values`(
        @TempDir tempDir: File,
    ) {
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText(
            """
            schemaVersion: "1"
            classifications:
              - id: "test-id"
                classification: "invalid-value"
                reason: "Test invalid"
            """.trimIndent(),
        )

        try {
            MutationClassificationLoader.load(tempDir)
            throw AssertionError("Should have thrown")
        } catch (e: org.gradle.api.GradleException) {
            assertTrue(e.message!!.contains("not allowed"))
        }
    }

    @Test
    fun `mutation classification loader accepts missing-test without issue or targetPhase`(
        @TempDir tempDir: File,
    ) {
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText(
            """
            schemaVersion: "1"
            classifications:
              - id: "test-id"
                classification: "missing-test"
                reason: "Test missing test"
            """.trimIndent(),
        )

        // Loader now accepts this; validation happens in MutationBaselineVerifier
        val result = MutationClassificationLoader.load(tempDir)
        kotlin.test.assertEquals(1, result.classifications.size)
        kotlin.test.assertEquals("missing-test", result.classifications[0].classification)
    }

    @Test
    fun `mutation classification loader rejects unknown classification`(
        @TempDir tempDir: File,
    ) {
        val classificationsFile = File(tempDir, "config/quality/mutation-classifications.yml")
        classificationsFile.parentFile.mkdirs()
        classificationsFile.writeText(
            """
            schemaVersion: "1"
            classifications:
              - id: "bad"
                classification: "unknown-type"
                reason: "Test"
            """.trimIndent(),
        )

        try {
            MutationClassificationLoader.load(tempDir)
            throw AssertionError("Should have thrown")
        } catch (e: org.gradle.api.GradleException) {
            assertTrue(e.message!!.contains("not allowed"))
        }
    }

    @Test
    fun `cross-family deduplication preserves per-family metrics while overall counts each mutant once`() {
        // Two families with overlapping mutants (same identity "overlap-id" appears in both)
        fun record(
            family: String,
            status: String,
            method: String = "route",
            mutator: String = "BooleanMutator",
            line: Int = 1,
            sourceFile: String = "Router.kt",
            identity: String,
        ) = MutationRecord(
            module = ":engine",
            family = family,
            status = status,
            sourceFile = sourceFile,
            className = if (method == "approve") "Approver" else "Router",
            method = method,
            methodDescription = "()V",
            line = line,
            mutator = mutator,
            description = "",
            identity = identity,
        )
        val mutant1 = record(family = "routing", status = "KILLED", identity = "overlap-id")
        val mutant2 =
            record(
                family = "routing",
                status = "SURVIVED",
                mutator = "NegateConditionalsMutator",
                line = 2,
                identity = "unique-routing-id",
            )
        // Same identity as mutant1 — cross-family overlap
        val mutant3 = record(family = "approval", status = "KILLED", identity = "overlap-id")
        val mutant4 =
            record(
                family = "approval",
                status = "KILLED",
                method = "approve",
                line = 5,
                sourceFile = "Approver.kt",
                identity = "unique-approval-id",
            )

        val routingReport = ParsedMutationReport(module = ":engine", family = "routing", mutants = listOf(mutant1, mutant2))
        val approvalReport = ParsedMutationReport(module = ":engine", family = "approval", mutants = listOf(mutant3, mutant4))

        val result = MutationBaselineVerifier.aggregate(reports = listOf(routingReport, approvalReport))

        // Assert both families have their metrics preserved (raw data per family)
        val routingFamily = result.byFamily["routing"]
        kotlin.test.assertNotNull(routingFamily)
        kotlin.test.assertEquals(2, routingFamily.totalMutants, "routing family should have 2 mutants (raw)")
        kotlin.test.assertEquals(1, routingFamily.killedMutants)
        kotlin.test.assertEquals(1, routingFamily.survivedMutants)

        val approvalFamily = result.byFamily["approval"]
        kotlin.test.assertNotNull(approvalFamily)
        kotlin.test.assertEquals(2, approvalFamily.totalMutants, "approval family should have 2 mutants (raw)")
        kotlin.test.assertEquals(2, approvalFamily.killedMutants)
        kotlin.test.assertEquals(0, approvalFamily.survivedMutants)

        // Assert overall totals count overlapping mutant only once (deduplicated)
        kotlin.test.assertEquals(3, result.totalMutants, "overall total should count 3 unique mutants, not 4")
        kotlin.test.assertEquals(2, result.killedMutants, "overall killed should count overlap-id killed only once")
        kotlin.test.assertEquals(1, result.survivedMutants, "overall survived should be 1")

        // Assert byModule counts the deduplicated total
        val engineMetrics = result.byModule[":engine"]
        kotlin.test.assertNotNull(engineMetrics)
        kotlin.test.assertEquals(3, engineMetrics.generated, "byModule should count 3 unique mutants, not 4")
    }

    @Test
    fun `order-independent dedup picks highest precedence status regardless of report order`() {
        // Same identity appears in two reports with conflicting statuses.
        // Precedence: NO_COVERAGE > SURVIVED > TIMED_OUT > KILLED

        val identity = "shared-mutant-id"
        val sharedSource = "Shared.kt"

        fun record(
            status: String,
            family: String,
        ) = MutationRecord(
            module = ":core",
            family = family,
            status = status,
            sourceFile = sharedSource,
            className = "Shared",
            method = "calc",
            methodDescription = "()V",
            line = 10,
            mutator = "VoidMethodCallMutator",
            description = "",
            identity = identity,
        )

        // Test KILLED vs SURVIVED → SURVIVED wins
        val killed = record("KILLED", "policy")
        val survived = record("SURVIVED", "approval")
        val reportsOrderA =
            listOf(
                ParsedMutationReport(":core", "policy", listOf(killed)),
                ParsedMutationReport(":core", "approval", listOf(survived)),
            )
        val reportsOrderB =
            listOf(
                ParsedMutationReport(":core", "approval", listOf(survived)),
                ParsedMutationReport(":core", "policy", listOf(killed)),
            )

        val resultA = MutationBaselineVerifier.aggregate(reportsOrderA)
        val resultB = MutationBaselineVerifier.aggregate(reportsOrderB)

        kotlin.test.assertEquals(1, resultA.totalMutants, "should still deduplicate to 1 mutant")
        kotlin.test.assertEquals(1, resultB.totalMutants, "should still deduplicate to 1 mutant")
        kotlin.test.assertEquals(0, resultA.killedMutants, "SURVIVED wins over KILLED")
        kotlin.test.assertEquals(0, resultB.killedMutants, "SURVIVED wins over KILLED — order-independent")
        kotlin.test.assertEquals(1, resultA.survivedMutants, "SURVIVED should be counted")
        kotlin.test.assertEquals(1, resultB.survivedMutants, "SURVIVED should be counted — order-independent")

        // Test NO_COVERAGE vs SURVIVED → NO_COVERAGE wins
        val noCoverage = record("NO_COVERAGE", "evidence")
        val reportsNoCov =
            listOf(
                ParsedMutationReport(":core", "approval", listOf(survived)),
                ParsedMutationReport(":core", "evidence", listOf(noCoverage)),
            )
        val resultNoCov = MutationBaselineVerifier.aggregate(reportsNoCov)
        kotlin.test.assertEquals(1, resultNoCov.totalMutants, "should still deduplicate to 1 mutant")
        kotlin.test.assertEquals(0, resultNoCov.killedMutants, "NO_COVERAGE wins over SURVIVED")
        kotlin.test.assertEquals(0, resultNoCov.survivedMutants, "mutant with NO_COVERAGE is not in survivors list")

        // Test TIMED_OUT vs KILLED → TIMED_OUT wins
        val timedOut = record("TIMED_OUT", "routing")
        val reportsTimedOut =
            listOf(
                ParsedMutationReport(":core", "routing", listOf(timedOut)),
                ParsedMutationReport(":core", "policy", listOf(killed)),
            )
        val resultTimedOut = MutationBaselineVerifier.aggregate(reportsTimedOut)
        kotlin.test.assertEquals(1, resultTimedOut.totalMutants, "should still deduplicate to 1 mutant")
        kotlin.test.assertEquals(0, resultTimedOut.killedMutants, "TIMED_OUT wins over KILLED")

        // Test all four statuses together → NO_COVERAGE wins
        val allStatuses =
            listOf(
                ParsedMutationReport(":core", "policy", listOf(record("KILLED", "policy"))),
                ParsedMutationReport(":core", "approval", listOf(record("SURVIVED", "approval"))),
                ParsedMutationReport(":core", "routing", listOf(record("TIMED_OUT", "routing"))),
                ParsedMutationReport(":core", "evidence", listOf(record("NO_COVERAGE", "evidence"))),
            )
        val resultAll = MutationBaselineVerifier.aggregate(allStatuses)
        kotlin.test.assertEquals(1, resultAll.totalMutants, "all statuses should deduplicate to 1 mutant")
        kotlin.test.assertEquals(0, resultAll.killedMutants, "NO_COVERAGE wins over all")
        kotlin.test.assertEquals(0, resultAll.survivedMutants, "NO_COVERAGE mutant is not in survivors")
    }

    private fun data(score: Double) =
        MutationData(
            status = "measured",
            totalMutants = 10,
            killedMutants = 8,
            survivedMutants = 2,
            mutationScore = score,
            byFamily =
                mapOf(
                    "routing" to
                        MutationFamilyMetrics(
                            "routing",
                            listOf(":engine"),
                            10,
                            8,
                            2,
                            0,
                            score,
                        ),
                ),
        )

    private fun survivor() =
        SurvivingMutant(
            module = ":engine",
            file = "Router.kt",
            line = 1,
            mutator = "BooleanMutator",
            classification = "behaviour-family",
            identity = "id",
            behaviourFamily = "routing",
        )
}
