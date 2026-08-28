package dev.tramai.build.quality

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

/**
 * TestKit proof that the typed maintainability tasks reuse Gradle's
 * configuration cache without accessing Task.project during execution.
 */
class TypedTaskConfigurationCacheTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `generate resolved dependency baseline reuses configuration cache`() {
        assertConfigurationCacheReuse(
            maintainabilityFixture(),
            ":sample:generateResolvedDependencyBaseline",
            "sample/build/reports/maintainability/resolved-dependencies.json",
        )
    }

    @Test
    fun `architecture dependency probe reuses configuration cache`() {
        assertConfigurationCacheReuse(
            maintainabilityFixture(),
            ":sample:architectureDependencyProbe",
            "sample/build/reports/maintainability/architecture-dependencies.json",
        )
    }

    @Test
    fun `verify change policy reuses configuration cache on clean repository`() {
        val dir = maintainabilityFixture()
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "initial fixture")
        git(dir, "remote", "add", "origin", "https://example.invalid/tramai.git")
        git(dir, "update-ref", "refs/remotes/origin/master", "HEAD")

        val args = configurationCacheArguments("verifyChangePolicy")
        val first = runner(dir, *args).build()
        assertTrue(first.task(":verifyChangePolicy") != null, "task must execute: ${first.output.take(800)}")
        assertTrue(first.output.contains("verifyChangePolicy: no changes detected"), "clean repository must pass: ${first.output.take(800)}")
        assertTrue(first.output.contains("Configuration cache entry stored"), "first run must store cache: ${first.output.take(800)}")

        val second = runner(dir, *args).build()
        assertTrue(second.output.contains("Reusing configuration cache"), "second run must reuse cache: ${second.output.take(800)}")
    }

    private fun assertConfigurationCacheReuse(dir: File, task: String, reportPath: String) {
        val args = configurationCacheArguments(task)
        val first = runner(dir, *args).build()
        assertTrue(first.task(task) != null, "$task must execute: ${first.output.take(800)}")
        assertTrue(first.output.contains("Configuration cache entry stored"), "first run must store cache: ${first.output.take(800)}")

        val report = File(dir, reportPath)
        assertTrue(report.isFile, "report must exist after first run: $reportPath")
        val firstContent = report.readText()
        val parsed = com.fasterxml.jackson.databind.ObjectMapper().readTree(firstContent)
        assertTrue(parsed.isArray && parsed.size() == 0, "fixture has no external deps, report must be empty array: $firstContent")
        assertTrue(!firstContent.contains("resolutionFailed"), "probe must not report a swallowed failure: $firstContent")

        val second = runner(dir, *args).build()
        assertTrue(second.output.contains("Reusing configuration cache"), "second run must reuse cache: ${second.output.take(800)}")
        assertTrue(second.task(task)?.outcome == org.gradle.testkit.runner.TaskOutcome.SUCCESS, "task must succeed on warm run")
        val secondContent = File(dir, reportPath).readText()
        assertTrue(firstContent == secondContent, "cold and warm outputs must be byte-identical")
    }

    private fun configurationCacheArguments(task: String) = arrayOf(
        task,
        "--configuration-cache",
        "--configuration-cache-problems=fail",
    )

    private fun maintainabilityFixture(): File {
        val dir = File(tempDir, "fixture-${tempDir.listFiles()?.size ?: 0}").apply { mkdirs() }
        writeFile(dir, "settings.gradle.kts", """
            rootProject.name = "typed-task-configuration-cache"
            include(":sample", ":tramai-core", ":examples:java-consumer-smoke", ":examples:kotlin-consumer-smoke")
        """.trimIndent())
        writeFile(dir, "build.gradle.kts", """
            plugins { id("tramai.maintainability-baseline") }
        """.trimIndent())
        writeFile(dir, "sample/build.gradle.kts", "plugins { `java-library` }")
        writeFile(dir, "tramai-core/build.gradle.kts", "plugins { `java-library` }")
        writeFile(dir, "examples/java-consumer-smoke/build.gradle.kts", "plugins { `java-library` }")
        writeFile(dir, "examples/kotlin-consumer-smoke/build.gradle.kts", "plugins { `java-library` }")
        writeFile(dir, ".gitignore", ".gradle/\nbuild/\nsample/build/\n")
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
              - path: ":tramai-core"
                <<: *internal
              - path: ":examples:java-consumer-smoke"
                <<: *internal
              - path: ":examples:kotlin-consumer-smoke"
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
        """.trimIndent())
        return dir
    }

    private fun runner(dir: File, vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withGradleVersion("9.0.0")
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()

    private fun writeFile(base: File, relativePath: String, content: String) {
        val target = File(base, relativePath)
        target.parentFile.mkdirs()
        target.writeText(content)
    }

    private fun git(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }
}
