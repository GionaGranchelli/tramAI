package dev.tramai.build.quality

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

/**
 * Epic 10.3b wiring: verifyCriticalCoverage must be part of verifyPr and the
 * check task graph.
 *
 * ponytail: proven on a minimal TestKit fixture (root plugin + two java
 * modules) instead of a real-repo dry-run — the real-repo nested Gradle
 * configuration (45 modules + included build) OOM-kills the sandbox cgroup
 * even as a single `--dry-run`. The fixture exercises the same task-graph
 * edges with a fraction of the configuration work.
 */
class CoverageWiringTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `verifyPr owns verifyCriticalCoverage which depends on generateCoverageBaseline`() {
        val dir = wiringFixture()
        val result =
            GradleRunner
                .create()
                .withProjectDir(dir)
                .withGradleVersion("9.0.0")
                .withArguments(":generateCoverageBaseline", "--no-build-cache", "verifyPr", "--dry-run")
                .withPluginClasspath()
                .build()

        assertTrue(
            result.output.contains(":verifyCriticalCoverage"),
            "verifyPr must run verifyCriticalCoverage\n${result.output}",
        )
        assertTrue(
            result.output.contains(":generateCoverageBaseline"),
            "verifyCriticalCoverage must run generateCoverageBaseline\n${result.output}",
        )
    }

    // ── W4: mutation ratchet wiring (10.3d) ──

    @Test
    fun `W4 verifyPr owns verifyMutationRatchet`() {
        val dir = wiringFixture()
        val result =
            GradleRunner
                .create()
                .withProjectDir(dir)
                .withGradleVersion("9.0.0")
                .withArguments("verifyPr", "--no-build-cache", "--dry-run")
                .withPluginClasspath()
                .build()

        assertTrue(
            result.output.contains(":verifyMutationRatchet"),
            "verifyPr must run verifyMutationRatchet\n${result.output}",
        )
    }

    // ── W5: CI workflow wiring discriminators (10.3d / #29) ──

    @Test
    fun `W5 CI workflow invokes verifyMutationRatchet with PR base authority`() {
        val repoRoot = File(System.getProperty("tramai.repositoryRoot"))
        val ciWorkflow = File(repoRoot, ".github/workflows/ci.yml")
        assertTrue(ciWorkflow.isFile, "CI workflow file must exist: ${ciWorkflow.absolutePath}")
        assertCiMutationRatchetWiring(ciWorkflow.readText())
    }

    @Test
    fun `W5 discriminator deleting verifyMutationRatchet from CI workflow fails`() {
        val repoRoot = File(System.getProperty("tramai.repositoryRoot"))
        val content = File(repoRoot, ".github/workflows/ci.yml").readText()
        val mutated = content.replace("./gradlew verifyMutationRatchet", "./gradlew verifySomethingElse")
        val error =
            kotlin.test.assertFailsWith<AssertionError> {
                assertCiMutationRatchetWiring(mutated)
            }
        assertTrue(error.message!!.contains("verifyMutationRatchet"))
    }

    @Test
    fun `W5 discriminator deleting PR base sha property from CI workflow fails`() {
        val repoRoot = File(System.getProperty("tramai.repositoryRoot"))
        val content = File(repoRoot, ".github/workflows/ci.yml").readText()
        val mutated = content.replace("-PtramaiMutationBaseSha=\"\${{ github.event.pull_request.base.sha }}\"", "")
        val error =
            kotlin.test.assertFailsWith<AssertionError> {
                assertCiMutationRatchetWiring(mutated)
            }
        assertTrue(error.message!!.contains("tramaiMutationBaseSha"))
    }

    @Test
    fun `W5 discriminator changing PR ratchet condition away from pull request fails`() {
        val repoRoot = File(System.getProperty("tramai.repositoryRoot"))
        val content = File(repoRoot, ".github/workflows/ci.yml").readText()
        val mutated = content.replace("if: github.event_name == 'pull_request'", "if: github.event_name == 'push'")
        val error =
            kotlin.test.assertFailsWith<AssertionError> {
                assertCiMutationRatchetWiring(mutated)
            }
        assertTrue(error.message!!.contains("pull_request"))
    }

    private fun assertCiMutationRatchetWiring(content: String) {
        val prStep = extractStep(content, "Verify mutation ratchet against PR base")
        assertTrue(
            prStep.contains("if: github.event_name == 'pull_request'"),
            "PR mutation ratchet step must run on github.event_name == 'pull_request'",
        )
        assertTrue(
            prStep.contains("./gradlew verifyMutationRatchet"),
            "PR mutation ratchet step must invoke verifyMutationRatchet",
        )
        assertTrue(
            prStep.contains("-PtramaiMutationBaseSha=\"\${{ github.event.pull_request.base.sha }}\""),
            "PR mutation ratchet step must pass PR base SHA (-PtramaiMutationBaseSha)",
        )

        val pushStep = extractStep(content, "Verify mutation ratchet against previous master")
        assertTrue(
            pushStep.contains("github.event_name == 'push'"),
            "push mutation ratchet step must run on github.event_name == 'push'",
        )
        assertTrue(
            pushStep.contains("./gradlew verifyMutationRatchet"),
            "push mutation ratchet step must invoke verifyMutationRatchet",
        )
        assertTrue(
            pushStep.contains("-PtramaiMutationBaseSha=\"\${{ github.event.before }}\""),
            "push mutation ratchet step must pass push before SHA (-PtramaiMutationBaseSha)",
        )
    }

    private fun extractStep(
        content: String,
        stepName: String,
    ): String {
        val marker = "      - name: $stepName"
        val startIndex = content.indexOf(marker)
        assertTrue(startIndex != -1, "ci.yml must declare step '$stepName'")
        val afterStart = content.substring(startIndex + marker.length)
        val nextBoundary = Regex("\n(      - |  [a-zA-Z0-9_-]+:)").find(afterStart)
        val endIndex = nextBoundary?.range?.first ?: afterStart.length
        return content.substring(startIndex, startIndex + marker.length + endIndex)
    }

    // ── W2/W3: required verifyPr authorities must be fail-closed (review P1) ──

    @Test
    fun `W2 verifyPr without verifyModuleDocContract fails closed`() {
        val dir = wiringFixture(includeDocContractTask = false)
        val result =
            GradleRunner
                .create()
                .withProjectDir(dir)
                .withGradleVersion("9.0.0")
                .withArguments("verifyPr", "--dry-run")
                .withPluginClasspath()
                .buildAndFail()

        assertTrue(
            result.output.contains("verifyModuleDocContract"),
            "verifyPr must fail when verifyModuleDocContract is missing\n${result.output}",
        )
    }

    @Test
    fun `W3 verifyPr without build-logic authority fails closed`() {
        val dir = wiringFixture(includeBuildLogic = false)
        val result =
            GradleRunner
                .create()
                .withProjectDir(dir)
                .withGradleVersion("9.0.0")
                .withArguments("verifyPr", "--dry-run")
                .withPluginClasspath()
                .buildAndFail()

        assertTrue(
            result.output.contains("build-logic") && result.output.contains("required verification authority"),
            "verifyPr must fail when the build-logic included build is missing\n${result.output}",
        )
    }

    private fun wiringFixture(
        includeDocContractTask: Boolean = true,
        includeBuildLogic: Boolean = true,
    ): File {
        val dir = File(tempDir, "fixture").apply { mkdirs() }
        writeBuildScripts(dir, includeDocContractTask, includeBuildLogic)
        writeQualityConfig(dir)
        return dir
    }

    private fun writeBuildScripts(
        dir: File,
        includeDocContractTask: Boolean,
        includeBuildLogic: Boolean,
    ) {
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "coverage-wiring"
            include(":sample", ":tramai-core")
            ${if (includeBuildLogic) "includeBuild(\"build-logic\")" else ""}
            """.trimIndent(),
        )
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins { id("tramai.maintainability-baseline") }
            ${if (includeDocContractTask) "tasks.register(\"verifyModuleDocContract\")" else ""}
            allprojects {
                repositories {
                    // jacocoTestReport resolves jacocoAnt from a repository.
                    mavenCentral()
                }
            }
            """.trimIndent(),
        )
        if (includeBuildLogic) {
            writeBuildLogicFixture(dir)
        }
        writeFile(
            dir,
            "gradle.properties",
            """
            org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=384m
            org.gradle.workers.max=1
            """.trimIndent(),
        )
        writeFile(
            dir,
            "sample/build.gradle.kts",
            """
            plugins { `java-library` }
            """.trimIndent(),
        )
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            plugins { `java-library` }
            """.trimIndent(),
        )
    }

    /**
     * Minimal included build named build-logic with a :test task — the
     * authority verifyPr requires (review P1: fail-closed).
     */
    private fun writeBuildLogicFixture(dir: File) {
        writeFile(
            dir,
            "build-logic/settings.gradle.kts",
            """
            rootProject.name = "build-logic"
            """.trimIndent(),
        )
        writeFile(
            dir,
            "build-logic/build.gradle.kts",
            """
            tasks.register("test")
            """.trimIndent(),
        )
    }

    private fun writeQualityConfig(dir: File) {
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
    }

    private fun writeFile(
        dir: File,
        path: String,
        content: String,
    ) {
        File(dir, path).apply {
            parentFile.mkdirs()
            writeText(content, Charsets.UTF_8)
        }
    }
}
