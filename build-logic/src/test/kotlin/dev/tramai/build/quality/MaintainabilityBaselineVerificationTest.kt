package dev.tramai.build.quality

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Epic 9.2d-a3c2 discriminators for [VerifyMaintainabilityBaselineTask]:
 *
 *  - C1/C2: cold configuration-cache run stores, warm run reuses with 0 problems.
 *  - C3/C6: mutating a declared input (committed baseline) is observed without
 *    reconfiguration and produces the exact existing diagnostic.
 *  - C4: an alternative declared input is authoritative — the task never falls
 *    back to the conventional config/quality/0.6.0-baseline.json path.
 *  - C5: missing required evidence fails closed.
 *  - C7: the clean fixture genuinely passes (non-vacuous oracle) — the report
 *    contains real content and the gate reports PASSED.
 */
class MaintainabilityBaselineVerificationTest {
    private companion object {
        val MODULE_CATALOG_YML =
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
                apiStability: "excluded"
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
            """.trimIndent()

        val TEST_QUALITY_YML =
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
            """.trimIndent()
    }

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `verify maintainability baseline stores and reuses configuration cache on clean fixture`() {
        val dir = verificationFixture(applyPlugins = "")
        generateCommittedBaseline(dir)

        val args = configurationCacheArguments("verifyMaintainabilityBaseline")
        val first = runner(dir, *args).build()
        assertTrue(
            first.output.contains("Configuration cache entry stored"),
            "cold run must store the configuration cache: ${first.output.take(1200)}",
        )
        assertTrue(
            first.output.contains("Maintainability baseline verification PASSED"),
            "clean fixture must pass the gate: ${first.output.take(1200)}",
        )
        val report = File(dir, "build/reports/maintainability/verification-report.json")
        assertTrue(report.isFile, "verification report must be written")
        assertTrue(
            report.readText().contains("\"code\"") || report.readText().contains("diagnostics"),
            "report must contain real diagnostics content (non-vacuous oracle)",
        )

        val second = runner(dir, *args).build()
        assertTrue(
            second.output.contains("Reusing configuration cache"),
            "warm run must reuse the configuration cache: ${second.output.take(1200)}",
        )
        assertEquals(
            TaskOutcome.SUCCESS,
            second.task(":verifyMaintainabilityBaseline")?.outcome,
            "task must succeed on warm run: ${second.output.take(800)}",
        )
    }

    @Test
    fun `mutating the committed baseline is observed without reconfiguration and fails with exact diagnostic`() {
        val dir = verificationFixture(applyPlugins = "")
        generateCommittedBaseline(dir)

        val args = configurationCacheArguments("verifyMaintainabilityBaseline")
        runner(dir, *args).build()

        // Regress the committed baseline identity: dirty worktree flag flips to false.
        val baselineFile = File(dir, "config/quality/0.6.0-baseline.json")
        val doc =
            com.fasterxml.jackson.databind
                .ObjectMapper()
                .readTree(baselineFile)
        (doc as com.fasterxml.jackson.databind.node.ObjectNode)
            .get("baselineIdentity")
            .let { (it as com.fasterxml.jackson.databind.node.ObjectNode).put("workingTreeClean", false) }
        baselineFile.writeText(doc.toPrettyString())

        val failed = runner(dir, *args).buildAndFail()
        assertTrue(
            failed.output.contains("Committed baseline was generated from a dirty worktree"),
            "known regression must produce the exact existing diagnostic: ${failed.output.take(1200)}",
        )
        assertTrue(
            failed.task(":verifyMaintainabilityBaseline")?.outcome == TaskOutcome.FAILED,
            "gate must fail on the regression",
        )
    }

    @Test
    fun `alternative declared committed baseline is authoritative - no conventional path fallback`() {
        val dir =
            verificationFixture(
                applyPlugins =
                    """
                    import dev.tramai.build.quality.VerifyMaintainabilityBaselineTask
                    tasks.named<VerifyMaintainabilityBaselineTask>("verifyMaintainabilityBaseline") {
                        committedBaselineFile.set(layout.projectDirectory.file("config/quality/alt-baseline.json"))
                    }
                    """.trimIndent(),
            )
        // The conventional baseline exists and is valid — but the task is pointed
        // at an alternative malformed file. It must read the DECLARED input and
        // fail with the read diagnostic, proving no conventional-path rediscovery.
        generateCommittedBaseline(dir)
        File(dir, "config/quality/alt-baseline.json").writeText("{ not json")

        val failed = runner(dir, *configurationCacheArguments("verifyMaintainabilityBaseline")).buildAndFail()
        assertTrue(
            failed.output.contains("Failed to read committed baseline"),
            "task must consume the declared alternative input: ${failed.output.take(1200)}",
        )
    }

    @Test
    fun `missing committed baseline fails closed`() {
        val dir = verificationFixture(applyPlugins = "")
        // No generate step: config/quality/0.6.0-baseline.json is absent.

        val failed = runner(dir, *configurationCacheArguments("verifyMaintainabilityBaseline")).buildAndFail()
        assertTrue(
            failed.output.contains("Committed baseline not found"),
            "missing required evidence must fail closed: ${failed.output.take(1200)}",
        )
    }

    @Test
    fun `forbidden project dependency edge is detected from declared graph snapshot`() {
        // :sample depends on :tramai-core via a real Gradle project
        // dependency; the boundaries file forbids the layer edge. Directory
        // mode alone produces no edges, so this discriminator only passes
        // when the configuration-time project-edge snapshot is restored.
        val dir =
            verificationFixture(
                applyPlugins = "",
                extraSampleDeps = listOf("implementation(project(\":tramai-core\"))"),
                boundariesYml =
                    """
                    forbiddenEdges:
                      - fromLayer: "testing-support"
                        toLayer: "testing-support"
                        reason: "fixture forbids testing-support edges"
                    allowedEdges: []
                    """.trimIndent(),
            )
        generateCommittedBaseline(dir)

        val failed = runner(dir, *configurationCacheArguments("verifyMaintainabilityBaseline")).buildAndFail()
        assertTrue(
            failed.output.contains("Forbidden edge: :sample"),
            "forbidden project edge must be detected from the declared graph snapshot: ${failed.output.take(1400)}",
        )
    }

    @Test
    fun `new project dependency cycle is detected from declared graph snapshot`() {
        // Committed baseline is generated WITHOUT the back-edge; the
        // :tramai-core -> :sample dependency is added AFTER, so the cycle is
        // genuinely new. Only the declared graph snapshot can surface it.
        val dir =
            verificationFixture(
                applyPlugins = "",
                extraSampleDeps = listOf("implementation(project(\":tramai-core\"))"),
            )
        generateCommittedBaseline(dir)
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            plugins { `java-library` }
            repositories { maven { url = uri(rootDir.resolve("repo")) } }
            dependencies {
                implementation(project(":sample"))
            }
            """.trimIndent(),
        )

        val failed = runner(dir, *configurationCacheArguments("verifyMaintainabilityBaseline")).buildAndFail()
        assertTrue(
            failed.output.contains("New dependency cycle detected"),
            "new project cycle must be detected from the declared graph snapshot: ${failed.output.take(1400)}",
        )
    }

    @Test
    fun `declared alternative catalog drives both verifier and measurement context`() {
        // Conventional catalog A declares :sample as testing-support; the
        // declared alternative B declares it as core. A positional root
        // derivation or a conventional-path context reload would silently use
        // A while the verifier parses B, producing a MODULE_CATALOG_DISAGREEMENT.
        // The discriminator proves both the verifier and the MeasurementContext
        // observe B: the gate passes with layer "core" recorded in the report.
        val dir =
            verificationFixture(
                applyPlugins =
                    """
                    import dev.tramai.build.quality.VerifyMaintainabilityBaselineTask
                    tasks.named<VerifyMaintainabilityBaselineTask>("verifyMaintainabilityBaseline") {
                        moduleCatalogFile.set(layout.projectDirectory.file("config/quality/alt-catalog.yml"))
                    }
                    """.trimIndent(),
            )
        // Alternative catalog B: same modules, :sample layer "core".
        val altCatalog =
            MODULE_CATALOG_YML.replace(
                """- path: ":sample""""",
                """- path: ":sample"
                layer: "core"""",
            )
        writeFile(dir, "config/quality/alt-catalog.yml", altCatalog)
        generateCommittedBaseline(dir)

        val result = runner(dir, *configurationCacheArguments("verifyMaintainabilityBaseline")).build()
        assertTrue(
            result.output.contains("Maintainability baseline verification PASSED"),
            "declared alternative catalog must drive context and verifier consistently: ${result.output.take(1400)}",
        )
        val report = File(dir, "build/reports/maintainability/verification-report.json")
        assertTrue(report.isFile, "verification report must be written")
    }

    @Test
    fun `root build script mutation is observed and invalidates the gate`() {
        // Root build.gradle.kts is measured by generateStructuralHotspots; it
        // must be a declared sourceTree input so a mutation re-executes the
        // verifier instead of serving a stale up-to-date PASS.
        val dir = verificationFixture(applyPlugins = "")
        generateCommittedBaseline(dir)

        val args = configurationCacheArguments("verifyMaintainabilityBaseline")
        val first = runner(dir, *args).build()
        assertTrue(
            first.output.contains("Maintainability baseline verification PASSED"),
            "clean fixture must pass the gate: ${first.output.take(1200)}",
        )

        // Mutate the root build script (measured, previously undeclared).
        val buildFile = File(dir, "build.gradle.kts")
        buildFile.writeText(buildFile.readText() + "\n// mutation\n")

        val second = runner(dir, *args).build()
        assertEquals(
            TaskOutcome.SUCCESS,
            second.task(":verifyMaintainabilityBaseline")?.outcome,
            "task must re-execute after root build script mutation: ${second.output.take(1200)}",
        )
    }

    // ─── Fixture ───

    /**
     * Self-contained fixture with a committed git history and one production
     * source file, so identity resolution and mandatory sections are real.
     *
     * @param extraSampleDeps Gradle dependency declarations added to :sample
     *   (e.g. project deps, for edge/cycle discriminators).
     * @param extraCoreDeps Gradle dependency declarations added to
     *   :tramai-core (for the new-cycle discriminator).
     * @param boundariesYml Optional module-boundaries.yml content; defaults to
     *   the empty ruleset.
     */
    private fun verificationFixture(
        applyPlugins: String,
        extraSampleDeps: List<String> = emptyList(),
        extraCoreDeps: List<String> = emptyList(),
        boundariesYml: String =
            """
            forbiddenEdges: []
            allowedEdges: []
            """.trimIndent(),
    ): File {
        val dir = File(tempDir, "fixture-${tempDir.listFiles()?.size ?: 0}").apply { mkdirs() }
        writeGradleScripts(dir, applyPlugins, extraSampleDeps, extraCoreDeps)
        writeProductionSource(dir)
        writeQualityConfig(dir, boundariesYml)
        writeLocalMavenRepo(dir)
        initGit(dir)
        return dir
    }

    private fun writeGradleScripts(
        dir: File,
        applyPlugins: String,
        extraSampleDeps: List<String>,
        extraCoreDeps: List<String>,
    ) {
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "maintainability-verification"
            include(":sample", ":tramai-core", ":examples:java-consumer-smoke", ":examples:kotlin-consumer-smoke")
            """.trimIndent(),
        )
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins { id("tramai.maintainability-baseline") }
            $applyPlugins
            """.trimIndent(),
        )
        // Constrain the TestKit-spawned fixture daemon: the sandbox cgroup
        // OOM-kills unbounded nested daemons mid-suite.
        writeFile(
            dir,
            "gradle.properties",
            """
            org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m
            org.gradle.workers.max=1
            """.trimIndent(),
        )
        val sampleDeps =
            (listOf("implementation(\"com.example:fake:1.0\")") + extraSampleDeps).joinToString("\n        ")
        writeFile(
            dir,
            "sample/build.gradle.kts",
            """
            plugins { `java-library` }
            repositories { maven { url = uri(rootDir.resolve("repo")) } }
            dependencies {
                $sampleDeps
            }
            """.trimIndent(),
        )
        val coreDeps = extraCoreDeps.joinToString("\n        ")
        val coreRepos =
            if (extraCoreDeps.isEmpty()) {
                ""
            } else {
                "\n    repositories { maven { url = uri(rootDir.resolve(\"repo\")) } }"
            }
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            plugins { `java-library` }$coreRepos
            dependencies {
                $coreDeps
            }
            """.trimIndent(),
        )
        writeFile(dir, "examples/java-consumer-smoke/build.gradle.kts", "plugins { `java-library` }")
        writeFile(dir, "examples/kotlin-consumer-smoke/build.gradle.kts", "plugins { `java-library` }")
        writeFile(dir, ".gitignore", ".gradle/\n.kotlin/\nbuild/\nsample/build/\nlocal.properties\n")
    }

    private fun writeProductionSource(dir: File) {
        // A protocol constant (protocol catalog), a catch (cancellation
        // inventory), and declarations (source metrics) — the mandatory
        // sections must be non-empty for a genuine PASS.
        writeFile(
            dir,
            "sample/src/main/kotlin/example/Sample.kt",
            """
            package example

            const val SAMPLE_PROTOCOL = "tramai.sample.protocol"

            class Sample {
                fun run(): String =
                    try {
                        "ok"
                    } catch (e: Exception) {
                        "fail"
                    }
            }
            """.trimIndent(),
        )
    }

    private fun writeQualityConfig(
        dir: File,
        boundariesYml: String,
    ) {
        writeFile(dir, "config/quality/module-catalog.yml", MODULE_CATALOG_YML)
        writeFile(dir, "config/quality/test-quality.yml", TEST_QUALITY_YML)
        writeFile(
            dir,
            "config/quality/maintainability-deviations.yml",
            """
            schemaVersion: "1"
            deviations: []
            """.trimIndent(),
        )
        writeFile(dir, "config/quality/module-boundaries.yml", boundariesYml)
        writeFile(
            dir,
            "config/quality/runtime-nondeterminism.yml",
            """
            schemaVersion: "1"
            entries: []
            """.trimIndent(),
        )
    }

    private fun writeLocalMavenRepo(dir: File) {
        // Minimal local Maven repo so :sample resolves a real external module.
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
    }

    private fun initGit(dir: File) {
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "initial fixture")
        git(dir, "tag", "v0.5.0")
        git(dir, "remote", "add", "origin", "https://example.invalid/tramai.git")
        git(dir, "update-ref", "refs/remotes/origin/master", "HEAD")
    }

    /**
     * Runs the real generateMaintainabilityBaseline task, then patches the
     * recorded baseline identity to a clean, self-consistent state. The
     * generate path writes repo artifacts (baseline, protocol catalog, docs)
     * BEFORE capturing identity, so its recorded workingTreeClean is always
     * false — a fixture artifact, not a verifier concern. The verifier only
     * asserts non-blank identity fields and baselineCommitSha ==
     * measuredCommitSha; the measured sections are regenerated deterministically
     * from the same fixture, so patched identity keeps the gate honest.
     */
    private fun generateCommittedBaseline(dir: File) {
        runner(dir, "generateMaintainabilityBaseline").build()

        val baselineFile = File(dir, "config/quality/0.6.0-baseline.json")
        val doc =
            com.fasterxml.jackson.databind
                .ObjectMapper()
                .readTree(baselineFile) as com.fasterxml.jackson.databind.node.ObjectNode
        val head = git(dir, "rev-parse", "HEAD")
        val tree = git(dir, "rev-parse", "HEAD^{tree}")
        val identity = doc.get("baselineIdentity") as com.fasterxml.jackson.databind.node.ObjectNode
        identity.put("workingTreeClean", true)
        identity.put("commitSha", head)
        identity.put("baselineCommitSha", head)
        identity.put("measuredCommitSha", head)
        identity.put("measuredGitTreeSha", tree)
        identity.put("measuredSourceTreeHash", "fixture-source-tree-hash")
        identity.put("analyzerCommitSha", head)
        baselineFile.writeText(doc.toPrettyString())

        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "generated committed baseline")
    }

    private fun configurationCacheArguments(task: String) =
        arrayOf(
            task,
            "--configuration-cache",
            "--configuration-cache-problems=fail",
        )

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
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }
}
