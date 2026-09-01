package dev.tramai.build.quality

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Epic 10.3a P1 discriminator: REAL cross-module (Option-B) coverage proof.
 *
 * Fixture topology (the reviewer's required shape):
 *
 *   root
 *   ├── critical/   — production code, NO tests of its own
 *   └── tck/        — test depends on critical, executes CriticalThing
 *
 * `:critical:jacocoTestReport` must show CriticalThing covered > 0, crediting
 * the tck module's execution. This is mutation-sensitive by construction:
 * if the plugin regressed to module-local execution data (Option A),
 * critical's own `test` task runs nothing → CriticalThing covered == 0 → RED.
 */
class CrossModuleCoverageTest {
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
              - path: ":critical"
                <<: *internal
              - path: ":tck"
                <<: *internal
            """.trimIndent()

        val VENDOR_MODULES =
            listOf(
                VendorModule("org.junit.jupiter", "junit-jupiter-api", "5.12.2", emptyList(), null),
                VendorModule(
                    "org.junit.jupiter",
                    "junit-jupiter-engine",
                    "5.12.2",
                    listOf(
                        Dep("org.junit.jupiter", "junit-jupiter-api", "5.12.2"),
                        Dep("org.junit.platform", "junit-platform-engine", "1.12.2"),
                        Dep("org.opentest4j", "opentest4j", "1.3.0"),
                        Dep("org.apiguardian", "apiguardian-api", "1.1.2"),
                    ),
                    null,
                ),
                VendorModule("org.junit.platform", "junit-platform-commons", "1.12.2", emptyList(), null),
                VendorModule(
                    "org.junit.platform",
                    "junit-platform-engine",
                    "1.12.2",
                    listOf(
                        Dep("org.junit.platform", "junit-platform-commons", "1.12.2"),
                        Dep("org.opentest4j", "opentest4j", "1.3.0"),
                    ),
                    null,
                ),
                VendorModule(
                    "org.junit.platform",
                    "junit-platform-launcher",
                    "1.12.2",
                    listOf(
                        Dep("org.junit.platform", "junit-platform-engine", "1.12.2"),
                        Dep("org.junit.platform", "junit-platform-commons", "1.12.2"),
                    ),
                    null,
                ),
                VendorModule("org.apiguardian", "apiguardian-api", "1.1.2", emptyList(), null),
                VendorModule("org.opentest4j", "opentest4j", "1.3.0", emptyList(), null),
                // Gradle's jacoco plugin resolves org.jacoco.agent:<v>:runtime
                // (jacocoAgent) plus the jacocoAnt stack (ant → core/report → asm).
                VendorModule("org.jacoco", "org.jacoco.agent", "0.8.13", emptyList(), "runtime"),
                VendorModule(
                    "org.jacoco",
                    "org.jacoco.ant",
                    "0.8.13",
                    listOf(
                        Dep("org.jacoco", "org.jacoco.core", "0.8.13"),
                        Dep("org.jacoco", "org.jacoco.report", "0.8.13"),
                    ),
                    null,
                ),
                VendorModule(
                    "org.jacoco",
                    "org.jacoco.core",
                    "0.8.13",
                    listOf(
                        Dep("org.ow2.asm", "asm", "9.8"),
                        Dep("org.ow2.asm", "asm-commons", "9.8"),
                        Dep("org.ow2.asm", "asm-tree", "9.8"),
                    ),
                    null,
                ),
                VendorModule(
                    "org.jacoco",
                    "org.jacoco.report",
                    "0.8.13",
                    listOf(
                        Dep("org.jacoco", "org.jacoco.core", "0.8.13"),
                    ),
                    null,
                ),
                VendorModule("org.ow2.asm", "asm", "9.8", emptyList(), null),
                VendorModule(
                    "org.ow2.asm",
                    "asm-commons",
                    "9.8",
                    listOf(
                        Dep("org.ow2.asm", "asm", "9.8"),
                        Dep("org.ow2.asm", "asm-tree", "9.8"),
                    ),
                    null,
                ),
                VendorModule(
                    "org.ow2.asm",
                    "asm-tree",
                    "9.8",
                    listOf(
                        Dep("org.ow2.asm", "asm", "9.8"),
                    ),
                    null,
                ),
            )
    }

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `A10 critical report credits tck module execution of its production class`() {
        val dir = fixture(moduleLocalExec = false)
        runJacocoReport(dir)
        assertCriticalThingCovered(dir, expectedCovered = true)
    }

    @Test
    fun `A10 mutation discriminator — module-local exec data does not credit cross-module execution`() {
        // Simulates reverting Option B → A: critical's report consumes only
        // critical/test.exec (its own empty test run). CriticalThing must be
        // uncovered — proving the primary test above would go RED under that
        // reversion.
        val dir = fixture(moduleLocalExec = true)
        runJacocoReport(dir)
        assertCriticalThingCovered(dir, expectedCovered = false)
    }

    // ── fixture ────────────────────────────────────────────────────────────

    private fun fixture(moduleLocalExec: Boolean): File {
        val dir = File(tempDir, "fixture-${System.nanoTime()}").apply { mkdirs() }
        writeRootFiles(dir)
        writeQualityConfig(dir)
        writeVendoredJunitRepo(dir)
        writeCriticalModule(dir, moduleLocalExec)
        writeTckModule(dir)
        return dir
    }

    private fun writeRootFiles(dir: File) {
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "cross-module-coverage"
            include(":critical", ":tck")
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
            org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m
            org.gradle.workers.max=1
            """.trimIndent(),
        )
    }

    private fun writeCriticalModule(
        dir: File,
        moduleLocalExec: Boolean,
    ) {
        // critical: production code only, NO tests
        writeFile(
            dir,
            "critical/src/main/java/critical/CriticalThing.java",
            """
            package critical;

            public final class CriticalThing {
                public static int compute(int input) {
                    int result = input * 2;
                    if (input > 10) {
                        result += 1;
                    }
                    return result;
                }
            }
            """.trimIndent(),
        )
        val reportOverride =
            if (moduleLocalExec) {
                """
                tasks.named("jacocoTestReport", org.gradle.testing.jacoco.tasks.JacocoReport::class.java) {
                    executionData.setFrom(layout.buildDirectory.file("jacoco/test.exec"))
                }
                """.trimIndent()
            } else {
                ""
            }
        writeFile(
            dir,
            "critical/build.gradle.kts",
            """
            plugins { `java` }
            repositories { maven { url = uri(rootDir.resolve("repo")) } }
            $reportOverride
            """.trimIndent(),
        )
    }

    private fun writeTckModule(dir: File) {
        // tck: test depends on critical and executes CriticalThing
        writeFile(
            dir,
            "tck/build.gradle.kts",
            """
            plugins { `java` }
            repositories { maven { url = uri(rootDir.resolve("repo")) } }
            dependencies {
                implementation(project(":critical"))
                testImplementation("org.junit.jupiter:junit-jupiter-engine:5.12.2")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
            }
            tasks.test { useJUnitPlatform() }
            """.trimIndent(),
        )
        writeFile(
            dir,
            "tck/src/test/java/tck/CriticalThingTckTest.java",
            """
            package tck;

            import critical.CriticalThing;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            class CriticalThingTckTest {
                @Test
                void computesTwice() {
                    assertEquals(43, CriticalThing.compute(21));
                }
            }
            """.trimIndent(),
        )
    }

    private fun runJacocoReport(dir: File) {
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withGradleVersion("9.0.0")
            .withArguments(":critical:jacocoTestReport", "--stacktrace")
            .withPluginClasspath()
            .build()
    }

    private fun assertCriticalThingCovered(
        dir: File,
        expectedCovered: Boolean,
    ) {
        val xml = dir.resolve("critical/build/reports/jacoco/test/jacocoTestReport.xml")
        if (expectedCovered) {
            assertTrue(xml.isFile, "Option-B critical jacocoTestReport.xml must exist: ${xml.absolutePath}")
            val covered = criticalThingCovered(xml)!!
            assertTrue(
                covered > 0,
                "CriticalThing must be covered by tck execution (Option B); covered=$covered",
            )
        } else {
            // Option A (module-local exec): critical has no tests of its own →
            // empty exec data → jacocoTestReport is SKIPPED (no XML), or at
            // best produces a report with CriticalThing fully uncovered. Both
            // states prove the topology difference the primary test detects.
            criticalThingCovered(xml)?.let {
                assertEquals(
                    0,
                    it,
                    "module-local exec data must leave CriticalThing uncovered (Option A)",
                )
            }
        }
    }

    private fun criticalThingCovered(xml: File): Int? {
        if (!xml.isFile) return null
        val lineCounter =
            Regex(
                """<sourcefile name="CriticalThing\.java">.*?</sourcefile>""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(xml.readText())
                ?.let { sourcefile ->
                    Regex(
                        """<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""",
                    ).find(sourcefile.value)
                }
        return lineCounter?.groupValues?.get(2)?.toInt()
    }

    private fun writeQualityConfig(dir: File) {
        writeFile(
            dir,
            "config/quality/module-catalog.yml",
            MODULE_CATALOG_YML,
        )
        writeQualityYmls(dir)
    }

    private fun writeQualityYmls(dir: File) {
        writeFile(
            dir,
            "config/quality/test-quality.yml",
            """
            schemaVersion: "1"
            criticalModules: [":critical"]
            coverage:
              regressionTolerancePercentagePoints: 1.0
              exclusions: []
            mutation:
              regressionTolerancePercentagePoints: 1.0
              targetFamilies:
                sample:
                  modules: [":critical"]
                  targetClasses: ["critical.*"]
                  targetTests: ["tck.*"]
            """.trimIndent(),
        )
        writeFile(
            dir,
            "config/quality/maintainability-deviations.yml",
            """
            schemaVersion: "1"
            deviations: []
            """.trimIndent(),
        )
        writeFile(
            dir,
            "config/quality/module-boundaries.yml",
            """
            forbiddenEdges: []
            allowedEdges: []
            """.trimIndent(),
        )
        writeFile(
            dir,
            "config/quality/runtime-nondeterminism.yml",
            """
            schemaVersion: "1"
            entries: []
            """.trimIndent(),
        )
    }

    private fun writeVendoredJunitRepo(dir: File) {
        // Vendor JUnit 5.11.4 jars from the local Gradle cache into a hermetic
        // repo so the fixture never touches the network. POMs carry the real
        // transitive edges (engine → api, platform-engine, opentest4j,
        // apiguardian; platform-engine → commons; launcher → engine) so the
        // resolution is complete.
        val cacheRoot = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1")
        for (module in VENDOR_MODULES) {
            val groupDir = File(cacheRoot, "${module.group}/${module.artifact}/${module.version}")
            val jar =
                groupDir
                    .walkTopDown()
                    .firstOrNull { it.isFile && it.extension == "jar" && !it.name.contains("sources") }
                    ?: error("JUnit jar not in local cache: ${module.group}:${module.artifact}:${module.version}")
            val repoDir = dir.resolve("repo/${module.group.replace('.', '/')}/${module.artifact}/${module.version}")
            repoDir.mkdirs()
            // Gradle's jacoco plugin requests org.jacoco.agent:<v> AND
            // org.jacoco.agent:<v>:runtime (the jacocoAgent configuration).
            // The cache stores one jar; write it under both names.
            jar.copyTo(repoDir.resolve("${module.artifact}-${module.version}.jar"), overwrite = true)
            if (module.classifier != null) {
                jar.copyTo(
                    repoDir.resolve("${module.artifact}-${module.version}-${module.classifier}.jar"),
                    overwrite = true,
                )
            }
            val deps =
                module.deps.joinToString("\n") { d ->
                    """
                    <dependency>
                      <groupId>${d.group}</groupId>
                      <artifactId>${d.artifact}</artifactId>
                      <version>${d.version}</version>
                    </dependency>
                    """.trimIndent()
                }
            writeFile(
                repoDir,
                "${module.artifact}-${module.version}.pom",
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>${module.group}</groupId>
                  <artifactId>${module.artifact}</artifactId>
                  <version>${module.version}</version>
                  <dependencies>
                  $deps
                  </dependencies>
                </project>
                """.trimIndent(),
            )
        }
    }

    private data class VendorModule(
        val group: String,
        val artifact: String,
        val version: String,
        val deps: List<Dep>,
        val classifier: String?,
    )

    private data class Dep(
        val group: String,
        val artifact: String,
        val version: String,
    )

    private fun writeFile(
        base: File,
        relativePath: String,
        content: String,
    ) {
        val target = File(base, relativePath)
        target.parentFile.mkdirs()
        target.writeText(content)
    }
}
