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

    private fun wiringFixture(): File {
        val dir = File(tempDir, "fixture").apply { mkdirs() }
        writeBuildScripts(dir)
        writeQualityConfig(dir)
        return dir
    }

    private fun writeBuildScripts(dir: File) {
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "coverage-wiring"
            include(":sample", ":tramai-core")
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
