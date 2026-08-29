package dev.tramai.build.quality

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TestKit proof for the Epic 8.3d PR 2 aggregate-gate wiring (P0-J) and the
 * configuration-cache discipline of [VerifyRuntimeNondeterminismTask].
 */
class RuntimeNondeterminismGateTest {

    @TempDir
    lateinit var tempDir: File

    private fun fixture(
        allowlistContent: String,
        sourceContent: String = "fun f() { UUID.randomUUID() }",
        writeSource: Boolean = true,
        extraBuildScript: String = ""
    ): File {
        val dir = File(tempDir, "fixture-${tempDir.listFiles()?.size ?: 0}").apply { mkdirs() }
        writeFile(dir, "settings.gradle.kts", """
            rootProject.name = "runtime-nondeterminism-gate"
            include(":sample")
        """.trimIndent())
        writeFile(dir, "build.gradle.kts", """
            plugins { id("tramai.maintainability-baseline") }
            $extraBuildScript
        """.trimIndent())
        writeFile(dir, "sample/build.gradle.kts", "plugins { `java-library` }")
        if (writeSource) {
            writeFile(dir, "sample/src/main/kotlin/sample/App.kt", sourceContent)
        }
        writeFile(dir, "config/quality/module-catalog.yml", """
            schemaVersion: "3"
            dependencyPolicies:
              testing:
                allowedLayers: [testing-support]
            entryDefaults:
              internal: &internal
                layer: "testing-support"
                maturity: "internal"
                publishability: "internal"
                apiStability: "internal"
                visibility: "internal"
                owner: "testing"
                dependencyPolicy: "testing"
                releaseInclusion: "internal_only"
                rationale: "Provides a TestKit fixture module."
            modules:
              - path: ":sample"
                <<: *internal
        """.trimIndent())
        writeFile(dir, "config/quality/test-quality.yml", """
            schemaVersion: "1"
            criticalModules: [":sample"]
            coverage:
              regressionTolerancePercentagePoints: 1.0
              exclusions: []
            mutation:
              regressionTolerancePercentagePoints: 1.0
              targetFamilies:
                sample:
                  modules: [":sample"]
                  targetClasses: ["example.*"]
                  targetTests: ["example.*"]
              excludedClasses: []
            performance:
              regressionToleranceMillis: 100
        """.trimIndent())
        writeFile(dir, "config/quality/runtime-nondeterminism.yml", allowlistContent)
        writeFile(dir, ".gitignore", ".gradle/\nbuild/\nsample/build/\n")
        return dir
    }

    private fun runner(dir: File, vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*args)
            .withPluginClasspath()

    private fun writeFile(dir: File, path: String, content: String) {
        val f = File(dir, path)
        f.parentFile.mkdirs()
        f.writeText(content)
    }

    private val emptyAllowlist = """
        schemaVersion: '1'
        entries: []
    """.trimIndent()

    private val matchingAllowlist = """
        schemaVersion: '1'
        entries:
          - module: sample
            file: sample/src/main/kotlin/sample/App.kt
            source: UUID.randomUUID()
            category: identity
            scannerClassification: correlation_identity
            disposition: CAPABILITY_AUTHORITY
            authority: TestAuthority
            occurrences: 1
            rationale: Test fixture authority.
    """.trimIndent()

    @Test
    fun `P0-J verifyMaintainabilityBaseline task graph includes verifyRuntimeNondeterminism`() {
        val dir = fixture(emptyAllowlist)
        val result = runner(dir, "verifyMaintainabilityBaseline", "--dry-run").build()
        assertTrue(
            result.output.contains(":verifyRuntimeNondeterminism"),
            "verifyMaintainabilityBaseline must depend on verifyRuntimeNondeterminism: ${result.output.take(1200)}"
        )
    }

    @Test
    fun `P0-A at task level - unclassified finding fails the build`() {
        val dir = fixture(emptyAllowlist)
        val result = runner(dir, "verifyRuntimeNondeterminism").buildAndFail()
        assertTrue(
            result.output.contains("Nondeterminism authority contract verification FAILED"),
            "verifier must fail the build: ${result.output.take(1200)}"
        )
        assertTrue(
            result.output.contains("UNCLASSIFIED_FINDING"),
            "unclassified finding must be reported: ${result.output.take(1200)}"
        )
    }

    @Test
    fun `exact allowlist passes and report is written`() {
        val dir = fixture(matchingAllowlist)
        val result = runner(dir, "verifyRuntimeNondeterminism").build()
        assertTrue(result.task(":verifyRuntimeNondeterminism") != null)
        val report = File(dir, "build/reports/maintainability/runtime-nondeterminism-verification.json")
        assertTrue(report.isFile, "report must exist")
        val parsed = com.fasterxml.jackson.databind.ObjectMapper().readTree(report)
        assertTrue(parsed.get("passed").asBoolean(), "report must say passed: ${report.readText()}")
        assertFalse(parsed.get("unclassifiedCount").asInt() > 0, "no unclassified findings expected")
    }

    @Test
    fun `declared allowlist file is the file actually consumed`() {
        // Default allowlist would FAIL (empty) — the fixture overrides the task's
        // allowlistFile to an alternate file with a matching entry. Success proves
        // the declared input, not the hardcoded default path, is what is parsed.
        val dir = fixture(
            allowlistContent = emptyAllowlist,
            extraBuildScript = """
                tasks.named<dev.tramai.build.quality.VerifyRuntimeNondeterminismTask>("verifyRuntimeNondeterminism") {
                    allowlistFile.set(layout.projectDirectory.file("config/quality/alt-runtime-nondeterminism.yml"))
                }
            """.trimIndent()
        )
        writeFile(dir, "config/quality/alt-runtime-nondeterminism.yml", matchingAllowlist)
        val result = runner(dir, "verifyRuntimeNondeterminism").build()
        assertTrue(
            result.task(":verifyRuntimeNondeterminism") != null,
            "task must execute with the alternate allowlist: ${result.output.take(800)}"
        )
    }

    @Test
    fun `source root created after first run is tracked and fails on new finding`() {
        // First run: module has NO src/main dir at all; empty allowlist passes.
        // Configuration cache is used so the second run REUSES the stored
        // configuration — the exact scenario where a previously-absent root
        // must still be visible as an input change (otherwise the task would
        // be up-to-date and the new finding silently missed).
        val args = arrayOf(
            "verifyRuntimeNondeterminism",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
        )
        val dir = fixture(allowlistContent = emptyAllowlist, writeSource = false)
        runner(dir, *args).build()

        // Introduce a previously-absent source root with an unclassified finding.
        writeFile(
            dir,
            "sample/src/main/kotlin/sample/New.kt",
            "fun g() { System.nanoTime() }"
        )
        val second = runner(dir, *args).buildAndFail()
        assertTrue(
            second.output.contains("UNCLASSIFIED_FINDING"),
            "newly-created source root must be an input change that reruns and fails: ${second.output.take(1200)}"
        )
    }

    @Test
    fun `verifyRuntimeNondeterminism reuses configuration cache`() {
        val dir = fixture(matchingAllowlist)
        val args = arrayOf(
            "verifyRuntimeNondeterminism",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
        )
        val first = runner(dir, *args).build()
        assertTrue(first.task(":verifyRuntimeNondeterminism") != null, "task must execute: ${first.output.take(800)}")
        assertTrue(first.output.contains("Configuration cache entry stored"), "first run must store cache: ${first.output.take(800)}")

        val second = runner(dir, *args).build()
        assertTrue(second.output.contains("Reusing configuration cache"), "second run must reuse cache: ${second.output.take(800)}")
        val warmOutcome = second.task(":verifyRuntimeNondeterminism")?.outcome
        assertTrue(
            warmOutcome == org.gradle.testkit.runner.TaskOutcome.SUCCESS || warmOutcome == org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE,
            "task must succeed (or be up-to-date) on warm run: $warmOutcome"
        )
    }
}
