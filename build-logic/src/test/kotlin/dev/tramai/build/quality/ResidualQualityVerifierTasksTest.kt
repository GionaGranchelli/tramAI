package dev.tramai.build.quality

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Discriminating TestKit tests for the 9.2d-a3a residual surgical CC-hygiene
 * typed tasks (verifyModuleMatrixDrift, verifyJUnitTestSignatures, and the two
 * consumer-smoke compile tasks).
 *
 * Standing rule (user-mandated): NO empty fixture may serve as the successful
 * oracle when the verifier must discover real content. Fixtures copy REAL repo
 * files via git ls-files; positives assert SUCCESS on fresh dirs; fail-closed
 * negatives mutate exactly one real file and assert the exact diagnostic.
 */
class ResidualQualityVerifierTasksTest {
    @TempDir
    lateinit var tempDir: File

    private val repoRoot: File by lazy {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, ".git").isDirectory) {
            dir = dir.parentFile ?: error("repo root not found from ${System.getProperty("user.dir")}")
        }
        dir
    }

    private fun copyFromRepo(
        dir: File,
        vararg relativePaths: String,
    ) {
        val git =
            ProcessBuilder("git", "-C", repoRoot.absolutePath, "ls-files", *relativePaths)
                .redirectErrorStream(true)
                .start()
        val listing = git.inputStream.bufferedReader().readText()
        check(git.waitFor() == 0) { "git ls-files failed: $listing" }
        listing.lineSequence().filter { it.isNotBlank() }.forEach { rel ->
            val src = File(repoRoot, rel)
            val dst = File(dir, rel)
            if (src.isFile) {
                dst.parentFile.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }
    }

    private fun fixture(): File {
        val dir = File(tempDir, "fixture-${System.nanoTime()}").apply { mkdirs() }
        writeFile(dir, "settings.gradle.kts", "rootProject.name = \"quality-fixture\"\n")
        writeFile(dir, "build.gradle.kts", "plugins { id(\"tramai.maintainability-baseline\") }\n")
        // The maintainability plugin reads these at apply time; use the real ones.
        copyFromRepo(
            dir,
            "config/quality/test-quality.yml",
            "config/quality/maintainability-deviations.yml",
            "config/quality/module-catalog.yml",
        )
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
            .withArguments(*args, "--no-build-cache", "--stacktrace")
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

    private fun runTask(
        dir: File,
        task: String,
    ): org.gradle.testkit.runner.BuildResult {
        val result = runner(dir, task).build()
        assertTrue(
            result.task(":$task")?.outcome == TaskOutcome.SUCCESS,
            "$task must succeed: ${result.output.take(1200)}",
        )
        return result
    }

    // ------------------------------------------------------------------
    // verifyModuleMatrixDrift
    // ------------------------------------------------------------------

    @Test
    fun `verifyModuleMatrixDrift passes on real generated matrix`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "config/quality/module-catalog.yml",
            "docs/reference/module-matrix.md",
        )
        runTask(dir, "verifyModuleMatrixDrift")
    }

    @Test
    fun `verifyModuleMatrixDrift fails when matrix drifts from catalog`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "config/quality/module-catalog.yml",
            "docs/reference/module-matrix.md",
        )
        val matrix = File(dir, "docs/reference/module-matrix.md")
        matrix.appendText("| tampered | row |\n")
        val result = runner(dir, "verifyModuleMatrixDrift").buildAndFail()
        assertContains(result.output, "[GENERATED_DOCUMENT_DRIFT] Module matrix drift: run ./gradlew generateModuleMatrix")
    }

    // ------------------------------------------------------------------
    // verifyJUnitTestSignatures
    // ------------------------------------------------------------------

    @Test
    fun `verifyJUnitTestSignatures passes on clean test sources`() {
        val dir = fixture()
        // A REAL repo test file (block-body @Test methods) — the scanner's
        // known-safe classification. Not a synthetic oracle.
        copyFromRepo(
            dir,
            "tramai-core/src/test/kotlin/dev/tramai/core/approval/IdempotencyKeyUtilTest.kt",
        )
        runTask(dir, "verifyJUnitTestSignatures")
    }

    @Test
    fun `verifyJUnitTestSignatures fails on expression-bodied test`() {
        val dir = fixture()
        writeFile(
            dir,
            "tramai-fake/src/test/kotlin/com/example/FakeTest.kt",
            """
            package com.example
            import kotlin.test.Test
            import kotlinx.coroutines.runBlocking
            class FakeTest {
                @Test
                fun `bad`() = runBlocking {
                    check(true)
                }
            }
            """.trimIndent(),
        )
        val result = runner(dir, "verifyJUnitTestSignatures").buildAndFail()
        assertContains(result.output, "non-Unit expression bodies would be silently skipped by JUnit")
    }

    // ------------------------------------------------------------------
    // Consumer-smoke compile tasks
    // ------------------------------------------------------------------

    @Test
    fun `java consumer smoke compiles real fixture and writes marker`() {
        val dir = consumerFixture("java")
        val result = runner(dir, ":examples:java-consumer-smoke:verifyJavaConsumerCompatibility").build()
        assertTrue(
            result.task(":examples:java-consumer-smoke:verifyJavaConsumerCompatibility")?.outcome == TaskOutcome.SUCCESS,
            result.output.take(1200),
        )
        val marker = File(dir, "examples/java-consumer-smoke/build/reports/maintainability/consumer-java.json")
        val found =
            dir
                .walkTopDown()
                .filter { it.name.contains("consumer-java") }
                .map { it.absolutePath }
                .toList()
        assertTrue(
            marker.isFile && marker.readText().contains("\"ok\" : true"),
            "marker must be written and ok. expected=$marker found=$found " +
                "content=${if (marker.isFile) marker.readText() else "<no file>"} " +
                "output=${result.output.take(2000)}",
        )
    }

    @Test
    fun `kotlin consumer smoke compiles real fixture and writes marker`() {
        val dir = consumerFixture("kotlin")
        val result = runner(dir, ":examples:kotlin-consumer-smoke:verifyKotlinConsumerCompatibility").build()
        assertTrue(
            result.task(":examples:kotlin-consumer-smoke:verifyKotlinConsumerCompatibility")?.outcome == TaskOutcome.SUCCESS,
            result.output.take(1200),
        )
        val marker = File(dir, "examples/kotlin-consumer-smoke/build/reports/maintainability/consumer-kotlin.json")
        val found =
            dir
                .walkTopDown()
                .filter { it.name.contains("consumer-kotlin") }
                .map { it.absolutePath }
                .toList()
        assertTrue(
            marker.isFile && marker.readText().contains("\"ok\" : true"),
            "marker must be written and ok. expected=$marker found=$found " +
                "content=${if (marker.isFile) marker.readText() else "<no file>"} " +
                "output=${result.output.take(2000)}",
        )
    }

    // ------------------------------------------------------------------
    // Fail-soft producer contract (C3/C4): the consumer tasks NEVER throw —
    // a compile failure must still produce SUCCESS + a marker recording it.
    // ------------------------------------------------------------------

    private fun consumerFixture(language: String): File {
        val dir = fixture()
        val (settingsLine, plugin, srcRel) =
            if (language == "java") {
                Triple(
                    "include(\":examples:java-consumer-smoke\")",
                    "id(\"java\")",
                    "examples/java-consumer-smoke/src",
                )
            } else {
                Triple(
                    "include(\":examples:kotlin-consumer-smoke\")",
                    "id(\"org.jetbrains.kotlin.jvm\")",
                    "examples/kotlin-consumer-smoke/src",
                )
            }
        copyFromRepo(
            dir,
            srcRel,
            "tramai-core/src/main/kotlin/dev/tramai/core/annotations",
            "tramai-core/src/main/kotlin/dev/tramai/core/model/Tool.kt",
            "tramai-core/src/main/kotlin/dev/tramai/core/model/ToolResult.kt",
            "tramai-core/src/main/kotlin/dev/tramai/core/model/ContentPart.kt",
            "tramai-core/src/main/kotlin/dev/tramai/core/model/ModelVisibleToolMessage.kt",
            "tramai-core/src/main/kotlin/dev/tramai/core/model/ToolFailureCode.kt",
            "tramai-core/src/main/kotlin/dev/tramai/core/policy",
        )
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "quality-fixture"
            include(":tramai-core")
            $settingsLine
            """.trimIndent(),
        )
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            plugins { id("org.jetbrains.kotlin.jvm") }
            group = "dev.tramai"
            version = "0.5.0"
            repositories { mavenCentral() }
            """.trimIndent(),
        )
        writeFile(
            dir,
            "examples/$language-consumer-smoke/build.gradle.kts",
            """
            plugins { $plugin }
            repositories { mavenCentral() }
            dependencies { implementation(project(":tramai-core")) }
            """.trimIndent(),
        )
        return dir
    }

    @Test
    fun `java consumer smoke is fail-soft - broken source records failure in marker`() {
        val dir = consumerFixture("java")
        val broken = File(dir, "examples/java-consumer-smoke/src/main/java/dev/tramai/examples/javaconsumer/JavaConsumerSmoke.java")
        broken.appendText("\npublic final class Broken { this is not valid java }\n")
        val result = runner(dir, ":examples:java-consumer-smoke:verifyJavaConsumerCompatibility").build()
        assertTrue(
            result.task(":examples:java-consumer-smoke:verifyJavaConsumerCompatibility")?.outcome == TaskOutcome.SUCCESS,
            "fail-soft task must not throw: ${result.output.take(1200)}",
        )
        val marker = File(dir, "examples/java-consumer-smoke/build/reports/maintainability/consumer-java.json")
        assertTrue(marker.isFile, "marker must be written even on compile failure")
        val content = marker.readText()
        assertTrue(content.contains("\"ok\" : false"), "marker must record ok:false, was: $content")
        assertTrue(content.contains("\"exitCode\""), "marker must record exitCode, was: $content")
    }

    @Test
    fun `kotlin consumer smoke is fail-soft - broken source records failure in marker`() {
        val dir = consumerFixture("kotlin")
        val broken = File(dir, "examples/kotlin-consumer-smoke/src/main/kotlin/dev/tramai/examples/kotlinconsumer/KotlinConsumerSmoke.kt")
        broken.appendText("\nfun broken() { this is not valid kotlin }\n")
        val result = runner(dir, ":examples:kotlin-consumer-smoke:verifyKotlinConsumerCompatibility").build()
        assertTrue(
            result.task(":examples:kotlin-consumer-smoke:verifyKotlinConsumerCompatibility")?.outcome == TaskOutcome.SUCCESS,
            "fail-soft task must not throw: ${result.output.take(1200)}",
        )
        val marker = File(dir, "examples/kotlin-consumer-smoke/build/reports/maintainability/consumer-kotlin.json")
        assertTrue(marker.isFile, "marker must be written even on compile failure")
        val content = marker.readText()
        assertTrue(content.contains("\"ok\" : false"), "marker must record ok:false, was: $content")
        assertTrue(content.contains("\"exitCode\""), "marker must record exitCode, was: $content")
    }

    // ------------------------------------------------------------------
    // R12-003: Gradle 9 Strict Task Dependency Validation
    // ------------------------------------------------------------------

    @Test
    fun `R12-003 consumer smoke and cancellation safety inputs pass Gradle 9 strict task dependency validation`() {
        val dir = consumerFixture("kotlin")
        initGit(dir)
        val result =
            runner(
                dir,
                ":examples:kotlin-consumer-smoke:verifyKotlinConsumerCompatibility",
                ":verifyCancellationSafety",
                "-PtramaiCancellationBaseSha=HEAD",
                "--warning-mode=fail",
            ).build()

        assertTrue(
            result.task(":examples:kotlin-consumer-smoke:verifyKotlinConsumerCompatibility")?.outcome == TaskOutcome.SUCCESS,
            "consumer smoke task must succeed under Gradle 9 strict validation: ${result.output.take(1200)}",
        )
        val output = result.output
        assertTrue(
            !output.contains("without declaring an explicit or implicit dependency"),
            "build must have zero implicit task dependency warnings under Gradle 9: ${output.take(1200)}",
        )
        assertTrue(
            !output.contains("overlapping outputs"),
            "build must have zero overlapping output warnings under Gradle 9: ${output.take(1200)}",
        )
    }

    private fun initGit(dir: File) {
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "initial fixture")
    }

    private fun git(
        dir: File,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }
}
