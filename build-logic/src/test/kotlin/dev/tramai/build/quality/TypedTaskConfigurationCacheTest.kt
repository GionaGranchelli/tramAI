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
        assertTrue(
            first.output.contains("verifyChangePolicy: no changes detected"),
            "clean repository must pass: ${first.output.take(800)}",
        )
        assertTrue(first.output.contains("Configuration cache entry stored"), "first run must store cache: ${first.output.take(800)}")

        val second = runner(dir, *args).build()
        assertTrue(second.output.contains("Reusing configuration cache"), "second run must reuse cache: ${second.output.take(800)}")
    }

    @Test
    fun `verify cancellation safety reuses configuration cache`() {
        val dir = maintainabilityFixture()
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        // A scoped module with a catch-free source file; no findings either way.
        writeFile(dir, "sample/src/main/kotlin/example/Plain.kt", "package example\nclass Plain\n")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "initial fixture")
        git(dir, "remote", "add", "origin", "https://example.invalid/tramai.git")
        git(dir, "update-ref", "refs/remotes/origin/master", "HEAD")

        val args = configurationCacheArguments("verifyCancellationSafety")
        val first = runner(dir, *args).build()
        assertTrue(first.task(":verifyCancellationSafety") != null, "task must execute: ${first.output.take(800)}")
        assertTrue(first.output.contains("PASSED"), "clean repository must pass: ${first.output.take(800)}")
        assertTrue(first.output.contains("Configuration cache entry stored"), "first run must store cache: ${first.output.take(800)}")

        val second = runner(dir, *args).build()
        assertTrue(second.output.contains("Reusing configuration cache"), "second run must reuse cache: ${second.output.take(800)}")
    }

    @Test
    fun `verify cancellation safety scans declared inputs not rediscovered sources`() {
        // Authority discriminator (9.2d-b3 P1): the declared scanInputs must be
        // the execution authority. The base commit is clean. Module B (sample)
        // then receives a forbidden broad catch as an UNCOMMITTED working-tree
        // change (present on disk, absent from base). Redirecting scanInputs to
        // clean module A must pass even though B's forbidden source exists on
        // disk; redirecting to B must fail. A rediscovery-based implementation
        // would always scan B regardless of the declared input.
        val dir = maintainabilityFixture()
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        writeFile(dir, "tramai-core/src/main/kotlin/example/Good.kt", "package example\nclass Good\n")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "initial fixture")
        git(dir, "remote", "add", "origin", "https://example.invalid/tramai.git")
        git(dir, "update-ref", "refs/remotes/origin/master", "HEAD")

        // Uncommitted forbidden catch in module B's conventional source tree.
        writeFile(
            dir,
            "sample/src/main/kotlin/example/Bad.kt",
            """
            package example

            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    logError(e)
                }
            }
            """.trimIndent(),
        )

        fun configureInputs(module: String) {
            val buildScript = File(dir, "build.gradle.kts").readText()
            File(dir, "build.gradle.kts").writeText(
                buildScript +
                    """
                |tasks.named<dev.tramai.build.quality.VerifyCancellationSafetyTask>("verifyCancellationSafety") {
                |    scanInputs.setFrom(layout.projectDirectory.dir("$module/src/main/kotlin").asFileTree)
                |}
                |
                    """.trimMargin(),
            )
        }

        // Point at clean module A: must pass even though module B on disk
        // contains an uncommitted forbidden catch.
        configureInputs("tramai-core")
        val passing = runner(dir, "verifyCancellationSafety").build()
        assertTrue(passing.output.contains("PASSED"), "clean declared source must pass: ${passing.output.take(800)}")

        // Invert: point at forbidden module B: the uncommitted catch is new
        // against the clean base and must fail.
        configureInputs("sample")
        val failing = runner(dir, "verifyCancellationSafety").buildAndFail()
        assertTrue(
            failing.output.contains("PASSED").not(),
            "declared forbidden source must fail: ${failing.output.take(800)}",
        )
    }

    private fun assertConfigurationCacheReuse(
        dir: File,
        task: String,
        reportPath: String,
    ) {
        val args = configurationCacheArguments(task)
        val first = runner(dir, *args).build()
        assertTrue(first.task(task) != null, "$task must execute: ${first.output.take(800)}")
        assertTrue(first.output.contains("Configuration cache entry stored"), "first run must store cache: ${first.output.take(800)}")

        val report = File(dir, reportPath)
        assertTrue(report.isFile, "report must exist after first run: $reportPath")
        val firstContent = report.readText()
        val parsed =
            com.fasterxml.jackson.databind
                .ObjectMapper()
                .readTree(firstContent)
        assertTrue(
            parsed.isArray && parsed.size() >= 1,
            "fixture :sample resolves com.example:fake:1.0, report must contain records: $firstContent",
        )
        assertTrue(
            parsed.any { it.get("group").asText() == "com.example" && it.get("artifact").asText() == "fake" },
            "report must contain the fake module: $firstContent",
        )
        assertTrue(!firstContent.contains("resolutionFailed"), "probe must not report a swallowed failure: $firstContent")

        val second = runner(dir, *args).build()
        assertTrue(second.output.contains("Reusing configuration cache"), "second run must reuse cache: ${second.output.take(800)}")
        assertTrue(second.task(task)?.outcome == org.gradle.testkit.runner.TaskOutcome.SUCCESS, "task must succeed on warm run")
        val secondContent = File(dir, reportPath).readText()
        assertTrue(firstContent == secondContent, "cold and warm outputs must be byte-identical")
    }

    private fun configurationCacheArguments(task: String) =
        arrayOf(
            task,
            "--configuration-cache",
            "--configuration-cache-problems=fail",
        )

    private fun maintainabilityFixture(): File {
        val dir = File(tempDir, "fixture-${tempDir.listFiles()?.size ?: 0}").apply { mkdirs() }
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "typed-task-configuration-cache"
            include(":sample", ":tramai-core", ":examples:java-consumer-smoke", ":examples:kotlin-consumer-smoke")
            """.trimIndent(),
        )
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins { id("tramai.maintainability-baseline") }
            """.trimIndent(),
        )
        writeFile(
            dir,
            "sample/build.gradle.kts",
            """
            plugins { `java-library` }
            repositories { maven { url = uri(rootDir.resolve("repo")) } }
            dependencies { implementation("com.example:fake:1.0") }
            """.trimIndent(),
        )
        writeFile(dir, "tramai-core/build.gradle.kts", "plugins { `java-library` }")
        writeFile(dir, "examples/java-consumer-smoke/build.gradle.kts", "plugins { `java-library` }")
        writeFile(dir, "examples/kotlin-consumer-smoke/build.gradle.kts", "plugins { `java-library` }")
        writeFile(dir, ".gitignore", ".gradle/\nbuild/\nsample/build/\n")
        writeFile(
            dir,
            "config/quality/module-catalog.yml",
            """
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
            """.trimIndent(),
        )
        writeFile(
            dir,
            "config/quality/test-quality.yml",
            """
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
            """.trimIndent(),
        )

        // Minimal local Maven repo so :sample resolves a real external module.
        // Deliberately a file repo (not mavenCentral): the test must be
        // deterministic and offline. The jar is empty; resolution metadata is
        // what the baseline records.
        val repoModule = File(dir, "repo/com/example/fake/1.0")
        repoModule.mkdirs()
        writeFile(
            repoModule,
            "fake-1.0.pom",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>fake</artifactId>
              <version>1.0</version>
            </project>
            """.trimIndent(),
        )
        java.util.zip
            .ZipOutputStream(File(repoModule, "fake-1.0.jar").outputStream())
            .use { it.close() }
        return dir
    }

    private fun runner(
        dir: File,
        vararg args: String,
    ): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withGradleVersion("9.0.0")
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()

    private fun writeFile(
        base: File,
        relativePath: String,
        content: String,
    ) {
        val target = File(base, relativePath)
        target.parentFile.mkdirs()
        target.writeText(content)
    }

    private fun git(
        dir: File,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }
}
